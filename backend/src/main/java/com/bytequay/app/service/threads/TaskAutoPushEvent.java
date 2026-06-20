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
 * Fired when an <em>autonomous</em> (non-human) push lands a task in
 * {@code PUSHED_AWAITING_CI}. The per-stage auto-push budget on the active
 * ci-fixing stage decrements off this, independently of the task-level
 * consecutive-auto-push cap the phase machine enforces.
 */
public record TaskAutoPushEvent(String taskId)
{
}
