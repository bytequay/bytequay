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
package com.bytequay.app.web;

import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.spi.AgentLaneId;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** The shared settings and task-submission representation; missing toggles are invalid. */
public record DevelopmentAutomationDto(Boolean autoDraftPr, Boolean autoCiFix, Optional<String> ciLaneId)
{
    public DevelopmentAutomationDto
    {
        requireNonNull(autoDraftPr, "autoDraftPr is null");
        requireNonNull(autoCiFix, "autoCiFix is null");
        requireNonNull(ciLaneId, "ciLaneId is null");
        ciLaneId.ifPresent(AgentLaneId::new);
    }

    public static DevelopmentAutomationDto from(DevelopmentAutomation automation)
    {
        return new DevelopmentAutomationDto(automation.autoDraftPr(), automation.autoCiFix(), automation.ciLaneId().map(AgentLaneId::value));
    }

    public DevelopmentAutomation toDomain()
    {
        return new DevelopmentAutomation(true, autoDraftPr, autoCiFix, ciLaneId.map(AgentLaneId::new));
    }
}
