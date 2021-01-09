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

package org.apache.james.backends.cassandra;

import static com.datastax.driver.core.querybuilder.QueryBuilder.select;
import static org.apache.james.backends.cassandra.Scenario.Builder.awaitOn;
import static org.apache.james.backends.cassandra.Scenario.Builder.executeNormally;
import static org.apache.james.backends.cassandra.Scenario.Builder.fail;
import static org.apache.james.backends.cassandra.Scenario.combine;
import static org.apache.james.backends.cassandra.versions.table.CassandraSchemaVersionTable.TABLE_NAME;
import static org.apache.james.backends.cassandra.versions.table.CassandraSchemaVersionTable.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.james.backends.cassandra.Scenario.Barrier;
import org.apache.james.backends.cassandra.utils.CassandraAsyncExecutor;
import org.apache.james.backends.cassandra.versions.CassandraSchemaVersionDAO;
import org.apache.james.backends.cassandra.versions.CassandraSchemaVersionModule;
import org.apache.james.backends.cassandra.versions.SchemaVersion;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class TestingSessionTest {
    @RegisterExtension
    static CassandraClusterExtension cassandraCluster = new CassandraClusterExtension(CassandraSchemaVersionModule.MODULE);

    private CassandraSchemaVersionDAO dao;

    @BeforeEach
    void setUp(CassandraCluster cassandra) {
        dao = new CassandraSchemaVersionDAO(cassandra.getConf());
    }

    @Test
    void daoOperationShouldNotBeInstrumentedByDefault() {
        assertThatCode(() -> dao.getCurrentSchemaVersion().block())
            .doesNotThrowAnyException();
    }

    @Test
    void daoOperationShouldNotBeInstrumentedWhenExecuteNormally(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(executeNormally()
                .times(1)
                .whenQueryStartsWith("SELECT value FROM schemaVersion;"));

        assertThatCode(() -> dao.getCurrentSchemaVersion().block())
            .doesNotThrowAnyException();
    }

    @Test
    void daoOperationShouldNotBeInstrumentedWhenNotMatching(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .times(1)
                .whenQueryStartsWith("non matching"));

        assertThatCode(() -> dao.getCurrentSchemaVersion().block())
            .doesNotThrowAnyException();
    }

    @Test
    void daoOperationShouldFailWhenInstrumented(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .times(1)
                .whenQueryStartsWith("SELECT value FROM schemaVersion;"));

        assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void regularStatementsShouldBeInstrumented(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .times(1)
                .whenQueryStartsWith("SELECT value FROM schemaVersion;"));

        assertThatThrownBy(() -> new CassandraAsyncExecutor(cassandra.getConf())
                .execute(select(VALUE).from(TABLE_NAME))
                .block())
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void forAllQueriesShouldMatchAllStatements(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .times(1)
                .forAllQueries());

        assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void daoShouldNotBeInstrumentedWhenTimesIsExceeded(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .times(1)
                .whenQueryStartsWith("SELECT value FROM schemaVersion;"));

        try {
            dao.getCurrentSchemaVersion().block();
        } catch (Exception e) {
            // discard expected exception
        }

        assertThatCode(() -> dao.getCurrentSchemaVersion().block())
            .doesNotThrowAnyException();
    }

    @Test
    void timesShouldSpecifyExactlyTheFailureCount(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .times(2)
                .whenQueryStartsWith("SELECT value FROM schemaVersion;"));

        SoftAssertions.assertSoftly(softly -> {
            assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
                .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
                .isInstanceOf(RuntimeException.class);
            assertThatCode(() -> dao.getCurrentSchemaVersion().block())
                .doesNotThrowAnyException();
        });
    }

    @Test
    void scenarioShouldDefiningSeveralHooks(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(combine(
                executeNormally()
                    .times(1)
                    .whenQueryStartsWith("SELECT value FROM schemaVersion;"),
                fail()
                    .times(1)
                    .whenQueryStartsWith("SELECT value FROM schemaVersion;")));

        SoftAssertions.assertSoftly(softly -> {
            assertThatCode(() -> dao.getCurrentSchemaVersion().block())
                .doesNotThrowAnyException();
            assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
                .isInstanceOf(RuntimeException.class);
            assertThatCode(() -> dao.getCurrentSchemaVersion().block())
                .doesNotThrowAnyException();
        });
    }

    @Test
    void foreverShouldAlwaysApplyBehaviour(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .forever()
                .whenQueryStartsWith("SELECT value FROM schemaVersion;"));

        SoftAssertions.assertSoftly(softly -> {
            assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
                .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
                .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
                .isInstanceOf(RuntimeException.class);
        });
    }

    @Test
    void timesShouldBeTakenIntoAccountOnlyForMatchingStatements(CassandraCluster cassandra) {
        cassandra.getConf()
            .registerScenario(fail()
                .times(1)
                .whenQueryStartsWith("SELECT value FROM schemaVersion;"));

        dao.updateVersion(new SchemaVersion(36)).block();

        assertThatThrownBy(() -> dao.getCurrentSchemaVersion().block())
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void statementShouldNotBeAppliedBeforeBarrierIsReleased(CassandraCluster cassandra) throws Exception {
        SchemaVersion originalSchemaVersion = new SchemaVersion(32);
        dao.updateVersion(originalSchemaVersion).block();
        Barrier barrier = new Barrier();
        cassandra.getConf()
            .registerScenario(awaitOn(barrier)
                .times(1)
                .whenQueryStartsWith("INSERT INTO schemaVersion"));

        dao.updateVersion(new SchemaVersion(36)).subscribeOn(Schedulers.elastic()).subscribe();

        Thread.sleep(100);

        assertThat(dao.getCurrentSchemaVersion().block())
            .contains(originalSchemaVersion);
    }

    @Test
    void statementShouldBeAppliedWhenBarrierIsReleased(CassandraCluster cassandra) {
        SchemaVersion originalSchemaVersion = new SchemaVersion(32);
        SchemaVersion newVersion = new SchemaVersion(36);

        dao.updateVersion(originalSchemaVersion).block();
        Barrier barrier = new Barrier();
        cassandra.getConf()
            .registerScenario(awaitOn(barrier)
                .times(1)
                .whenQueryStartsWith("INSERT INTO schemaVersion"));

        Mono<Void> operation = dao.updateVersion(newVersion).cache();

        operation.subscribeOn(Schedulers.elastic()).subscribe();
        barrier.releaseCaller();
        operation.block();

        assertThat(dao.getCurrentSchemaVersion().block())
            .contains(newVersion);
    }

    @Test
    void testShouldBeAbleToAwaitCaller(CassandraCluster cassandra) throws Exception {
        SchemaVersion originalSchemaVersion = new SchemaVersion(32);
        SchemaVersion newVersion = new SchemaVersion(36);

        dao.updateVersion(originalSchemaVersion).block();
        Barrier barrier = new Barrier();
        cassandra.getConf()
            .registerScenario(awaitOn(barrier)
                .times(1)
                .whenQueryStartsWith("INSERT INTO schemaVersion"));

        Mono<Void> operation = dao.updateVersion(newVersion).cache();

        operation.subscribeOn(Schedulers.elastic()).subscribe();
        barrier.awaitCaller();
        barrier.releaseCaller();
        operation.block();

        assertThat(dao.getCurrentSchemaVersion().block())
            .contains(newVersion);
    }
}
