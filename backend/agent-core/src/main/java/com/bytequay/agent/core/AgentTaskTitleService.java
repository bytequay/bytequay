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

/** Generates task and branch names together before the task worktree exists. */
public interface AgentTaskTitleService
{
    /** Uses the full request and available issue context in a disposable text-only session; failures return empty. */
    Optional<TaskNames> generate(AgentLaneId laneId, AgentTaskDefaults defaults, String context);
}
