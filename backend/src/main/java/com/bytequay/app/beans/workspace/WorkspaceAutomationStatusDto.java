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
package com.bytequay.app.beans.workspace;

import java.time.Instant;

/** Durable per-workspace health for the two opt-in quality automations. */
public record WorkspaceAutomationStatusDto(
        QualityScan qualityScan,
        RemoteIssueIntake remoteIssueIntake)
{
    public record QualityScan(
            boolean enabled,
            boolean eligible,
            String reason,
            boolean running,
            Instant lastRunAt,
            Instant expectedNextRunAt,
            String lastOutcome,
            int findingsProposed,
            String lastError) {}

    public record RemoteIssueIntake(
            boolean enabled,
            boolean eligible,
            String reason,
            boolean running,
            Instant lastRunAt,
            Instant expectedNextRunAt,
            String lastOutcome,
            int issuesExamined,
            int tasksQueued,
            int implementationsStarted,
            String lastError) {}
}
