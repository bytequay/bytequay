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
import org.junit.jupiter.api.Test;

import java.util.List;

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
