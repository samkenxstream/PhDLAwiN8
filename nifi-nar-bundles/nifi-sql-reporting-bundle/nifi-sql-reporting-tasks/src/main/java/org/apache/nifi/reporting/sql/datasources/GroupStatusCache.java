/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.reporting.sql.datasources;

import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.reporting.ReportingContext;

import java.time.Duration;

public class GroupStatusCache {
    private final long refreshMillis;

    private long nextRefreshTime;
    private ProcessGroupStatus cached;


    public GroupStatusCache(final Duration refreshFrequency) {
        this.refreshMillis = refreshFrequency.toMillis();
        nextRefreshTime = 0L;
    }

    public synchronized ProcessGroupStatus getGroupStatus(final ReportingContext context) {
        if (cached == null || System.currentTimeMillis() > nextRefreshTime) {
            final ProcessGroupStatus retrieved = context.getEventAccess().getControllerStatus();
            this.cached = retrieved;

            final long now = System.currentTimeMillis();
            this.nextRefreshTime = now + refreshMillis;
            return retrieved;
        }

        return cached;
    }
}
