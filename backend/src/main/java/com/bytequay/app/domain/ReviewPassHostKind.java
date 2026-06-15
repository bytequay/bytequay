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
package com.bytequay.app.domain;

/**
 * What a {@link ReviewPass} is hosted by. {@link #THREAD} is the
 * standalone "Assign review" flow (carries the spawn-build affordance);
 * {@link #TASK_PHASE} is a pass the dev-task lifecycle runs at
 * INTERNAL_REVIEW / AGENT_RE_REVIEW (no spawn-build — the dev task IS
 * the build). {@code host_id} points at the review thread or the task
 * respectively.
 */
public enum ReviewPassHostKind
{
    THREAD,
    TASK_PHASE
}
