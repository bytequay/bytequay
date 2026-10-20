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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static com.bytequay.agent.core.AutomationLevel.CI_FIX;
import static com.bytequay.agent.core.AutomationLevel.COMMIT;
import static com.bytequay.agent.core.AutomationLevel.DRAFT_PR;
import static com.bytequay.agent.core.AutomationLevel.MERGE;
import static com.bytequay.agent.core.AutomationLevel.REVIEW_FIX;
import static java.util.Objects.requireNonNull;

/** Accepted delivery settings. Only trusted private/publication turns can disable commits; CI inherits the task lane. */
public record DevelopmentAutomation(boolean autoCommit, AutomationLevel level, Optional<AgentLaneId> ciLaneId)
{
    public static final DevelopmentAutomation DEFAULT = new DevelopmentAutomation(true, COMMIT, Optional.empty());
    public static final DevelopmentAutomation LOCAL_ONLY = new DevelopmentAutomation(false, COMMIT, Optional.empty());

    public DevelopmentAutomation
    {
        requireNonNull(level, "level is null");
        requireNonNull(ciLaneId, "ciLaneId is null");
    }

    /** Rows saved before the level existed hold one flag per step; the furthest step that was on becomes the level. */
    public static void upgradeSaved(ObjectNode saved)
    {
        if (saved.has("level")) {
            return;
        }
        AutomationLevel level = saved.path("autoCiFix").asBoolean() ? CI_FIX : saved.path("autoDraftPr").asBoolean() ? DRAFT_PR : COMMIT;
        saved.retain("autoCommit", "ciLaneId");
        saved.put("level", level.name());
    }

    public DevelopmentAutomation forDevelopment()
    {
        return new DevelopmentAutomation(true, level, ciLaneId);
    }

    public boolean autoDraftPr()
    {
        return level.includes(DRAFT_PR);
    }

    public boolean autoCiFix()
    {
        return level.includes(CI_FIX);
    }

    public boolean reviewFix()
    {
        return level.includes(REVIEW_FIX);
    }

    public boolean autoMerge()
    {
        return level.includes(MERGE);
    }

    public boolean enabled()
    {
        return autoCommit || autoDraftPr();
    }
}
