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
package org.apache.james.jmap.model

import eu.timepit.refined.types.string.NonEmptyString
import org.apache.james.jmap.model.Invocation.{Arguments, MethodCallId, MethodName}
import play.api.libs.json._

case class Invocation(methodName: MethodName, arguments: Arguments, methodCallId: MethodCallId)

object Invocation {
  val METHOD_NAME: Int = 0
  val ARGUMENTS: Int = 1
  val METHOD_CALL: Int = 2

  case class MethodName(value: NonEmptyString)
  case class Arguments(value: JsObject) extends AnyVal
  case class MethodCallId(value: NonEmptyString)

}
