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

package org.apache.james.jmap.draft.methods;

import java.util.List;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.api.filtering.FilteringManagement;
import org.apache.james.jmap.api.filtering.Rule;
import org.apache.james.jmap.draft.model.GetFilterRequest;
import org.apache.james.jmap.draft.model.GetFilterResponse;
import org.apache.james.jmap.draft.model.MethodCallId;
import org.apache.james.jmap.draft.model.SetError;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.MDCBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class GetFilterMethod implements Method {
    private static final Logger LOGGER = LoggerFactory.getLogger(GetFilterMethod.class);

    private static final Method.Request.Name METHOD_NAME = Method.Request.name("getFilter");
    private static final Method.Response.Name RESPONSE_NAME = Method.Response.name("filter");

    private final MetricFactory metricFactory;
    private final FilteringManagement filteringManagement;

    @Inject
    private GetFilterMethod(MetricFactory metricFactory, FilteringManagement filteringManagement) {
        this.metricFactory = metricFactory;
        this.filteringManagement = filteringManagement;
    }

    @Override
    public Request.Name requestHandled() {
        return METHOD_NAME;
    }

    @Override
    public Class<? extends JmapRequest> requestType() {
        return GetFilterRequest.class;
    }

    @Override
    public Stream<JmapResponse> processToStream(JmapRequest request, MethodCallId methodCallId, MailboxSession mailboxSession) {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(methodCallId);
        Preconditions.checkNotNull(mailboxSession);
        Preconditions.checkArgument(request instanceof GetFilterRequest);

        GetFilterRequest filterRequest = (GetFilterRequest) request;


        return MDCBuilder.create()
            .addContext(MDCBuilder.ACTION, "GET_FILTER")
            .wrapArround(
                () -> metricFactory.runPublishingTimerMetricLogP99(JMAP_PREFIX + METHOD_NAME.getName(),
                    () -> process(methodCallId, mailboxSession, filterRequest)))
            .get();
    }

    private Stream<JmapResponse> process(MethodCallId methodCallId, MailboxSession mailboxSession, GetFilterRequest request) {
        try {
            return retrieveFilter(methodCallId, mailboxSession.getUser());
        } catch (Exception e) {
            LOGGER.warn("Failed to retrieve filter");

            return Stream.of(unKnownError(methodCallId));
        }
    }

    private Stream<JmapResponse> retrieveFilter(MethodCallId methodCallId, Username username) {
        List<Rule> rules = filteringManagement.listRulesForUser(username);

        GetFilterResponse getFilterResponse = GetFilterResponse.builder()
            .rules(rules)
            .build();

        return Stream.of(JmapResponse.builder()
            .methodCallId(methodCallId)
            .response(getFilterResponse)
            .responseName(RESPONSE_NAME)
            .build());
    }

    private JmapResponse unKnownError(MethodCallId methodCallId) {
        return JmapResponse.builder()
            .methodCallId(methodCallId)
            .responseName(RESPONSE_NAME)
            .response(ErrorResponse.builder()
                .type(SetError.Type.ERROR.asString())
                .description("Failed to retrieve filter")
                .build())
            .build();
    }
}
