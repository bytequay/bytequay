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
package com.bytequay.agent.spi;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;

public record AgentLaneId(String value)
{
    public static final AgentLaneId CODEX_CLI = new AgentLaneId("codex-cli");
    public static final AgentLaneId CLAUDE_CLI = new AgentLaneId("claude-cli");

    public AgentLaneId
    {
        checkNotBlank(value, "value");
        checkArgument(value.matches("[a-z0-9]+(?:-[a-z0-9]+)*"), "invalid agent lane ID: " + value);
    }

    @Override
    public String toString()
    {
        return value;
    }
}
