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

import java.time.Clock;
import java.time.Instant;

import org.apache.james.mailbox.indexer.IndexingDetailInformation;
import org.apache.james.mailbox.indexer.ReIndexingExecutionFailures;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.mailbox.tools.indexer.ReprocessingContextInformationDTO.ReprocessingContextInformationForErrorRecoveryIndexationTask;
import org.apache.mailbox.tools.indexer.ReprocessingContextInformationDTO.ReprocessingContextInformationForFullReindexingTask;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ReprocessingContextInformation implements TaskExecutionDetails.AdditionalInformation, IndexingDetailInformation {

    public static ReprocessingContextInformationForErrorRecoveryIndexationTask forErrorRecoveryIndexationTask(ReprocessingContext reprocessingContext) {
        return new ReprocessingContextInformationForErrorRecoveryIndexationTask(
            reprocessingContext.successfullyReprocessedMailCount(),
            reprocessingContext.failedReprocessingMailCount(),
            reprocessingContext.failures(),
            Clock.systemUTC().instant());
    }

    public static ReprocessingContextInformationForFullReindexingTask forFullReindexingTask(ReprocessingContext reprocessingContext) {
        return new ReprocessingContextInformationForFullReindexingTask(
            reprocessingContext.successfullyReprocessedMailCount(),
            reprocessingContext.failedReprocessingMailCount(),
            reprocessingContext.failures(),
            Clock.systemUTC().instant());
    }

    private final int successfullyReprocessedMailCount;
    private final int failedReprocessedMailCount;
    private final ReIndexingExecutionFailures failures;
    private final Instant timestamp;

    ReprocessingContextInformation(int successfullyReprocessedMailCount, int failedReprocessedMailCount,
                                   ReIndexingExecutionFailures failures, Instant timestamp) {
        this.successfullyReprocessedMailCount = successfullyReprocessedMailCount;
        this.failedReprocessedMailCount = failedReprocessedMailCount;
        this.failures = failures;
        this.timestamp = timestamp;
    }

    @Override
    public int getSuccessfullyReprocessedMailCount() {
        return successfullyReprocessedMailCount;
    }

    @Override
    public int getFailedReprocessedMailCount() {
        return failedReprocessedMailCount;
    }

    @Override
    @JsonIgnore
    public ReIndexingExecutionFailures failures() {
        return failures;
    }

    @JsonProperty("failures")
    public SerializableReIndexingExecutionFailures failuresAsJson() {
        return SerializableReIndexingExecutionFailures.from(failures());
    }

    @Override
    public Instant timestamp() {
        return timestamp;
    }
}
