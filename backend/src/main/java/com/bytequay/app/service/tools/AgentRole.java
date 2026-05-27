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
package com.bytequay.app.service.tools;

/**
 * Agent role the caller plays in the conversation. Used by the
 * registry to filter {@code tools/list}: a trunk agent shouldn't see
 * code-editing tools, a task agent shouldn't see create_task, etc.
 *
 * <p>{@link #ANY} on a tool's {@code roles} array means "available to
 * every role" — useful for the discovery tools and the approval gate.
 */
public enum AgentRole
{
    TRUNK,
    TASK,
    REVIEWER,
    /** Sentinel — declared by a tool to mean "any role may call this". */
    ANY,
}
