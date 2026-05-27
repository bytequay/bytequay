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
 * How a tool call is admitted at dispatch time.
 *
 * <ul>
 *   <li>{@link #AUTO} — invoke immediately. Reads, recall, discovery.</li>
 *   <li>{@link #GATED} — surface an approval_prompt to the user; on
 *       Allow invoke, on Deny return a deny envelope. The "Allow next
 *       N" budget can drain the prompt and the park-guard refuses
 *       further GATED calls once a task is parked.</li>
 *   <li>{@link #PARKED} — never call the remote / write directly.
 *       Park the active task at AWAITING_REVIEW with the proposed
 *       payload and let the user's Approve click in
 *       {@code NotificationController} drive the real publish through
 *       {@code PublishService}.</li>
 * </ul>
 */
public enum Gating
{
    AUTO,
    GATED,
    PARKED,
}
