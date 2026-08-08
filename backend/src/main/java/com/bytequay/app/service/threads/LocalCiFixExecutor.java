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

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Fail-closed compatibility surface for retired local-CI fixing. */
@Component
public class LocalCiFixExecutor
{
    static final int MAX_ATTEMPTS = 5;

    public LocalCiFixExecutor(
            ThreadStore threadStore,
            StageStore stageStore,
            AgentRunServiceImpl agentRuns,
            ThreadTurnScheduler scheduler,
            WorktreeLeaseService leaseService,
            TaskStore taskStore)
    {
        requireNonNull(threadStore, "threadStore is null");
        requireNonNull(stageStore, "stageStore is null");
        requireNonNull(agentRuns, "agentRuns is null");
        requireNonNull(scheduler, "scheduler is null");
        requireNonNull(leaseService, "leaseService is null");
        requireNonNull(taskStore, "taskStore is null");
    }

    public boolean tryFix(Task task, List<ValidationFailure> failures)
    {
        throw retired();
    }

    public boolean tryFixInCommand(Task task, List<ValidationFailure> failures)
    {
        throw retired();
    }

    public void closeIfGreen(String taskId)
    {
        throw retired();
    }

    public void closeIfGreenInCommand(String taskId)
    {
        throw retired();
    }

    private static UnsupportedOperationException retired()
    {
        return new UnsupportedOperationException(
                "LEGACY local CI fixing is retired; use the typed V2 validation owner");
    }
}
