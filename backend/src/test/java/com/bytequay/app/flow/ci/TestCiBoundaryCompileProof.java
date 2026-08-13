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
