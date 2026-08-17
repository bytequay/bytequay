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
package com.bytequay.core.ci;

import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.LogReference;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.CheckResult;
import com.bytequay.core.ci.CiAutofixRun.FinalFixupCheck;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.RerunJournal;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCiAutofixRun
{
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);

    @Test
    void supportsTheThreeStatusLifecycle()
    {
        CiAutofixRun run = newRun("start-1", "feature", NOW);
        CiAutofixAttempt attempt = newAttempt(run, 1, NOW.plusSeconds(1));

        run = run.addAttempt(attempt, NOW.plusSeconds(1))
                .linkAttemptTurn(attempt.id(), "turn-1", NOW.plusSeconds(2))
                .clearCurrentTurn("turn-1", NOW.plusSeconds(3))
                .pause(PauseReason.TURN_FAILED, "turn failed", NOW.plusSeconds(4));

        assertThat(run.status()).isEqualTo(Status.PAUSED);
        assertThat(run.attemptsUsed()).isEqualTo(1);

        run = run.resume(NOW.plusSeconds(5))
                .withFinalFixupCheck(
                        new FinalFixupCheck(HEAD, CheckResult.VALID, "", NOW.plusSeconds(6)),
                        NOW.plusSeconds(6))
                .finish(FinishReason.GREEN, "CI passed", NOW.plusSeconds(7));

        assertThat(run.status()).isEqualTo(Status.FINISHED);
        assertThat(run.finishReason()).contains(FinishReason.GREEN);
        assertThat(run.terminalAt()).contains(NOW.plusSeconds(7));
        CiAutofixRun finished = run;
        assertThatThrownBy(() -> finished.resume(NOW.plusSeconds(8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finished");
    }

    @Test
    void enforcesAttemptOrderBudgetAndOneCurrentTurn()
    {
        CiAutofixRun run = newRun("start-2", "feature-2", NOW);
        CiAutofixAttempt first = newAttempt(run, 1, NOW.plusSeconds(1));
        CiAutofixAttempt second = newAttempt(run, 2, NOW.plusSeconds(2));

        assertThatThrownBy(() -> run.addAttempt(second, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt number must be 1");

        CiAutofixRun withTurn = run.addAttempt(first, NOW.plusSeconds(1))
                .linkAttemptTurn(first.id(), "turn-1", NOW.plusSeconds(2));
        assertThatThrownBy(() -> withTurn.linkAttemptTurn(first.id(), "turn-2", NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another current turn");

        CiAutofixRun full = withTurn.clearCurrentTurn("turn-1", NOW.plusSeconds(3))
                .addAttempt(second, NOW.plusSeconds(4))
                .linkAttemptTurn(second.id(), "turn-2", NOW.plusSeconds(5))
                .clearCurrentTurn("turn-2", NOW.plusSeconds(6));
        CiAutofixAttempt third = newAttempt(full, 3, NOW.plusSeconds(7));
        full = full.addAttempt(third, NOW.plusSeconds(7))
                .linkAttemptTurn(third.id(), "turn-3", NOW.plusSeconds(8));

        CiAutofixRun exhausted = full;
        assertThatThrownBy(() -> exhausted.addAttempt(
                newAttempt(exhausted, 3, NOW.plusSeconds(9)),
                NOW.plusSeconds(9)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void boundsRerunsPerUnchangedHeadAndResetsOnNewHead()
    {
        CiAutofixRun run = newRun("start-3", "feature-3", NOW);
        CiAutofixAttempt attempt = newAttempt(run, 1, NOW.plusSeconds(1));
        run = run.addAttempt(attempt, NOW.plusSeconds(1));

        for (int index = 1; index <= 3; index++) {
            run = run.recordRerunJournal(
                    new RerunJournal(
                            attempt.id(),
                            100,
                            HEAD,
                            index,
                            NOW.plusSeconds(index + 1),
                            Optional.empty()),
                    NOW.plusSeconds(index + 1));
        }

        CiAutofixRun exhausted = run;
        assertThatThrownBy(() -> exhausted.recordRerunJournal(
                new RerunJournal(attempt.id(), 100, HEAD, 4, NOW.plusSeconds(5), Optional.empty()),
                NOW.plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rerun limit");

        CiAutofixRun reset = run.recordRerunJournal(
                new RerunJournal(attempt.id(), 200, "c".repeat(40), 1, NOW.plusSeconds(5), Optional.empty()),
                NOW.plusSeconds(5));
        assertThat(reset.rerunCounter().used()).isEqualTo(1);
        assertThat(reset.rerunCounter().headSha()).contains("c".repeat(40));
    }

    @Test
    void validatesAuthorizationAndDurableEvidence()
    {
        assertThatThrownBy(() -> new Authorization(true, true, true, 4, NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CiAutofixAttempt.create(
                CiAutofixAttemptId.random(),
                CiAutofixRunId.random(),
                1,
                "short",
                ImmutableList.of(failure("signature")),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full Git SHA");
        assertThatThrownBy(() -> new CiAutofixAttempt.GitResult(
                HEAD,
                "fingerprint",
                ImmutableList.of("../escape")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe");
    }

    private static CiAutofixRun newRun(String key, String headRef, Instant now)
    {
        return CiAutofixRun.create(
                CiAutofixRunId.random(),
                key,
                new Scope(
                        PullRequestRef.of("bytequay", "sample", 7),
                        "author",
                        headRef,
                        "main",
                        Path.of("/tmp/bytequay-ci-worktree"),
                        "codex"),
                HEAD,
                BASE,
                new Authorization(true, true, true, 3, now, now.plus(Duration.ofHours(8))),
                now);
    }

    private static CiAutofixAttempt newAttempt(CiAutofixRun run, int number, Instant now)
    {
        return CiAutofixAttempt.create(
                CiAutofixAttemptId.random(),
                run.id(),
                number,
                HEAD,
                ImmutableList.of(failure("signature-" + number)),
                now);
    }

    private static FailureEvidence failure(String signature)
    {
        return new FailureEvidence(
                signature,
                "compile failed",
                100,
                1,
                200,
                1,
                Optional.of(new LogReference("blobs/log-1", "text/plain", 10, false)));
    }
}
