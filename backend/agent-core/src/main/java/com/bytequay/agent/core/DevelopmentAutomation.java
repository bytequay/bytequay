/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.agent.core;

import com.bytequay.agent.spi.AgentLaneId;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Accepted delivery settings. Only trusted private/publication turns can disable commits; CI inherits the task lane.
 *
 * @param reviewSummary whether the agent summarizes each new review comment on the task's pull request in a private thread. It is
 *         task-level: the watcher reads it from the task defaults, so the copies on turns and queued inputs have no effect. It is
 *         not a delivery step and grants no authority, so {@link #enabled()} ignores it.
 */
public record DevelopmentAutomation(
        boolean autoCommit,
        boolean autoDraftPr,
        boolean autoCiFix,
        Optional<AgentLaneId> ciLaneId,
        boolean reviewSummary)
{
    public static final DevelopmentAutomation DEFAULT = new DevelopmentAutomation(true, true, true, Optional.empty());
    public static final DevelopmentAutomation LOCAL_ONLY = new DevelopmentAutomation(false, false, false, Optional.empty());

    public DevelopmentAutomation
    {
        requireNonNull(ciLaneId, "ciLaneId is null");
    }

    public DevelopmentAutomation(boolean autoCommit, boolean autoDraftPr, boolean autoCiFix, Optional<AgentLaneId> ciLaneId)
    {
        this(autoCommit, autoDraftPr, autoCiFix, ciLaneId, false);
    }

    public DevelopmentAutomation forDevelopment()
    {
        return new DevelopmentAutomation(true, autoDraftPr, autoCiFix, ciLaneId, reviewSummary);
    }

    public boolean enabled()
    {
        return autoCommit || autoDraftPr || autoCiFix;
    }
}
