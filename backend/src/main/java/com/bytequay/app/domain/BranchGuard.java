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

/**
 * Per-task, always-on drift maintenance against a moving {@code main}
 * (plan-rail-runs.md R18). One row per task, created disabled and armed
 * on first push. {@code schedule} is an enum-ish label — only {@link
 * #SCHEDULE_NIGHTLY} exists for v1; {@code BranchGuardJob} maps it to a
 * fixed Java {@code Duration} rather than parsing a real cron expression.
 */
public record BranchGuard(
        String taskId,
        boolean enabled,
        String schedule,
        String state,
        String lastRunId,
        Instant lastCheckedAt)
{
    public static final String SCHEDULE_NIGHTLY = "nightly";

    public static final String STATE_IN_SYNC = "in_sync";
    public static final String STATE_DRIFTING = "drifting";
    public static final String STATE_FIXING = "fixing";
    public static final String STATE_NEEDS_ATTENTION = "needs_attention";

    public static BranchGuard disabled(String taskId)
    {
        return new BranchGuard(taskId, false, SCHEDULE_NIGHTLY, STATE_IN_SYNC, null, null);
    }

    public BranchGuard withEnabled(boolean enabled)
    {
        return new BranchGuard(taskId, enabled, schedule, state, lastRunId, lastCheckedAt);
    }

    public BranchGuard withSchedule(String schedule)
    {
        return new BranchGuard(taskId, enabled, schedule, state, lastRunId, lastCheckedAt);
    }

    public BranchGuard withState(String state)
    {
        return new BranchGuard(taskId, enabled, schedule, state, lastRunId, lastCheckedAt);
    }

    public BranchGuard withLastRun(String runId, Instant checkedAt)
    {
        return new BranchGuard(taskId, enabled, schedule, state, runId, checkedAt);
    }
}
