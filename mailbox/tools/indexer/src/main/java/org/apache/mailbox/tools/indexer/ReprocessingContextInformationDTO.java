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
package org.apache.mailbox.tools.indexer;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.james.json.DTOModule;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.indexer.ReIndexingExecutionFailures;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.steveash.guavate.Guavate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;

public class ReprocessingContextInformationDTO implements AdditionalInformationDTO {

    public static class ReindexingFailureDTO {

        private final String mailboxId;
        private final List<Long> uids;

        private ReindexingFailureDTO(@JsonProperty("mailboxId") String mailboxId, @JsonProperty("uids") List<Long> uids) {
            this.mailboxId = mailboxId;
            this.uids = uids;
        }

        public String getMailboxId() {
            return mailboxId;
        }

        public List<Long> getUids() {
            return uids;
        }
    }

    public static class ReprocessingContextInformationForErrorRecoveryIndexationTask extends ReprocessingContextInformation {

        public static class DTO extends ReprocessingContextInformationDTO {

            DTO(@JsonProperty("type") String type,
                @JsonProperty("successfullyReprocessedMailCount") int successfullyReprocessedMailCount,
                @JsonProperty("failedReprocessedMailCount") int failedReprocessedMailCount,
                @JsonProperty("failures") List<ReindexingFailureDTO> failures,
                @JsonProperty("timestamp") Instant timestamp) {
                super(type, successfullyReprocessedMailCount, failedReprocessedMailCount, failures, timestamp);
            }
        }

        public static final AdditionalInformationDTOModule<ReprocessingContextInformationForErrorRecoveryIndexationTask, DTO> serializationModule(MailboxId.Factory mailboxIdFactory) {
            return DTOModule.forDomainObject(ReprocessingContextInformationForErrorRecoveryIndexationTask.class)
                .convertToDTO(DTO.class)
                .toDomainObjectConverter(dto -> new ReprocessingContextInformationForErrorRecoveryIndexationTask(
                    dto.successfullyReprocessedMailCount,
                    dto.failedReprocessedMailCount,
                    deserializeFailures(mailboxIdFactory, dto.failures),
                    dto.getTimestamp()))
                .toDTOConverter((details, type) -> new DTO(
                    type,
                    details.getSuccessfullyReprocessedMailCount(),
                    details.getFailedReprocessedMailCount(),
                    serializeFailures(details.failures()),
                    details.timestamp()))
                .typeName(ErrorRecoveryIndexationTask.PREVIOUS_FAILURES_INDEXING.asString())
                .withFactory(AdditionalInformationDTOModule::new);
        }

        ReprocessingContextInformationForErrorRecoveryIndexationTask(int successfullyReprocessedMailCount, int failedReprocessedMailCount, ReIndexingExecutionFailures failures, Instant timestamp) {
            super(successfullyReprocessedMailCount, failedReprocessedMailCount, failures, timestamp);
        }
    }

    public static class ReprocessingContextInformationForFullReindexingTask extends ReprocessingContextInformation {

        public static class DTO extends ReprocessingContextInformationDTO {

            DTO(@JsonProperty("type") String type,
                @JsonProperty("successfullyReprocessedMailCount") int successfullyReprocessedMailCount,
                @JsonProperty("failedReprocessedMailCount") int failedReprocessedMailCount,
                @JsonProperty("failures") List<ReindexingFailureDTO> failures,
                @JsonProperty("timestamp") Instant timestamp) {
                super(type, successfullyReprocessedMailCount, failedReprocessedMailCount, failures, timestamp);
            }
        }

        public static final AdditionalInformationDTOModule<ReprocessingContextInformationForFullReindexingTask, DTO> serializationModule(MailboxId.Factory mailboxIdFactory) {
            return DTOModule.forDomainObject(ReprocessingContextInformationForFullReindexingTask.class)
                .convertToDTO(DTO.class)
                .toDomainObjectConverter(dto -> new ReprocessingContextInformationForFullReindexingTask(dto.successfullyReprocessedMailCount, dto.failedReprocessedMailCount, deserializeFailures(mailboxIdFactory, dto.failures), dto.getTimestamp()))
                .toDTOConverter((details, type) -> new DTO(
                    type,
                    details.getSuccessfullyReprocessedMailCount(),
                    details.getFailedReprocessedMailCount(),
                    serializeFailures(details.failures()),
                    details.timestamp()))
                .typeName(FullReindexingTask.FULL_RE_INDEXING.asString())
                .withFactory(AdditionalInformationDTOModule::new);
        }

