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

/** Accepted delivery settings. Only trusted private/publication turns can disable commits; CI inherits the task lane. */
public record DevelopmentAutomation(
        boolean autoCommit,
        boolean autoDraftPr,
        boolean autoCiFix,
        Optional<AgentLaneId> ciLaneId)
{
    public static final DevelopmentAutomation DEFAULT = new DevelopmentAutomation(true, true, true, Optional.empty());
    public static final DevelopmentAutomation LOCAL_ONLY = new DevelopmentAutomation(false, false, false, Optional.empty());

    public DevelopmentAutomation
    {
        requireNonNull(ciLaneId, "ciLaneId is null");
    }

    public DevelopmentAutomation forDevelopment()
    {
        return new DevelopmentAutomation(true, autoDraftPr, autoCiFix, ciLaneId);
    }

    public boolean enabled()
    {
        return autoCommit || autoDraftPr || autoCiFix;
    }
}
