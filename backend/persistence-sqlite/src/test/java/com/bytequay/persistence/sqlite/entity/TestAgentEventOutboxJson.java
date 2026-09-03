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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentEventOutboxJson
{
    @Test
    void decodesRowsStoredBeforeWorkingDirectoryDurationAndActionsWereCarried()
    {
        AgentEventPayload.CommandActivity command = (AgentEventPayload.CommandActivity) inline(
                AgentEventPayload.Kind.COMMAND,
                "{\"state\":\"COMPLETED\",\"argv\":[\"rg TODO\"],\"output\":\"ok\",\"exitCode\":0,\"truncated\":false}");
        assertThat(command.cwd()).isEmpty();
        assertThat(command.durationMs()).isEmpty();
        assertThat(command.actions()).isEmpty();

        AgentEventPayload.ToolActivity tool = (AgentEventPayload.ToolActivity) inline(
                AgentEventPayload.Kind.TOOL,
                "{\"name\":\"docs/lookup\",\"state\":\"COMPLETED\",\"summary\":\"docs/lookup\"}");
        assertThat(tool.durationMs()).isEmpty();
    }

    private static AgentEventPayload inline(AgentEventPayload.Kind kind, String json)
    {
        AgentEventOutboxPayload payload = AgentEventOutboxJson.decodePayload(kind, false, json);
        assertThat(payload).isInstanceOf(AgentEventOutboxPayload.Inline.class);
        return ((AgentEventOutboxPayload.Inline) payload).payload();
    }
}