        ReprocessingContextInformationForFullReindexingTask(int successfullyReprocessedMailCount, int failedReprocessedMailCount, ReIndexingExecutionFailures failures, Instant timestamp) {
            super(successfullyReprocessedMailCount, failedReprocessedMailCount, failures, timestamp);
        }
    }

    static ReIndexingExecutionFailures deserializeFailures(MailboxId.Factory mailboxIdFactory,
                                                           List<ReindexingFailureDTO> failures) {
        List<ReIndexingExecutionFailures.ReIndexingFailure> reIndexingFailures = failures
            .stream()
            .flatMap(failuresForMailbox ->
                getReIndexingFailureStream(mailboxIdFactory, failuresForMailbox))
            .collect(Guavate.toImmutableList());

        return new ReIndexingExecutionFailures(reIndexingFailures);
    }

    private static Stream<ReIndexingExecutionFailures.ReIndexingFailure> getReIndexingFailureStream(MailboxId.Factory mailboxIdFactory, ReindexingFailureDTO failuresForMailbox) {
        return failuresForMailbox.uids
            .stream()
            .map(uid ->
                new ReIndexingExecutionFailures.ReIndexingFailure(mailboxIdFactory.fromString(failuresForMailbox.mailboxId), MessageUid.of(uid)));
    }


    static List<ReindexingFailureDTO> serializeFailures(ReIndexingExecutionFailures failures) {
        ImmutableListMultimap<MailboxId, ReIndexingExecutionFailures.ReIndexingFailure> failuresByMailbox = failures.failures()
            .stream()
            .collect(Guavate.toImmutableListMultimap(ReIndexingExecutionFailures.ReIndexingFailure::getMailboxId));

        return failuresByMailbox
            .asMap()
            .entrySet()
            .stream()
            .map(failureByMailbox ->
                new ReindexingFailureDTO(
                    failureByMailbox.getKey().serialize(),
                    extractMessageUidsFromFailure(failureByMailbox)))
            .collect(Guavate.toImmutableList());
    }

    private static ImmutableList<Long> extractMessageUidsFromFailure(Map.Entry<MailboxId, Collection<ReIndexingExecutionFailures.ReIndexingFailure>> failureByMailbox) {
        return failureByMailbox
            .getValue()
            .stream()
            .map(failure -> failure.getUid().asLong())
            .collect(Guavate.toImmutableList());
    }

    protected final String type;
    protected final int successfullyReprocessedMailCount;
    protected final int failedReprocessedMailCount;
    protected final List<ReindexingFailureDTO> failures;
    protected final Instant timestamp;


    ReprocessingContextInformationDTO(@JsonProperty("type") String type,
                                      @JsonProperty("successfullyReprocessedMailCount") int successfullyReprocessedMailCount,
                                      @JsonProperty("failedReprocessedMailCount") int failedReprocessedMailCount,
                                      @JsonProperty("failures") List<ReindexingFailureDTO> failures,
                                      @JsonProperty("timestamp") Instant timestamp) {
        this.type = type;
        this.successfullyReprocessedMailCount = successfullyReprocessedMailCount;
        this.failedReprocessedMailCount = failedReprocessedMailCount;
        this.failures = failures;
        this.timestamp = timestamp;
    }

    public int getSuccessfullyReprocessedMailCount() {
        return successfullyReprocessedMailCount;
    }

    public int getFailedReprocessedMailCount() {
        return failedReprocessedMailCount;
    }

    public List<ReindexingFailureDTO> getFailures() {
        return failures;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String getType() {
        return type;
    }
}
