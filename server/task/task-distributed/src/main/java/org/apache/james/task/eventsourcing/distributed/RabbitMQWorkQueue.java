/**
 * *************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                   *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ***************************************************************/

package org.apache.james.task.eventsourcing.distributed;

import static com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.backends.rabbitmq.Constants;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.server.task.json.JsonTaskSerializer;
import org.apache.james.task.Task;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManagerWorker;
import org.apache.james.task.TaskWithId;
import org.apache.james.task.WorkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

public class RabbitMQWorkQueue implements WorkQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQWorkQueue.class);

    static final String EXCHANGE_NAME = "taskManagerWorkQueueExchange";
    static final String QUEUE_NAME = "taskManagerWorkQueue";
    static final String ROUTING_KEY = "taskManagerWorkQueueRoutingKey";

    static final String CANCEL_REQUESTS_EXCHANGE_NAME = "taskManagerCancelRequestsExchange";
    static final String CANCEL_REQUESTS_ROUTING_KEY = "taskManagerCancelRequestsRoutingKey";
    private static final String CANCEL_REQUESTS_QUEUE_NAME_PREFIX = "taskManagerCancelRequestsQueue";
    public static final String TASK_ID = "taskId";

    public static final int NUM_RETRIES = 8;
    public static final Duration FIRST_BACKOFF = Duration.ofMillis(100);
    public static final Duration FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private final TaskManagerWorker worker;
    private final JsonTaskSerializer taskSerializer;
    private final Sender sender;
    private final ReceiverProvider receiverProvider;
    private Receiver receiver;
    private UnicastProcessor<TaskId> sendCancelRequestsQueue;
    private Disposable sendCancelRequestsQueueHandle;
    private Disposable receiverHandle;
    private Disposable cancelRequestListenerHandle;
    private Receiver cancelRequestListener;

    public RabbitMQWorkQueue(TaskManagerWorker worker, Sender sender, ReceiverProvider receiverProvider, JsonTaskSerializer taskSerializer) {
        this.worker = worker;
        this.receiverProvider = receiverProvider;
        this.sender = sender;
        this.taskSerializer = taskSerializer;
    }

    @Override
    public void start() {
        startWorkqueue();
        listenToCancelRequests();
    }

    private void startWorkqueue() {
        declareQueue();
        consumeWorkqueue();
    }

    @VisibleForTesting
    void declareQueue() {
        Mono<AMQP.Exchange.DeclareOk> declareExchange = sender
            .declareExchange(ExchangeSpecification.exchange(EXCHANGE_NAME))
            .retryBackoff(NUM_RETRIES, FIRST_BACKOFF, FOREVER);
        Mono<AMQP.Queue.DeclareOk> declareQueue = sender
            .declare(QueueSpecification.queue(QUEUE_NAME).durable(true).arguments(Constants.WITH_SINGLE_ACTIVE_CONSUMER))
            .retryBackoff(NUM_RETRIES, FIRST_BACKOFF, FOREVER);
        Mono<AMQP.Queue.BindOk> bindQueueToExchange = sender
            .bind(BindingSpecification.binding(EXCHANGE_NAME, ROUTING_KEY, QUEUE_NAME))
            .retryBackoff(NUM_RETRIES, FIRST_BACKOFF, FOREVER);

        declareExchange
            .then(declareQueue)
            .then(bindQueueToExchange)
            .block();
    }

    private void consumeWorkqueue() {
        receiver = receiverProvider.createReceiver();
        receiverHandle = receiver.consumeManualAck(QUEUE_NAME, new ConsumeOptions())
            .subscribeOn(Schedulers.elastic())
            .concatMap(this::executeTask)
            .subscribe();
    }

    private Mono<Task.Result> executeTask(AcknowledgableDelivery delivery) {
        TaskId taskId = TaskId.fromString(delivery.getProperties().getHeaders().get(TASK_ID).toString());
        return Mono.fromCallable(() -> {
            delivery.ack();
            return new String(delivery.getBody(), StandardCharsets.UTF_8);
        }).flatMap(json ->
            deserialize(json, taskId)
                .flatMap(task -> executeOnWorker(taskId, task)));
    }

    private Mono<Task> deserialize(String json, TaskId taskId) {
        return Mono.fromCallable(() -> taskSerializer.deserialize(json))
            .doOnError(error -> {
                String errorMessage = String.format("Unable to deserialize submitted Task %s", taskId.asString());
                LOGGER.error(errorMessage, error);
                worker.fail(taskId, Optional.empty(), errorMessage, error);
            })
            .onErrorResume(error -> Mono.empty());
    }

    private Mono<Task.Result> executeOnWorker(TaskId taskId, Task task) {
        return worker.executeTask(new TaskWithId(taskId, task))
            .doOnError(error -> {
                String errorMessage = String.format("Unable to run submitted Task %s", taskId.asString());
                LOGGER.warn(errorMessage, error);
                worker.fail(taskId, task.details(), errorMessage, error);
            })
            .onErrorResume(error -> Mono.empty());
    }

    private void listenToCancelRequests() {
        String queueName = CANCEL_REQUESTS_QUEUE_NAME_PREFIX + UUID.randomUUID().toString();

        sender.declareExchange(ExchangeSpecification.exchange(CANCEL_REQUESTS_EXCHANGE_NAME)).block();
        sender.declare(QueueSpecification.queue(queueName).durable(false).autoDelete(true)).block();
        sender.bind(BindingSpecification.binding(CANCEL_REQUESTS_EXCHANGE_NAME, CANCEL_REQUESTS_ROUTING_KEY, queueName)).block();
        registerCancelRequestsListener(queueName);

        sendCancelRequestsQueue = UnicastProcessor.create();
        sendCancelRequestsQueueHandle = sender
            .send(sendCancelRequestsQueue.map(this::makeCancelRequestMessage))
            .subscribeOn(Schedulers.elastic())
            .subscribe();
    }

    private void registerCancelRequestsListener(String queueName) {
        cancelRequestListener = receiverProvider.createReceiver();
        cancelRequestListenerHandle = cancelRequestListener
            .consumeAutoAck(queueName)
            .subscribeOn(Schedulers.elastic())
            .map(this::readCancelRequestMessage)
            .doOnNext(worker::cancelTask)
            .subscribe();
    }

    private TaskId readCancelRequestMessage(Delivery delivery) {
        String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
        return TaskId.fromString(message);
    }

    private OutboundMessage makeCancelRequestMessage(TaskId taskId) {
        byte[] payload = taskId.asString().getBytes(StandardCharsets.UTF_8);
        AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder().build();
        return new OutboundMessage(CANCEL_REQUESTS_EXCHANGE_NAME, CANCEL_REQUESTS_ROUTING_KEY, basicProperties, payload);
    }

    @Override
    public void submit(TaskWithId taskWithId) {
        try {
            byte[] payload = taskSerializer.serialize(taskWithId.getTask()).getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder()
                .deliveryMode(PERSISTENT_TEXT_PLAIN.getDeliveryMode())
                .priority(PERSISTENT_TEXT_PLAIN.getPriority())
                .contentType(PERSISTENT_TEXT_PLAIN.getContentType())
                .headers(ImmutableMap.of(TASK_ID, taskWithId.getId().asString()))
                .build();

            OutboundMessage outboundMessage = new OutboundMessage(EXCHANGE_NAME, ROUTING_KEY, basicProperties, payload);
            sender.send(Mono.just(outboundMessage)).block();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cancel(TaskId taskId) {
        sendCancelRequestsQueue.onNext(taskId);
    }

    @Override
    public void close() {
        Optional.ofNullable(receiverHandle).ifPresent(Disposable::dispose);
        Optional.ofNullable(receiver).ifPresent(Receiver::close);
        Optional.ofNullable(sendCancelRequestsQueueHandle).ifPresent(Disposable::dispose);
        Optional.ofNullable(cancelRequestListenerHandle).ifPresent(Disposable::dispose);
        Optional.ofNullable(cancelRequestListener).ifPresent(Receiver::close);
    }
}
