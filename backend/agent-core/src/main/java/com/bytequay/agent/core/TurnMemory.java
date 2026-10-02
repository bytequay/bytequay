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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;

/**
 * A repository memory delivered with a turn, as the agent received it: its id and its line, so the turn's receipt shows what the agent
 * read even after the memory changes.
 */
public record TurnMemory(String id, String line)
{
    public TurnMemory
    {
        checkNotBlank(id, "id");
        checkNotBlank(line, "line");
    }
}
