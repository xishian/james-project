/** **************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                 *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 * ***************************************************************/
package org.apache.james.eventsourcing

object Event {
  def belongsToSameAggregate(events: List[_ <: Event]): Boolean = events
    .view
    .map(event => event.getAggregateId)
    .distinct
    .take(2)
    .size == 1
}

trait Event extends Comparable[Event] {
  def eventId: EventId

  def getAggregateId: AggregateId

  override def compareTo(o: Event): Int = eventId.compareTo(o.eventId)
}