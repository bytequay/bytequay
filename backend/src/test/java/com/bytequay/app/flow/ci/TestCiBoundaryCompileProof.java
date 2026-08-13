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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.AttributedFixupRebase.BoundaryKind;
import com.bytequay.app.flow.ci.AttributedFixupRebase.BoundaryOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.BoundaryExitState;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacement;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The evidence a rewritten series may only be published on. */
class TestCiBoundaryCompileProof
        extends BaseTestCiAutofixCoordinator
{
    private static final String TARGET_FIXUP = "a".repeat(40);
    private static final String PLAIN = "b".repeat(40);
    private static final List<String> BUILD =
            List.of("/usr/bin/true");

    @Test
    void aProofIsImmutablePerHeadAndBelongsToNoOtherHead()
    {
        var started = startRepair();
        String attemptId = started.binding().attempt().attemptId();
        String head = started.binding().attempt().inputLocalHead();

        var proof = autofix.storeBoundaryCompileProof(
                task.taskId(), attemptId, head, "profile-1",
                List.of(
                        new BoundaryOutcome(
                                TARGET_FIXUP, BoundaryKind.TARGET_WITH_FIXUP,
                                0, "sha256:one"),
                        new BoundaryOutcome(
                                PLAIN, BoundaryKind.PLAIN, 1, "sha256:two")));

        assertThat(proof.boundaries()).extracting(
                        boundary -> boundary.exitState())
                .containsExactly(
                        BoundaryExitState.PASSED, BoundaryExitState.FAILED);
        assertThat(proof.allPassed()).isFalse();
        assertThat(autofix.boundaryCompileProof(attemptId, head))
                .contains(proof);
        // A proof is evidence about one exact head, so another head has none
        // until its own boundaries are built.
        assertThat(autofix.boundaryCompileProof(attemptId, PLAIN)).isEmpty();

        assertThat(autofix.storeBoundaryCompileProof(
                task.taskId(), attemptId, head, "profile-1",
                List.of(
                        new BoundaryOutcome(
                                TARGET_FIXUP, BoundaryKind.TARGET_WITH_FIXUP,
                                0, "sha256:one"),
                        new BoundaryOutcome(
                                PLAIN, BoundaryKind.PLAIN, 1, "sha256:two"))))
                .isEqualTo(proof);
        assertThatThrownBy(() -> autofix.storeBoundaryCompileProof(
                task.taskId(), attemptId, head, "profile-1",
                List.of(new BoundaryOutcome(
                        PLAIN, BoundaryKind.PLAIN, 0, "sha256:two"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable per head");
    }

    @Test
    void aProofWithoutABoundaryProvesNothing()
    {
        var started = startRepair();

        assertThatThrownBy(() -> autofix.storeBoundaryCompileProof(
                task.taskId(),
                started.binding().attempt().attemptId(),
                started.binding().attempt().inputLocalHead(),
                "profile-1",
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proves nothing");
    }

    @Test
    void onlyAProvenBoundaryExcusesTheSeriesCompileCheck()
    {
        var started = startRepair();
        String attemptId = started.binding().attempt().attemptId();
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of("build"), ".github/workflows/ci.yml", "sha256:ci",
                true,
                BUILD);

        // The per-commit check is red because a target is red in isolation by
        // construction. Without the program's own proof that is simply red.
        assertThat(acceptedRequiredCi()).isFalse();

        autofix.storeBoundaryCompileProof(
                task.taskId(), attemptId, publishedHead, "profile-1",
                List.of(
                        new BoundaryOutcome(
                                TARGET_FIXUP, BoundaryKind.TARGET_WITH_FIXUP,
                                0, "sha256:one"),
                        new BoundaryOutcome(
                                PLAIN, BoundaryKind.PLAIN, 0, "sha256:two")));

        assertThat(acceptedRequiredCi()).isTrue();
    }

    @Test
    void aFailedOrFixuplessProofExcusesNothing()
    {
        var started = startRepair();
        String attemptId = started.binding().attempt().attemptId();
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of("build"), ".github/workflows/ci.yml", "sha256:ci",
                true,
                BUILD);

        // Green, but no commit in it carries a following fixup, so there is no
        // by-construction red to excuse.
        autofix.storeBoundaryCompileProof(
                task.taskId(), attemptId, publishedHead, "profile-1",
                List.of(new BoundaryOutcome(
                        PLAIN, BoundaryKind.PLAIN, 0, "sha256:two")));
        assertThat(acceptedRequiredCi()).isFalse();

        // A proof for some other head is not evidence about this one.
        autofix.storeBoundaryCompileProof(
                task.taskId(), attemptId, TARGET_FIXUP, "profile-1",
                List.of(new BoundaryOutcome(
                        TARGET_FIXUP, BoundaryKind.TARGET_WITH_FIXUP,
                        0, "sha256:one")));
        assertThat(acceptedRequiredCi()).isFalse();
    }

    @Test
    void aTaskWithoutTheRewritingPlacementIsNeverExcused()
    {
        var started = startRepair();

        autofix.storeBoundaryCompileProof(
                task.taskId(),
                started.binding().attempt().attemptId(),
                publishedHead,
                "profile-1",
                List.of(new BoundaryOutcome(
                        TARGET_FIXUP, BoundaryKind.TARGET_WITH_FIXUP,
                        0, "sha256:one")));

        assertThat(acceptedRequiredCi()).isFalse();
    }

    @Test
    void aRewriteItCannotProveCannotEvenOpenItsGate()
    {
        // No boundary build, so this Task can never prove a rewrite.
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, true, List.of());

        assertThatThrownBy(() -> readyGate("unprovable"))
                .hasMessageContaining("BOUNDARY_COMPILE_PROOF_MISSING");
        assertThat(count("flow_user_gate_revision", "1 = 1")).isZero();
    }

    @Test
    void aRewriteWithoutStandingAuthorityIsManualOnly()
    {
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, false, BUILD);

        GateRevision revision = readyGate("unauthorized");

        GateSubject subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.warningCodes())
                .contains("HISTORY_REWRITE_UNAUTHORIZED");
        // Manual-only is what stops a one-shot consent from rewriting
        // published history without the user saying so for this exact round.
        assertThat(subject.manualOnly()).isTrue();
    }

    @Test
    void aProvenRewriteIsAdoptedReviewedAndRecordedAsForced()
    {
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, true, BUILD);

        GateRevision revision = readyGate("authorized");

        // The proof the program built itself, for the exact head the Task
        // adopted and the reviewer saw.
        String reviewed = runtime.task(task.taskId())
                .orElseThrow().currentHeadSha();
        assertThat(autofix.boundaryCompileProofForHead(reviewed))
                .get()
                .matches(proof -> proof.allPassed(), "all boundaries green");
        GateSubject subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.proposedHead()).isEqualTo(reviewed);
        assertThat(subject.warningCodes())
                .doesNotContain("HISTORY_REWRITE_UNAUTHORIZED");
        assertThat(subject.manualOnly()).isFalse();
        // The record says what the publication actually does.
        assertThat(userGates.ciUpdateAction(revision.actionManifestRef())
                .orElseThrow().forcePush())
                .isTrue();
    }

    @Test
    void anOrdinaryFixIsNeitherRewrittenNorForced()
    {
        GateRevision revision = readyGate("ordinary");

        assertThat(autofix.boundaryCompileProofForHead(
                runtime.task(task.taskId()).orElseThrow().currentHeadSha()))
                .isEmpty();
        assertThat(userGates.ciUpdateAction(revision.actionManifestRef())
                .orElseThrow().forcePush())
                .isFalse();
        assertThat(userGates.subject(revision.subjectManifestRef())
                .orElseThrow().manualOnly())
                .isFalse();
    }

    @Test
    void fixerSelectsAnExactEligibleTargetButProgramCreatesTheFixupMessage()
    {
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, true, BUILD);
        var started = startRepair();
        var tools = repairCoordinator.repairToolContext(started.binding());

        assertThat(tools.failureSummary())
                .contains("eligibleFixupTargets=" + publishedHead
                        + " task change");
        assertThat(tools.repairCommitMessage(publishedHead))
                .isEqualTo("fixup! task change");
        assertThatThrownBy(() -> tools.repairCommitMessage("f".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not eligible");
    }

    @Test
    void failedBoundaryProofRestoresAndTheStoppedFinalizerReprovesDurably()
            throws Exception
    {
        var marker = temporaryDirectory.resolve("boundary-passed-once");
        var build = temporaryDirectory.resolve("boundary-build.sh");
        Files.writeString(build, """
                if [ ! -e '%s' ]; then
                  touch '%s'
                  exit 1
                fi
                exit 0
                """.formatted(marker, marker));
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, true,
                List.of("/bin/sh", build.toString()));
        var started = startRepair();
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED, "opaque fixer", null);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = repairCoordinator.launchRepair(
                supervisor, started.binding(), started.claim(),
                started.fence(), repositoryRoot, capability -> {
                    capability.runTool(() -> commitCiChange(
                            "proof-repair.txt", "repair\n",
                            "fixup! task change"));
                    return completion;
                });

        assertThatThrownBy(() -> repairCoordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(AttributedFixupRebase.RebaseFailure.class)
                .satisfies(failure -> assertThat(
                        ((AttributedFixupRebase.RebaseFailure) failure).code())
                        .isEqualTo(
                                AttributedFixupRebase.FailureCode.PROOF_FAILED));
        String fixerHead = gitOutput(
                Path.of(task.worktreePath()), "rev-parse", "HEAD");
        assertThat(gitOutput(
                Path.of(task.worktreePath()), "log", "-1", "--format=%s"))
                .isEqualTo("fixup! task change");
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(CiAutofixRecords.AttemptState.ACTIVE);

        runtimeNow = NOW.plus(TTL).plusSeconds(1);
        rebuildOwnerGraph(Clock.fixed(runtimeNow, ZoneOffset.UTC), true);
        repairCoordinator.recoverExpiredStoppedRepair(
                started.claim().operationId(), started.claim().generation(), TTL);

        String adopted = runtime.task(task.taskId()).orElseThrow()
                .currentHeadSha();
        assertThat(adopted).isEqualTo(fixerHead);
        assertThat(gitOutput(
                Path.of(task.worktreePath()), "rev-parse", "HEAD"))
                .isEqualTo(adopted);
        assertThat(autofix.boundaryCompileProofForHead(adopted))
                .get().matches(CiAutofixRecords.BoundaryCompileProof::allPassed);
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(CiAutofixRecords.AttemptState.FIX_PREPARED);
    }

    @Test
    void parkingAnIdleTaskSurfacesItWithoutInterruptingAWriter()
    {
        assertThat(runtime.parkIdleTask(
                task.taskId(), "park-op", "CI_REPAIR_NOT_CONVERGING",
                "ci-round:example"))
                .isTrue();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        // Already parked, so there is nothing left to say.
        assertThat(runtime.parkIdleTask(
                task.taskId(), "park-op", "CI_REPAIR_NOT_CONVERGING",
                "ci-round:example"))
                .isFalse();
    }

    @Test
    void aRunningWriterIsNeverParkedOutFromUnderItself()
    {
        var started = startRepair();

        assertThat(runtime.parkIdleTask(
                task.taskId(),
                started.claim().operationId(),
                "CI_REPAIR_NOT_CONVERGING",
                "ci-round:" + started.binding().attempt().roundId()))
                .isFalse();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
    }

    /**
     * Drives one repair through to its ready gate. Under attributed placement
     * the repair path rewrites and proves the series itself on the way.
     */
    private GateRevision readyGate(String suffix)
    {
        var ready = prepareReviewerResult(suffix);
        AtomicReference<RuntimeException> rejection = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review().launchReviewerResultContinuation(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    try {
                        capability.readyForReview();
                    }
                    catch (RuntimeException rejected) {
                        // The tool error the Task Agent would actually see.
                        rejection.set(rejected);
                    }
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "opaque ready " + suffix,
                            null);
                });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);
        if (rejection.get() != null) {
            throw rejection.get();
        }
        return userGates.revisionForRun(ready.binding().run().runId())
                .orElseThrow();
    }

    /** Whether required CI counts as accepted for the published head. */
    private boolean acceptedRequiredCi()
    {
        try {
            autofix.acceptedRequiredCiSnapshot(
                    pr.prId(),
                    publishedHead,
                    autofix.currentPolicy(task.repositoryId(), pr.scopeKey())
                            .orElseThrow().policyRevisionId());
            return true;
        }
        catch (CiAutofix.CiEvidenceUnavailableException expected) {
            return false;
        }
    }

    @Test
    void anAllGreenProofIsWhatMayBePublished()
    {
        var started = startRepair();

        var proof = autofix.storeBoundaryCompileProof(
                task.taskId(),
                started.binding().attempt().attemptId(),
                started.binding().attempt().inputLocalHead(),
                "profile-1",
                List.of(
                        new BoundaryOutcome(
                                TARGET_FIXUP, BoundaryKind.TARGET_WITH_FIXUP,
                                0, "sha256:one"),
                        new BoundaryOutcome(
                                PLAIN, BoundaryKind.PLAIN, 0, "sha256:two")));

        assertThat(proof.allPassed()).isTrue();
    }
}
