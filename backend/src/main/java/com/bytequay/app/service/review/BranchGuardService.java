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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.BranchGuard;

/**
 * Owns the {@link BranchGuard} row lifecycle: lazy creation (disabled),
 * arming on first push, and the user-facing toggle/schedule update
 * (plan-rail-runs.md R18). {@code BranchGuardJob} owns the scheduled tick
 * itself and writes state/lastRun directly through the store.
 */
public interface BranchGuardService
{
    /** The task's guard, creating a disabled row if none exists yet. */
    BranchGuard get(String taskId);

    /** User-facing toggle/schedule update. */
    BranchGuard update(String taskId, Boolean enabled, String schedule);

    /** Arms the guard the first time a task reaches PUSHED_AWAITING_CI.
     *  No-op if a row already exists (whatever its enabled state — a user
     *  who turned it off shouldn't have it silently re-armed by a later
     *  push). */
    void enableOnFirstPush(String taskId);
}
