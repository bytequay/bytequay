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
package com.bytequay.core.rebase;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase.ActionJournal;
import com.bytequay.core.rebase.BranchRebase.ActionType;
import com.bytequay.core.rebase.BranchRebase.FinishReason;
import com.bytequay.core.rebase.BranchRebase.PauseReason;
import com.bytequay.core.rebase.BranchRebase.Status;
import com.bytequay.core.rebase.BranchRebase.Step;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBranchRebase
{
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final String ORIGINAL = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String STOPPED = "c".repeat(40);
    private static final String RESULT = "d".repeat(40);

    @Test
    void resetsAttemptsWhenTheSequencerStopsOnANewCommit()
    {
        BranchRebase first = rebase().resolving(STOPPED, NOW.plusSeconds(1))
                .recordTurnStarted(NOW.plusSeconds(2));

        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(first.resolving(STOPPED, NOW.plusSeconds(3)).attemptCount()).isEqualTo(1);
        assertThat(first.resolving("d".repeat(40), NOW.plusSeconds(3)).attemptCount()).isZero();
    }

    @Test
    void preservesAJournalAcrossPauseAndResume()
    {
        ActionJournal journal = new ActionJournal(ActionType.CONTINUE, Optional.of(STOPPED), ORIGINAL, 1, "continue-1", NOW);
        BranchRebase paused = rebase()
                .resolving(STOPPED, NOW.plusSeconds(1))
                .withJournal(journal, NOW.plusSeconds(2))
                .pause(PauseReason.UNSAFE_GIT_STATE, "inspect the worktree", NOW.plusSeconds(3));

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.journal()).contains(journal);
        assertThat(paused.resume(NOW.plusSeconds(4)).status()).isEqualTo(Status.RUNNING);
    }

    @Test
    void completedAndCancelledResultsHaveDifferentTipRules()
    {
        BranchRebase completed = rebase().finish(FinishReason.COMPLETED, "Rebased", Optional.of("d".repeat(40)), NOW.plusSeconds(1));
        BranchRebase cancelled = rebase().finish(FinishReason.CANCELLED, "Cancelled", Optional.empty(), NOW.plusSeconds(1));

        assertThat(completed.resultWorkTip()).contains("d".repeat(40));
        assertThat(cancelled.resultWorkTip()).isEmpty();
        assertThat(completed.currentStep()).isEmpty();
        assertThat(completed.terminalAt()).contains(NOW.plusSeconds(1));
    }

    @Test
    void rejectsACompletedResultWithoutATip()
    {
        assertThatThrownBy(() -> rebase().finish(FinishReason.COMPLETED, "missing", Optional.empty(), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesThreeAgentTurnsPerStoppedCommit()
    {
        BranchRebase current = rebase().resolving(STOPPED, NOW.plusSeconds(1));
        for (int attempt = 1; attempt <= BranchRebase.MAX_AGENT_TURNS; attempt++) {
            current = current.recordTurnStarted(NOW.plusSeconds(attempt + 1));
        }
        BranchRebase exhausted = current;

        assertThatThrownBy(() -> exhausted.recordTurnStarted(NOW.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void eachRunningTransitionChangesOnlyItsOwnFields()
    {
        ActionJournal journal = new ActionJournal(ActionType.START, Optional.empty(), ORIGINAL, 0, "start-1", NOW);
        BranchRebase running = rebase();

        BranchRebase journalled = running.withJournal(journal, NOW.plusSeconds(1));
        assertOnlyChanged(running, journalled, "journal", "updatedAt");
        assertThat(journalled.journal()).contains(journal);
        assertThat(journalled.updatedAt()).isEqualTo(NOW.plusSeconds(1));

        BranchRebase cleared = journalled.withoutJournal(NOW.plusSeconds(2));
        assertOnlyChanged(journalled, cleared, "journal", "updatedAt");
        assertThat(cleared.journal()).isEmpty();

        BranchRebase resolving = running.resolving(STOPPED, NOW.plusSeconds(1));
        assertOnlyChanged(running, resolving, "currentStep", "conflictCommitSha", "updatedAt");
        assertThat(resolving.currentStep()).contains(Step.RESOLVING);
        assertThat(resolving.conflictCommitSha()).contains(STOPPED);

        BranchRebase started = resolving.recordTurnStarted(NOW.plusSeconds(2));
        assertOnlyChanged(resolving, started, "attemptCount", "updatedAt");
        assertThat(started.attemptCount()).isEqualTo(1);

        BranchRebase turning = started.withTurn("turn-1", NOW.plusSeconds(3));
        assertOnlyChanged(started, turning, "currentTurnId", "updatedAt");
        assertThat(turning.currentTurnId()).contains("turn-1");

        BranchRebase idle = turning.withoutTurn(NOW.plusSeconds(4));
        assertOnlyChanged(turning, idle, "currentTurnId", "updatedAt");
        assertThat(idle.currentTurnId()).isEmpty();

        BranchRebase replaying = idle.replaying(NOW.plusSeconds(5));
        assertOnlyChanged(idle, replaying, "currentStep", "conflictCommitSha", "attemptCount", "updatedAt");
        assertThat(replaying.currentStep()).contains(Step.REPLAYING);
        assertThat(replaying.conflictCommitSha()).isEmpty();
        assertThat(replaying.attemptCount()).isZero();
    }

    @Test
    void pauseResumeAndAbortChangeOnlyTheirOwnFields()
    {
        ActionJournal journal = new ActionJournal(ActionType.CONTINUE, Optional.of(STOPPED), ORIGINAL, 1, "continue-1", NOW);
        BranchRebase running = rebase().resolving(STOPPED, NOW.plusSeconds(1)).recordTurnStarted(NOW.plusSeconds(2)).withJournal(journal, NOW.plusSeconds(3));

        BranchRebase paused = running.pause(PauseReason.AGENT_TURN_FAILED, "turn failed", NOW.plusSeconds(4));
        assertOnlyChanged(running, paused, "status", "pauseReason", "summary", "updatedAt");
        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.AGENT_TURN_FAILED);
        assertThat(paused.summary()).contains("turn failed");

        BranchRebase resumed = paused.resume(NOW.plusSeconds(5));
        assertOnlyChanged(running, resumed, "updatedAt");
        assertThat(resumed.updatedAt()).isEqualTo(NOW.plusSeconds(5));

        ActionJournal abort = new ActionJournal(ActionType.ABORT, Optional.empty(), ORIGINAL, 1, "abort-1", NOW);
        BranchRebase aborting = paused.requestAbort(abort, NOW.plusSeconds(6));
        assertOnlyChanged(paused, aborting, "status", "pauseReason", "summary", "journal", "updatedAt");
        assertThat(aborting.status()).isEqualTo(Status.RUNNING);
        assertThat(aborting.pauseReason()).isEmpty();
        assertThat(aborting.summary()).isEmpty();
        assertThat(aborting.journal()).contains(abort);
    }

    @Test
    void finishKeepsOnlyTheResultAndTheVersionChangesNothingElse()
    {
        ActionJournal journal = new ActionJournal(ActionType.CONTINUE, Optional.of(STOPPED), ORIGINAL, 1, "continue-1", NOW);
        BranchRebase running = rebase().resolving(STOPPED, NOW.plusSeconds(1)).recordTurnStarted(NOW.plusSeconds(2)).withJournal(journal, NOW.plusSeconds(3));

        BranchRebase finished = running.finish(FinishReason.COMPLETED, "Rebased", Optional.of(RESULT), NOW.plusSeconds(4));
        assertOnlyChanged(running, finished,
                "status", "currentStep", "finishReason", "summary", "conflictCommitSha", "attemptCount", "journal", "resultWorkTip", "updatedAt", "terminalAt");
        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(finished.currentStep()).isEmpty();
        assertThat(finished.finishReason()).contains(FinishReason.COMPLETED);
        assertThat(finished.summary()).contains("Rebased");
        assertThat(finished.conflictCommitSha()).isEmpty();
        assertThat(finished.attemptCount()).isZero();
        assertThat(finished.journal()).isEmpty();
        assertThat(finished.resultWorkTip()).contains(RESULT);
        assertThat(finished.terminalAt()).contains(NOW.plusSeconds(4));

        BranchRebase versioned = finished.withVersion(7);
        assertOnlyChanged(finished, versioned, "version");
        assertThat(versioned.version()).isEqualTo(7);
    }

    /** A transition must leave every field it does not name exactly as it was. */
    private static void assertOnlyChanged(BranchRebase before, BranchRebase after, String... changed)
    {
        assertThat(after).usingRecursiveComparison().ignoringFields(changed).isEqualTo(before);
    }

    private static BranchRebase rebase()
    {
        return BranchRebase.create(
                BranchRebaseId.random(),
                "request-1",
                "task-1",
                RepoRef.of("owner", "repository"),
                Path.of("/tmp/bytequay-rebase/worktree"),
                "feature",
                "master",
                ORIGINAL,
                BASE,
                NOW);
    }
}
