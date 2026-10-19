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
package com.bytequay.app.assistant;

/**
 * What the assistant may do from the user's words; each is off until the user turns it on.
 *
 * @param startTasks whether it may start a task the user asks for
 * @param answerTasks whether it may answer a sandboxed command or a question, and send the user's reply to a task
 * @param changeWork whether it may mark an Epic, add work to one and create a work item, all of which stay local
 * @param markAttention whether it may acknowledge or snooze a Needs attention item, which stays local too
 */
public record AssistantPermissions(boolean startTasks, boolean answerTasks, boolean changeWork, boolean markAttention)
{
    public static final AssistantPermissions DEFAULT = new AssistantPermissions(false, false, false, false);
}
