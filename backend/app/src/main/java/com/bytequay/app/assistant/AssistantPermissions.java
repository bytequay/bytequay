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
 * What the assistant may do from the user's words; both are off until the user turns them on.
 *
 * @param startTasks whether it may start a task the user asks for
 * @param answerTasks whether it may answer a sandboxed command or a question, and send the user's reply to a task
 */
public record AssistantPermissions(boolean startTasks, boolean answerTasks)
{
    public static final AssistantPermissions DEFAULT = new AssistantPermissions(false, false);
}
