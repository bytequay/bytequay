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

import java.time.Instant;

/** Per-task drift maintenance against the task's moving base branch. */
public record BranchGuard(
        String taskId,
        boolean enabled,
        String schedule,
        String state,
        Health health,
        String lastRunId,
        Instant lastCheckedAt)
{
    public static final String SCHEDULE_NIGHTLY = "nightly";

    public static final String STATE_HEALTHY = "healthy";
    public static final String STATE_DRIFTING = "drifting";
    public static final String STATE_CONFLICTED = "conflicted";
    public static final String STATE_FIXING = "fixing";
    public static final String STATE_NEEDS_ATTENTION = "needs_attention";

    /** Last observed branch health. CI is display-only; the CI loop owns fixes. */
    public record Health(Integer behindBy, Boolean mergeable, Boolean checksGreen)
    {
        public static final Health UNKNOWN = new Health(null, null, null);
    }

    public static BranchGuard disabled(String taskId)
    {
        return new BranchGuard(taskId, false, SCHEDULE_NIGHTLY, STATE_HEALTHY, Health.UNKNOWN, null, null);
    }

    public BranchGuard withEnabled(boolean enabled)
    {
        return new BranchGuard(taskId, enabled, schedule, state, health, lastRunId, lastCheckedAt);
    }

    public BranchGuard withSchedule(String schedule)
    {
        return new BranchGuard(taskId, enabled, schedule, state, health, lastRunId, lastCheckedAt);
    }

    public BranchGuard withState(String state)
    {
        return new BranchGuard(taskId, enabled, schedule, state, health, lastRunId, lastCheckedAt);
    }

    public BranchGuard withHealth(Health health)
    {
        return new BranchGuard(taskId, enabled, schedule, state, health, lastRunId, lastCheckedAt);
    }

    public BranchGuard withLastRun(String runId, Instant checkedAt)
    {
        return new BranchGuard(taskId, enabled, schedule, state, health, runId, checkedAt);
    }
}
