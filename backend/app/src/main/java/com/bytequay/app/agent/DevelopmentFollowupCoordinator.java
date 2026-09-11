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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.spi.DevelopmentResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Advances live stopped turns; reading task history never calls this coordinator. */
public final class DevelopmentFollowupCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(DevelopmentFollowupCoordinator.class);

    private final AgentTurnService turns;
    private final WorktreeService worktrees;
    private final Executor executor;

    public DevelopmentFollowupCoordinator(AgentTurnService turns, WorktreeService worktrees, Executor executor)
    {
        this.turns = requireNonNull(turns, "turns is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    public void completed(DevelopmentTurnCompletion completion)
    {
        requireNonNull(completion, "completion is null");
        try {
            executor.execute(() -> advance(completion));
        }
        catch (RuntimeException failure) {
            fail(completion, "Could not start the development follow-up: " + failure.getMessage());
        }
    }

    private void advance(DevelopmentTurnCompletion completion)
    {
        if (!owns(completion)) {
            return;
        }
        try {
            checkState(completion.turn().state() == TurnState.COMPLETED, "Development turn did not complete successfully");
            checkState(completion.turn().cancellationRequestedAt().isEmpty(), "Development was stopped before delivery");
            checkState(completion.providerQuiescent(), "Provider shutdown could not be verified");
            checkState(completion.failure().isEmpty(), "Development turn cleanup failed: %s", completion.failure().orElse(""));
            DevelopmentResultReport report = completion.turn().developmentResult()
                    .orElseThrow(() -> new IllegalStateException("The root agent did not report a valid development result"));
            if (!report.readyForDelivery()) {
                turns.finishDevelopmentContinuation(completion.task().id(), completion.turn().id());
                return;
            }
            if (turns.handoffDevelopmentContinuationToQueue(completion.task().id(), completion.turn().id())) {
                return;
            }
            var worktree = completion.task().taskWorktree()
                    .orElseThrow(() -> new IllegalStateException("Development task has no managed worktree"));
            var snapshot = worktrees.inspect(worktree);
            checkState(snapshot.worktree().equals(worktree), "Development worktree ownership changed");
            checkState(!snapshot.gitOperationInProgress(), "A Git operation still needs attention");
            // Inspection is read-only. A concurrent Stop revokes authority even when that read succeeds.
            if (owns(completion)) {
                turns.finishDevelopmentContinuation(completion.task().id(), completion.turn().id());
            }
        }
        catch (RuntimeException failure) {
            fail(completion, failure.getMessage());
        }
    }

    private boolean owns(DevelopmentTurnCompletion completion)
    {
        return turns.ownsDevelopmentContinuation(completion.task().id(), completion.turn().id());
    }

    private void fail(DevelopmentTurnCompletion completion, String reason)
    {
        if (!owns(completion)) {
            return;
        }
        try {
            turns.failDevelopmentContinuation(completion.task().id(), completion.turn().id(),
                    "Automation failed · Delete task and start again. " + reason);
        }
        catch (RuntimeException failure) {
            // Core retains the unfinished marker and any unproven writer; a failed cleanup never resumes work.
            log.error("Could not settle development follow-up failure for task {}", completion.task().id(), failure);
        }
    }
}
