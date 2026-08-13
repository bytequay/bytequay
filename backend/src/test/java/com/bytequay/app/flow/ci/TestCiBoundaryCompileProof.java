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
                true);

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
                true);

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
    void aRewriteWithoutAProvenBoundaryCannotEvenOpenItsGate()
    {
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, true);

        assertThatThrownBy(() -> readyGate("unproven", null))
                .hasMessageContaining("BOUNDARY_COMPILE_PROOF_MISSING");
        assertThat(count("flow_user_gate_revision", "1 = 1")).isZero();
    }

    @Test
    void aRewriteWithoutStandingAuthorityIsManualOnly()
    {
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, false);

        GateRevision revision = readyGate("unauthorized", TARGET_FIXUP);

        GateSubject subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.warningCodes())
                .contains("HISTORY_REWRITE_UNAUTHORIZED");
        // Manual-only is what stops a one-shot consent from rewriting
        // published history without the user saying so for this exact round.
        assertThat(subject.manualOnly()).isTrue();
    }

    @Test
    void aRewriteWithStandingAuthorityPublishesLikeAnyOtherFix()
    {
        autofix.recordPlacementPolicy(
                task.taskId(), RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(), null, null, true);

        GateRevision revision = readyGate("authorized", TARGET_FIXUP);

        GateSubject subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.warningCodes())
                .doesNotContain("HISTORY_REWRITE_UNAUTHORIZED");
        assertThat(subject.manualOnly()).isFalse();
        // The record says what the publication actually does.
        assertThat(userGates.ciUpdateAction(revision.actionManifestRef())
                .orElseThrow().forcePush())
                .isTrue();
    }

    @Test
    void anOrdinaryFixIsStillRecordedAsANonForcedPush()
    {
        GateRevision revision = readyGate("ordinary", null);

        assertThat(userGates.ciUpdateAction(revision.actionManifestRef())
                .orElseThrow().forcePush())
                .isFalse();
        assertThat(userGates.subject(revision.subjectManifestRef())
                .orElseThrow().manualOnly())
                .isFalse();
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
     * Drives one repair to its ready gate, storing a boundary proof for the
     * exact candidate head first when {@code provenBoundary} is given.
     */
    private GateRevision readyGate(String suffix, String provenBoundary)
    {
        var ready = prepareReviewerResult(suffix);
        String candidate = runtime.task(task.taskId())
                .orElseThrow().currentHeadSha();
        if (provenBoundary != null) {
            autofix.storeBoundaryCompileProof(
                    task.taskId(),
                    autofix.repairAttemptForRound(
                            ready.ready().binding().projection().roundId(), 0)
                            .orElseThrow().attemptId(),
                    candidate,
                    "profile-1",
                    List.of(new BoundaryOutcome(
                            provenBoundary, BoundaryKind.TARGET_WITH_FIXUP,
                            0, "sha256:one")));
        }
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
