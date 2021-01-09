/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.queue.rabbitmq;

import static org.apache.james.backends.rabbitmq.Constants.AUTO_DELETE;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.backends.rabbitmq.Constants.EXCLUSIVE;
import static org.apache.james.backends.rabbitmq.Constants.NO_ARGUMENTS;
import static org.apache.james.queue.api.MailQueue.QUEUE_SIZE_METRIC_NAME_PREFIX;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.inject.Inject;
import javax.mail.internet.MimeMessage;

import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.Store;
import org.apache.james.blob.mail.MimeMessagePartsId;
import org.apache.james.blob.mail.MimeMessageStore;
import org.apache.james.metrics.api.GaugeRegistry;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.queue.api.MailQueueFactory;
import org.apache.james.queue.api.MailQueueItemDecoratorFactory;
import org.apache.james.queue.rabbitmq.view.RabbitMQMailQueueConfiguration;
import org.apache.james.queue.rabbitmq.view.api.MailQueueView;

import com.github.fge.lambdas.Throwing;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import reactor.core.publisher.Flux;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class RabbitMQMailQueueFactory implements MailQueueFactory<RabbitMQMailQueue> {

    @VisibleForTesting static class PrivateFactory {
        private final MetricFactory metricFactory;
        private final GaugeRegistry gaugeRegistry;
        private final ReceiverProvider receiverProvider;
        private final Sender sender;
        private final Store<MimeMessage, MimeMessagePartsId> mimeMessageStore;
        private final MailReferenceSerializer mailReferenceSerializer;
        private final Function<MailReferenceDTO, MailWithEnqueueId> mailLoader;
        private final MailQueueView.Factory mailQueueViewFactory;
        private final Clock clock;
        private final MailQueueItemDecoratorFactory decoratorFactory;
        private final RabbitMQMailQueueConfiguration configuration;

        @Inject
        @VisibleForTesting PrivateFactory(MetricFactory metricFactory,
                                          GaugeRegistry gaugeRegistry,
                                          Sender sender, ReceiverProvider receiverProvider, MimeMessageStore.Factory mimeMessageStoreFactory,
                                          BlobId.Factory blobIdFactory,
                                          MailQueueView.Factory mailQueueViewFactory,
                                          Clock clock,
                                          MailQueueItemDecoratorFactory decoratorFactory,
                                          RabbitMQMailQueueConfiguration configuration) {
            this.metricFactory = metricFactory;
            this.gaugeRegistry = gaugeRegistry;
            this.sender = sender;
            this.receiverProvider = receiverProvider;
            this.mimeMessageStore = mimeMessageStoreFactory.mimeMessageStore();
            this.mailQueueViewFactory = mailQueueViewFactory;
            this.clock = clock;
            this.decoratorFactory = decoratorFactory;
            this.mailReferenceSerializer = new MailReferenceSerializer();
            this.mailLoader = Throwing.function(new MailLoader(mimeMessageStore, blobIdFactory)::load).sneakyThrow();
            this.configuration = configuration;
        }

        RabbitMQMailQueue create(MailQueueName mailQueueName) {
            MailQueueView mailQueueView = mailQueueViewFactory.create(mailQueueName);
            mailQueueView.initialize(mailQueueName);

            RabbitMQMailQueue rabbitMQMailQueue = new RabbitMQMailQueue(
                metricFactory,
                mailQueueName,
                new Enqueuer(mailQueueName, sender, mimeMessageStore, mailReferenceSerializer,
                    metricFactory, mailQueueView, clock),
                new Dequeuer(mailQueueName, receiverProvider, mailLoader, mailReferenceSerializer,
                    metricFactory, mailQueueView),
                mailQueueView,
                decoratorFactory);

            registerGaugeFor(rabbitMQMailQueue);
            return rabbitMQMailQueue;
        }

        private void registerGaugeFor(RabbitMQMailQueue rabbitMQMailQueue) {
            if (configuration.isSizeMetricsEnabled()) {
                this.gaugeRegistry.register(QUEUE_SIZE_METRIC_NAME_PREFIX + rabbitMQMailQueue.getName().asString(), rabbitMQMailQueue::getSize);
            }
        }
    }

    private final RabbitMQMailQueueManagement mqManagementApi;
    private final PrivateFactory privateFactory;
    private final Sender sender;

    @VisibleForTesting
    @Inject
    RabbitMQMailQueueFactory(Sender sender,
                             RabbitMQMailQueueManagement mqManagementApi,
                             PrivateFactory privateFactory) {
        this.sender = sender;
        this.mqManagementApi = mqManagementApi;
        this.privateFactory = privateFactory;
    }

    @Override
    public Optional<RabbitMQMailQueue> getQueue(org.apache.james.queue.api.MailQueueName name) {
        return getQueueFromRabbitServer(MailQueueName.fromString(name.asString()));
    }

    @Override
    public RabbitMQMailQueue createQueue(org.apache.james.queue.api.MailQueueName name) {
        MailQueueName mailQueueName = MailQueueName.fromString(name.asString());
        return getQueueFromRabbitServer(mailQueueName)
            .orElseGet(() -> createQueueIntoRabbitServer(mailQueueName));
    }

    @Override
    public Set<org.apache.james.queue.api.MailQueueName> listCreatedMailQueues() {
        return mqManagementApi.listCreatedMailQueueNames()
            .map(MailQueueName::asString)
            .map(org.apache.james.queue.api.MailQueueName::of)
            .collect(ImmutableSet.toImmutableSet());
    }

    private RabbitMQMailQueue createQueueIntoRabbitServer(MailQueueName mailQueueName) {
        String exchangeName = mailQueueName.toRabbitExchangeName().asString();
        Flux.concat(
            sender.declareExchange(ExchangeSpecification.exchange(exchangeName)
                .durable(true)
                .type("direct")),
            sender.declareQueue(QueueSpecification.queue(mailQueueName.toWorkQueueName().asString())
                .durable(DURABLE)
                .exclusive(!EXCLUSIVE)
                .autoDelete(!AUTO_DELETE)
                .arguments(NO_ARGUMENTS)),
            sender.bind(BindingSpecification.binding()
                .exchange(mailQueueName.toRabbitExchangeName().asString())
                .queue(mailQueueName.toWorkQueueName().asString())
                .routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();
        return privateFactory.create(mailQueueName);
    }

    private Optional<RabbitMQMailQueue> getQueueFromRabbitServer(MailQueueName name) {
        return mqManagementApi.listCreatedMailQueueNames()
            .filter(name::equals)
            .map(privateFactory::create)
            .findFirst();
    }
}
