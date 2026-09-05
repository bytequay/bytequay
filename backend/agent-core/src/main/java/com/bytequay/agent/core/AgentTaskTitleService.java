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

/** Generates an interactive task's short UI title outside its real provider conversation. */
public interface AgentTaskTitleService
{
    /**
     * Schedules a best-effort title request based only on the task's first message.
     *
     * <p>The task already has a provisional title, so scheduling or provider failure is silent.
     * A result may replace only that provisional title; a user rename always wins.
     *
     * @param task newly created interactive task
     * @param ownerTurnId durable initial turn used to attribute the disposable provider process
     * @param firstMessage message that created it
     */
    void generate(AgentTask task, TurnId ownerTurnId, String firstMessage);
}
