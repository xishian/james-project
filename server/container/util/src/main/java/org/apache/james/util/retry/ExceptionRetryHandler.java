/*
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */

package org.apache.james.util.retry;

import org.apache.james.util.retry.api.ExceptionRetryingProxy;
import org.apache.james.util.retry.api.RetryHandler;
import org.apache.james.util.retry.api.RetrySchedule;

/**
 * Abstract class <code>ExceptionRetryHandler</code> retries the behaviour defined in abstract method 
 * <code>operation()</code> implemented in a concrete subclass when nominated subclasses of <code>Exception</code>
 * are thrown. The intervals between retries are defined by a <code>RetrySchedule</code>.
 * <p>
 * Concrete subclasses are proxies that forward work via the <code>operation()</code> to a delegate
 * instance for which retry behaviour is required. Both the proxy and the delegate implement the
 * same interfaces. 
 * <p>
 * The method stubs required to perform the proxy call via <code>operation()</code> may be generated by many means,
 * including explicit code, a <code>DynamicProxy</code> and compile time aspect injection.
 *
 * @see org.apache.james.util.retry.naming.RetryingContext
 */
public abstract class ExceptionRetryHandler implements RetryHandler {

    private Class<?>[] exceptionClasses = null;
    
    private ExceptionRetryingProxy proxy = null;
    private RetrySchedule schedule;
    private int maxRetries = 0;

        /**
         * Creates a new instance of ExceptionRetryHandler.
         *
         */
        private ExceptionRetryHandler() {
            super();
        }


        /**
         * Creates a new instance of ExceptionRetryHandler.
         *
         * @param exceptionClasses
         * @param proxy
         * @param maxRetries
         */
        public ExceptionRetryHandler(Class<?>[] exceptionClasses, ExceptionRetryingProxy proxy, RetrySchedule schedule, int maxRetries) {
            this();
            this.exceptionClasses = exceptionClasses;
            this.proxy = proxy;
            this.schedule = schedule;
            this.maxRetries = maxRetries;
        }

        @Override
        public Object perform() throws Exception {
            boolean success = false;
            Object result = null;
            int retryCount = 0;
            while (!success) {
                try {
                    if (retryCount > 0) {
                        proxy.resetDelegate();
                    }
                    result = operation();
                    success = true;

                } catch (Exception ex) {
                    if (retryCount >= maxRetries || !isRetryable(ex)) {
                        throw ex;
                    }
                    postFailure(ex, retryCount);
                    try {
                        Thread.sleep(getRetryInterval(retryCount));
                    } catch (InterruptedException ex1) {
                        // no-op
                    }
                    retryCount = maxRetries < 0 ? maxRetries
                            : retryCount + 1;
                }
            }
            return result;
        }
        
        /**
         * @param ex
         * The <code>Throwable</code> to test
         * 
         * @return true if the array of exception classes contains <strong>ex</strong>
         */
        private boolean isRetryable(Throwable ex) {
            boolean isRetryable = false;
            for (int i = 0; !isRetryable && i < exceptionClasses.length; i++) {
                isRetryable = exceptionClasses[i].isInstance(ex);
            }
            return isRetryable;
        }

        @Override
        public void postFailure(Exception ex, int retryCount) {
            // no-op
        }        

        @Override
        public abstract Object operation() throws Exception;
        
        /**
         * @return the retryInterval
         */
        public long getRetryInterval(int retryCount) {
            return schedule.getInterval(retryCount);
        }
}