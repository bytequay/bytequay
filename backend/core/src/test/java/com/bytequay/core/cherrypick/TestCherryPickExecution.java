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
package com.bytequay.core.cherrypick;

import com.bytequay.core.cherrypick.CherryPickExecution.ActionJournal;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionType;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecution.Step;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickItem.Result;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCherryPickExecution
{
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final String TARGET = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String TIP = "c".repeat(40);

    @Test
    void derivesTheCursorFromItemResultsRatherThanStoringOne()
    {
        CherryPickExecution execution = execution(
                planned(1, Disposition.ALREADY_ON_TARGET),
                planned(2, Disposition.TO_CHERRY_PICK),
                planned(3, Disposition.USER_SKIPPED),
                planned(4, Disposition.TO_CHERRY_PICK));

        assertThat(execution.cursor().orElseThrow().position()).isEqualTo(2);
        assertThat(execution.pendingItems()).hasSize(2);

        CherryPickExecution applied = execution.recordApplied(sha(2), created(2), NOW.plusSeconds(1));
        assertThat(applied.cursor().orElseThrow().position()).isEqualTo(4);

        CherryPickExecution done = applied.recordEmptySkipped(sha(4), NOW.plusSeconds(2));
        assertThat(done.cursor()).isEmpty();
        assertThat(done.pendingItems()).isEmpty();
    }

    @Test
    void recordsOneAppliedSourceCommit()
    {
        CherryPickExecution execution = execution(
                planned(1, Disposition.TO_CHERRY_PICK),
                planned(2, Disposition.TO_CHERRY_PICK),
                planned(3, Disposition.TO_CHERRY_PICK));

        CherryPickExecution applied = execution.recordApplied(sha(1), created(1), NOW.plusSeconds(1));

        assertThat(applied.items().getFirst().result()).isEqualTo(Result.APPLIED);
        assertThat(applied.items().getFirst().targetSha()).contains(created(1));
        assertThat(applied.items().subList(1, 3)).allMatch(item -> item.result() == Result.PENDING);
        assertThat(applied.currentTargetSha()).isEqualTo(created(1));
    }

    @Test
    void rejectsAppliedCommitsThatAreNotInThePlan()
    {
        CherryPickExecution execution = execution(planned(1, Disposition.TO_CHERRY_PICK));

        assertThatThrownBy(() -> execution.recordApplied("d".repeat(40), created(1), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the plan");
    }

    @Test
    void keepsConfirmedDispositionsOutOfGitDecisions()
    {
        CherryPickExecution execution = execution(
                planned(1, Disposition.ALREADY_ON_TARGET),
                planned(2, Disposition.USER_SKIPPED));

        assertThat(execution.items().get(0).result()).isEqualTo(Result.ALREADY_ON_TARGET);
        assertThat(execution.items().get(1).result()).isEqualTo(Result.USER_SKIPPED);
        assertThat(execution.pendingItems()).isEmpty();

        assertThatThrownBy(() -> execution.recordEmptySkipped(sha(1), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresATargetShaExactlyForAppliedItems()
    {
        CherryPickItem item = planned(1, Disposition.TO_CHERRY_PICK);

        assertThatThrownBy(() -> new CherryPickItem(
                1, sha(1), parent(1), "title", Optional.empty(),
                Disposition.TO_CHERRY_PICK, Result.APPLIED, Optional.empty(),
                ImmutableList.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only an applied item carries a target SHA");

        assertThatThrownBy(() -> new CherryPickItem(
                1, sha(1), parent(1), "title", Optional.empty(),
                Disposition.TO_CHERRY_PICK, Result.EMPTY_SKIPPED, Optional.of(created(1)),
                ImmutableList.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only an applied item carries a target SHA");

        assertThat(item.applied(created(1)).targetSha()).contains(created(1));
        assertThat(item.emptySkipped().targetSha()).isEmpty();
    }

    @Test
    void requiresOneBasedIncreasingPositionsAndUniqueSourceCommits()
    {
        assertThatThrownBy(() -> execution(planned(2, Disposition.TO_CHERRY_PICK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one-based");

        assertThatThrownBy(() -> execution(
                planned(1, Disposition.TO_CHERRY_PICK),
                planned(1, Disposition.TO_CHERRY_PICK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not increasing");

        CherryPickItem duplicate = new CherryPickItem(
                2, sha(1), parent(2), "duplicate", Optional.empty(),
                Disposition.TO_CHERRY_PICK, Result.PENDING, Optional.empty(),
                ImmutableList.of(), 0);
        assertThatThrownBy(() -> execution(planned(1, Disposition.TO_CHERRY_PICK), duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate source commit");
    }

    @Test
    void allowsAStepOnlyWhileRunning()
    {
        CherryPickExecution running = execution(planned(1, Disposition.TO_CHERRY_PICK))
                .withStep(Step.APPLYING, NOW.plusSeconds(1));
        assertThat(running.currentStep()).contains(Step.APPLYING);

        CherryPickExecution paused = running.pause(PauseReason.UNSAFE_GIT_STATE, "unexpected commit", NOW.plusSeconds(2));
        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.currentStep()).isEmpty();
        assertThat(paused.pauseReason()).contains(PauseReason.UNSAFE_GIT_STATE);

        assertThatThrownBy(() -> paused.withStep(Step.RESOLVING, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    void movesThroughTheWholeLifecycle()
    {
        CherryPickExecution execution = execution(planned(1, Disposition.TO_CHERRY_PICK))
                .withAgentTask("task-1", NOW.plusSeconds(1))
                .withJournal(journal(), NOW.plusSeconds(2))
                .withStep(Step.APPLYING, NOW.plusSeconds(3))
                .recordConflict(sha(1), ImmutableList.of(), NOW.plusSeconds(4))
                .withStep(Step.RESOLVING, NOW.plusSeconds(5))
                .recordAgentTurnStarted(sha(1), NOW.plusSeconds(6))
                .withTurn("turn-1", NOW.plusSeconds(7))
                .withoutTurn(NOW.plusSeconds(8))
                .recordApplied(sha(1), created(1), NOW.plusSeconds(10))
                .withoutJournal(NOW.plusSeconds(11))
                .finish(FinishReason.COMPLETED, "1 of 1 applied", NOW.plusSeconds(12));

        assertThat(execution.status()).isEqualTo(Status.FINISHED);
        assertThat(execution.finishReason()).contains(FinishReason.COMPLETED);
        assertThat(execution.terminalAt()).contains(NOW.plusSeconds(12));
        assertThat(execution.items().getFirst().agentTurnCount()).isEqualTo(1);
        assertThat(execution.journal()).isEmpty();

        assertThatThrownBy(() -> execution.withStep(Step.APPLYING, NOW.plusSeconds(13)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finished");
    }

    @Test
    void refusesToCompleteWhileItemsRemainPending()
    {
        CherryPickExecution execution = execution(
                planned(1, Disposition.TO_CHERRY_PICK),
                planned(2, Disposition.TO_CHERRY_PICK))
                .recordApplied(sha(1), created(1), NOW.plusSeconds(1));

        assertThatThrownBy(() -> execution.finish(FinishReason.COMPLETED, "done", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still has pending items");

        assertThat(execution.finish(FinishReason.CANCELLED, "cancelled by user", NOW.plusSeconds(2)).status())
                .isEqualTo(Status.FINISHED);
    }

    @Test
    void resumesFromPauseAndBoundsAgentTurns()
    {
        CherryPickExecution paused = execution(planned(1, Disposition.TO_CHERRY_PICK))
                .pause(PauseReason.RETRY_LIMIT_REACHED, "three turns used", NOW.plusSeconds(1));
        CherryPickExecution resumed = paused.resume(NOW.plusSeconds(2));

        assertThat(resumed.status()).isEqualTo(Status.RUNNING);
        assertThat(resumed.pauseReason()).isEmpty();

        CherryPickExecution exhausted = resumed
                .recordAgentTurnStarted(sha(1), NOW.plusSeconds(3))
                .recordAgentTurnStarted(sha(1), NOW.plusSeconds(4))
                .recordAgentTurnStarted(sha(1), NOW.plusSeconds(5));
        assertThatThrownBy(() -> exhausted.recordAgentTurnStarted(sha(1), NOW.plusSeconds(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exhausted its agent turns");
    }

    @Test
    void rejectsBackwardsUpdateTimes()
    {
        CherryPickExecution execution = execution(planned(1, Disposition.TO_CHERRY_PICK))
                .withStep(Step.APPLYING, NOW.plusSeconds(5));

        assertThatThrownBy(() -> execution.withStep(Step.RESOLVING, NOW.plusSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before the previous update");
    }

    @Test
    void rejectsAWorkBranchThatIsTheLocalTargetBranch()
    {
        assertThatThrownBy(() -> new Workspace(
                RepoRef.of("trinodb", "trino"), "origin", "fork", "master",
                "fork-master", "fork-master", Path.of("/tmp/wt"), "claude"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("work branch equals the local target branch");
    }

    private static CherryPickExecution execution(CherryPickItem... items)
    {
        return CherryPickExecution.create(
                CherryPickExecutionId.random(),
                "idempotency-key",
                workspace(),
                new Range(BASE, TIP, TARGET),
                List.of(items),
                NOW);
    }

    private static Workspace workspace()
    {
        return new Workspace(
                RepoRef.of("trinodb", "trino"),
                "origin",
                "fork",
                "master",
                "fork-master",
                "bump-477-478-a7",
                Path.of("/tmp/bytequay-cherry-pick"),
                "claude");
    }

    private static ActionJournal journal()
    {
        return new ActionJournal(
                ActionType.BEGIN_SEQUENCE,
                Optional.of(sha(1)),
                TARGET,
                1,
                "action-key",
                NOW);
    }

    private static CherryPickItem planned(int position, Disposition disposition)
    {
        return CherryPickItem.planned(
                position,
                sha(position),
                parent(position),
                "Commit " + position,
                Optional.of(new CherryPickItem.PullRequestRefView(
                        1000 + position,
                        "Pull request " + position,
                        "https://github.com/trinodb/trino/pull/" + (1000 + position))),
                disposition);
    }

    private static String sha(int position)
    {
        return String.format("%040x", position);
    }

    private static String parent(int position)
    {
        return String.format("%040x", 1_000_000 + position);
    }

    private static String created(int position)
    {
        return String.format("%040x", 2_000_000 + position);
    }
}
