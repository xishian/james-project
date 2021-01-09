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
import java.util.Optional;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.james.jmap.draft.exceptions.JmapFieldNotSupportedException;
import org.apache.james.jmap.draft.json.FieldNamePropertyFilter;
import org.apache.james.jmap.draft.model.GetMessagesRequest;
import org.apache.james.jmap.draft.model.GetMessagesResponse;
import org.apache.james.jmap.draft.model.MessageProperties;
import org.apache.james.jmap.draft.model.MessageProperties.HeaderProperty;
import org.apache.james.jmap.draft.model.MethodCallId;
import org.apache.james.jmap.draft.model.message.view.MessageView;
import org.apache.james.jmap.draft.model.message.view.MessageViewFactory;
import org.apache.james.jmap.draft.model.message.view.MetaMessageViewFactory;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageIdManager;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.MDCBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ser.PropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public class GetMessagesMethod implements Method {

    public static final String HEADERS_FILTER = "headersFilter";
    private static final String ISSUER = "GetMessagesMethod";
    private static final Logger LOGGER = LoggerFactory.getLogger(GetMessagesMethod.class);
    private static final Method.Request.Name METHOD_NAME = Method.Request.name("getMessages");
    private static final Method.Response.Name RESPONSE_NAME = Method.Response.name("messages");
    private final MetaMessageViewFactory messageViewFactory;
    private final MessageIdManager messageIdManager;
    private final MetricFactory metricFactory;

    @Inject
    @VisibleForTesting GetMessagesMethod(
            MetaMessageViewFactory messageViewFactory,
            MessageIdManager messageIdManager,
            MetricFactory metricFactory) {
        this.messageViewFactory = messageViewFactory;
        this.messageIdManager = messageIdManager;
        this.metricFactory = metricFactory;
    }
    
    @Override
    public Method.Request.Name requestHandled() {
        return METHOD_NAME;
    }
    
    @Override
    public Class<? extends JmapRequest> requestType() {
        return GetMessagesRequest.class;
    }
    
    @Override
    public Stream<JmapResponse> processToStream(JmapRequest request, MethodCallId methodCallId, MailboxSession mailboxSession) {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(mailboxSession);
        Preconditions.checkArgument(request instanceof GetMessagesRequest);

        GetMessagesRequest getMessagesRequest = (GetMessagesRequest) request;
        MessageProperties outputProperties = getMessagesRequest.getProperties().toOutputProperties();


        return MDCBuilder.create()
            .addContext(MDCBuilder.ACTION, "GET_MESSAGES")
            .addContext("accountId", getMessagesRequest.getAccountId())
            .addContext("ids", getMessagesRequest.getIds())
            .addContext("properties", getMessagesRequest.getProperties())
            .wrapArround(
                () -> metricFactory.runPublishingTimerMetricLogP99(JMAP_PREFIX + METHOD_NAME.getName(),
                    () -> Stream.of(JmapResponse.builder().methodCallId(methodCallId)
                        .response(getMessagesResponse(mailboxSession, getMessagesRequest))
                        .responseName(RESPONSE_NAME)
                        .properties(outputProperties.getOptionalMessageProperties())
                        .filterProvider(buildOptionalHeadersFilteringFilterProvider(outputProperties))
                        .build())))
            .get();
    }

    private Optional<SimpleFilterProvider> buildOptionalHeadersFilteringFilterProvider(MessageProperties properties) {
        return properties.getOptionalHeadersProperties()
            .map(this::buildHeadersPropertyFilter)
            .map(propertyFilter -> new SimpleFilterProvider()
                .addFilter(HEADERS_FILTER, propertyFilter));
    }
    
    private PropertyFilter buildHeadersPropertyFilter(ImmutableSet<HeaderProperty> headerProperties) {
        return new FieldNamePropertyFilter((fieldName) -> headerProperties.contains(HeaderProperty.fromFieldName(fieldName)));
    }

    private GetMessagesResponse getMessagesResponse(MailboxSession mailboxSession, GetMessagesRequest getMessagesRequest) {
        getMessagesRequest.getAccountId().ifPresent((input) -> notImplemented("accountId"));

        try {
            MessageProperties.ReadProfile readProfile = getMessagesRequest.getProperties().computeReadLevel();
            MessageViewFactory<? extends MessageView> factory = messageViewFactory.getFactory(readProfile);
            List<? extends MessageView> messageViews = factory.fromMessageIds(getMessagesRequest.getIds(), mailboxSession);

            return GetMessagesResponse.builder()
                .messages(messageViews)
                .expectedMessageIds(getMessagesRequest.getIds())
                .build();
        } catch (MailboxException e) {
            throw new RuntimeException(e);
        }
    }

    private static void notImplemented(String field) {
        throw new JmapFieldNotSupportedException(ISSUER, field);
    }
}
