/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mock.smtp.server;

import static org.apache.james.mock.smtp.server.Fixture.BEHAVIOR_LIST;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.mock.smtp.server.Fixture.MailsFixutre;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigurationClientTest {
    private ConfigurationClient testee;
    private HTTPConfigurationServer.RunningStage server;
    private SMTPBehaviorRepository behaviorRepository;
    private ReceivedMailRepository mailRepository;

    @BeforeEach
    void setUp() {
        behaviorRepository = new SMTPBehaviorRepository();
        mailRepository = new ReceivedMailRepository();
        server = HTTPConfigurationServer.onRandomPort(behaviorRepository, mailRepository)
            .start();

        testee = ConfigurationClient.fromServer(server);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void listBehaviorsShouldReturnEmptyWhenNoSet() {
        assertThat(testee.listBehaviors())
            .isEmpty();
    }

    @Test
    void listBehaviorsShouldReturnDefinedBehaviors() {
        behaviorRepository.setBehaviors(Fixture.BEHAVIORS);

        assertThat(testee.listBehaviors())
            .isEqualTo(Fixture.BEHAVIORS.getBehaviorList());
    }

    @Test
    void setBehaviorsShouldStoreBehaviors() {
        testee.setBehaviors(BEHAVIOR_LIST);

        assertThat(testee.listBehaviors())
            .isEqualTo(BEHAVIOR_LIST);
    }

    @Test
    void clearBehaviorsShouldRemoveAllBehaviors() {
        testee.setBehaviors(BEHAVIOR_LIST);

        testee.clearBehaviors();

        assertThat(testee.listBehaviors())
            .isEmpty();
    }

    @Test
    void listMailsShouldReturnEmptyWhenNoStore() {
        assertThat(testee.listMails())
            .isEmpty();
    }

    @Test
    void listMailsShouldReturnStoredMails() {
        mailRepository.store(MailsFixutre.MAIL_1);
        mailRepository.store(MailsFixutre.MAIL_2);

        assertThat(testee.listMails())
            .containsExactly(MailsFixutre.MAIL_1, MailsFixutre.MAIL_2);
    }

    @Test
    void clearMailsRemoveAllStoredMails() {
        mailRepository.store(MailsFixutre.MAIL_1);
        mailRepository.store(MailsFixutre.MAIL_2);

        testee.clearMails();

        assertThat(testee.listMails())
            .isEmpty();
    }

}
