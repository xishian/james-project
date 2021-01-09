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

package org.apache.james.backends.rabbitmq;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.BiConsumer;

import javax.annotation.PreDestroy;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.ChannelPool;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

public class ReactorRabbitMQChannelPool implements ChannelPool, Startable {

    private static class ChannelClosedException extends IOException {
        ChannelClosedException(String message) {
            super(message);
        }
    }

    static class ChannelFactory extends BasePooledObjectFactory<Channel> {

        private static final Logger LOGGER = LoggerFactory.getLogger(ChannelFactory.class);

        private static final int MAX_RETRIES = 5;
        private static final Duration RETRY_FIRST_BACK_OFF = Duration.ofMillis(100);

        private final Mono<Connection> connectionMono;

        ChannelFactory(Mono<Connection> connectionMono) {
            this.connectionMono = connectionMono;
        }

        @Override
        public Channel create() {
            return connectionMono
                .flatMap(this::openChannel)
                .block();
        }

        private Mono<Channel> openChannel(Connection connection) {
            return Mono.fromCallable(connection::openChannel)
                .map(maybeChannel ->
                    maybeChannel.orElseThrow(() -> new RuntimeException("RabbitMQ reached to maximum opened channels, cannot get more channels")))
                .retryBackoff(MAX_RETRIES, RETRY_FIRST_BACK_OFF, FOREVER, Schedulers.elastic())
                .doOnError(throwable -> LOGGER.error("error when creating new channel", throwable));
        }

        @Override
        public PooledObject<Channel> wrap(Channel obj) {
            return new DefaultPooledObject<>(obj);
        }

        @Override
        public void destroyObject(PooledObject<Channel> pooledObject) throws Exception {
            Channel channel = pooledObject.getObject();
            if (channel.isOpen()) {
                channel.close();
            }
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactorRabbitMQChannelPool.class);
    private static final long MAXIMUM_BORROW_TIMEOUT_IN_MS = Duration.ofSeconds(5).toMillis();
    private static final int MAX_CHANNELS_NUMBER = 3;
    private static final int MAX_BORROW_RETRIES = 3;
    private static final Duration MIN_BORROW_DELAY = Duration.ofMillis(50);
    private static final Duration FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private final Mono<Connection> connectionMono;
    private final GenericObjectPool<Channel> pool;
    private final ConcurrentSkipListSet<Channel> borrowedChannels;
    private Sender sender;

    public ReactorRabbitMQChannelPool(SimpleConnectionPool simpleConnectionPool) {
        this(simpleConnectionPool.getResilientConnection(), MAX_CHANNELS_NUMBER);
    }

    public ReactorRabbitMQChannelPool(Mono<Connection> connectionMono, int poolSize) {
        this.connectionMono = connectionMono;
        ChannelFactory channelFactory = new ChannelFactory(connectionMono);

        GenericObjectPoolConfig<Channel> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolSize);
        this.pool = new GenericObjectPool<>(channelFactory, config);
        this.borrowedChannels = new ConcurrentSkipListSet<>(Comparator.comparingInt(System::identityHashCode));
    }

    public void start() {
        sender = createSender();
    }

    public Sender getSender() {
        return sender;
    }

    public Receiver createReceiver() {
        return RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connectionMono));
    }

    public Mono<Connection> getConnectionMono() {
        return connectionMono;
    }

    @Override
    public Mono<? extends Channel> getChannelMono() {
        return Mono.fromCallable(this::borrow);
    }

    private Channel borrow() {
        Channel channel = tryBorrowFromPool();
        borrowedChannels.add(channel);
        return channel;
    }

    private Channel tryBorrowFromPool() {
        return Mono.fromCallable(this::borrowFromPool)
            .doOnError(throwable -> LOGGER.warn("Cannot borrow channel", throwable))
            .retryBackoff(MAX_BORROW_RETRIES, MIN_BORROW_DELAY, FOREVER, Schedulers.elastic())
            .onErrorMap(this::propagateException)
            .subscribeOn(Schedulers.elastic())
            .block();
    }

    private Throwable propagateException(Throwable throwable) {
        if (throwable instanceof IllegalStateException
            && throwable.getMessage().contains("Retries exhausted")) {
            return throwable.getCause();
        }

        return throwable;
    }

    private Channel borrowFromPool() throws Exception {
        Channel channel = pool.borrowObject(MAXIMUM_BORROW_TIMEOUT_IN_MS);
        if (!channel.isOpen()) {
            invalidateObject(channel);
            throw new ChannelClosedException("borrowed channel is already closed");
        }
        return channel;
    }

    @Override
    public BiConsumer<SignalType, Channel> getChannelCloseHandler() {
        return (signalType, channel) -> {
            borrowedChannels.remove(channel);
            if (!channel.isOpen() || signalType != SignalType.ON_COMPLETE) {
                invalidateObject(channel);
                return;
            }
            pool.returnObject(channel);
        };
    }

    private Sender createSender() {
       return RabbitFlux.createSender(new SenderOptions()
           .connectionMono(connectionMono)
           .channelPool(this)
           .resourceManagementChannelMono(
               connectionMono.map(Throwing.function(Connection::createChannel)).cache()));
    }

    private void invalidateObject(Channel channel) {
        try {
            pool.invalidateObject(channel);
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        sender.close();
        borrowedChannels.forEach(channel -> getChannelCloseHandler().accept(SignalType.ON_NEXT, channel));
        borrowedChannels.clear();
        pool.close();
    }

    public boolean tryChannel() {
        Channel channel = null;
        try {
            channel = borrow();
            return channel.isOpen();
        } catch (Throwable t) {
            return false;
        } finally {
            if (channel != null) {
                borrowedChannels.remove(channel);
                pool.returnObject(channel);
            }
        }
    }
}
