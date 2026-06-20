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
package com.bytequay.app.service.threads;

/**
 * Fired when a task-scoped agent turn reaches a terminal state
 * (finished or failed). Carries the focused {@code taskId} so listeners
 * can clean up per-task, per-turn state — notably releasing the task-level
 * write mutex so a held lock can never outlive the turn that took it.
 *
 * @param taskId the turn's focused task (non-null; trunk turns don't fire this)
 */
public record TaskTurnFinishedEvent(String taskId, String turnId, boolean failed)
{
}
