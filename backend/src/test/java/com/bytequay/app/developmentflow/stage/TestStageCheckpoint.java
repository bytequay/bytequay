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
package com.bytequay.app.developmentflow.stage;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestStageCheckpoint
{
    @Test
    void everyKindHasTheLockedInitialCheckpoint()
    {
        assertThat(StageKind.PLAN.initialCheckpoint()).isEqualTo(StageCheckpoint.DRAFTING);
        assertThat(StageKind.LOCAL_DEVELOPMENT.initialCheckpoint())
                .isEqualTo(StageCheckpoint.IMPLEMENTING);
        assertThat(StageKind.REMOTE_DEVELOPMENT.initialCheckpoint())
                .isEqualTo(StageCheckpoint.WAITING_CI);
        assertThat(StageKind.CLEANUP.initialCheckpoint())
                .isEqualTo(StageCheckpoint.WAITING_QUIESCENCE);
    }

    @Test
    void checkpointsCannotBeStoredUnderAnotherKind()
    {
        for (StageKind kind : StageKind.values()) {
            for (StageCheckpoint checkpoint : StageCheckpoint.values()) {
                assertThat(checkpoint.belongsTo(kind))
                        .as("%s belongs to %s", checkpoint, kind)
                        .isEqualTo(checkpoint == StageCheckpoint.COMPLETED
                                || belongsToKind(checkpoint, kind));
            }
        }
    }

    @Test
    void exposesOnlyTheLockedStructuralEdges()
    {
        Map<StageCheckpoint, Set<StageCheckpoint>> expected = expectedEdges();
        for (StageCheckpoint source : StageCheckpoint.values()) {
            for (StageCheckpoint target : StageCheckpoint.values()) {
                assertThat(source.allowsStructuralTransition(target))
                        .as("%s -> %s", source, target)
                        .isEqualTo(expected.get(source).contains(target));
            }
        }
    }

    private static boolean belongsToKind(StageCheckpoint checkpoint, StageKind kind)
    {
        return switch (kind) {
            case PLAN -> Set.of(
                    StageCheckpoint.DRAFTING,
                    StageCheckpoint.SELF_REVIEW,
                    StageCheckpoint.AWAITING_APPROVAL).contains(checkpoint);
            case LOCAL_DEVELOPMENT -> Set.of(
                    StageCheckpoint.IMPLEMENTING,
                    StageCheckpoint.VALIDATING,
                    StageCheckpoint.BRAIN_REVIEW,
                    StageCheckpoint.LOCAL_REVIEW,
                    StageCheckpoint.PUBLISHING,
                    StageCheckpoint.ADDRESSING_BRAIN_FINDINGS,
                    StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK).contains(checkpoint);
            case REMOTE_DEVELOPMENT -> Set.of(
                    StageCheckpoint.WAITING_CI,
                    StageCheckpoint.AWAITING_READY,
                    StageCheckpoint.WAITING_REMOTE_REVIEW,
                    StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK,
                    StageCheckpoint.READY_TO_MERGE,
                    StageCheckpoint.MERGING).contains(checkpoint);
            case CLEANUP -> Set.of(
                    StageCheckpoint.WAITING_QUIESCENCE,
                    StageCheckpoint.CLEANING).contains(checkpoint);
        };
    }

    private static Map<StageCheckpoint, Set<StageCheckpoint>> expectedEdges()
    {
        Map<StageCheckpoint, Set<StageCheckpoint>> edges =
                new EnumMap<>(StageCheckpoint.class);
        edges.put(StageCheckpoint.DRAFTING, Set.of(StageCheckpoint.SELF_REVIEW));
        edges.put(StageCheckpoint.SELF_REVIEW, Set.of(
                StageCheckpoint.DRAFTING, StageCheckpoint.AWAITING_APPROVAL));
        edges.put(StageCheckpoint.AWAITING_APPROVAL, Set.of(
                StageCheckpoint.DRAFTING, StageCheckpoint.COMPLETED));
        edges.put(StageCheckpoint.IMPLEMENTING, Set.of(StageCheckpoint.VALIDATING));
        edges.put(StageCheckpoint.VALIDATING, Set.of(StageCheckpoint.BRAIN_REVIEW));
        edges.put(StageCheckpoint.BRAIN_REVIEW, Set.of(
                StageCheckpoint.LOCAL_REVIEW,
                StageCheckpoint.ADDRESSING_BRAIN_FINDINGS));
        edges.put(StageCheckpoint.ADDRESSING_BRAIN_FINDINGS,
                Set.of(StageCheckpoint.IMPLEMENTING));
        edges.put(StageCheckpoint.LOCAL_REVIEW, Set.of(
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK,
                StageCheckpoint.PUBLISHING));
        edges.put(StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK,
                Set.of(StageCheckpoint.IMPLEMENTING));
        edges.put(StageCheckpoint.PUBLISHING, Set.of(StageCheckpoint.COMPLETED));
        edges.put(StageCheckpoint.WAITING_CI, Set.of(StageCheckpoint.AWAITING_READY));
        edges.put(StageCheckpoint.AWAITING_READY,
                Set.of(StageCheckpoint.WAITING_REMOTE_REVIEW,
                        StageCheckpoint.WAITING_CI));
        edges.put(StageCheckpoint.WAITING_REMOTE_REVIEW, Set.of(
                StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK,
                StageCheckpoint.READY_TO_MERGE,
                StageCheckpoint.WAITING_CI));
        edges.put(StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK,
                Set.of(StageCheckpoint.WAITING_CI,
                        StageCheckpoint.WAITING_REMOTE_REVIEW));
        edges.put(StageCheckpoint.READY_TO_MERGE, Set.of(
                StageCheckpoint.MERGING,
                StageCheckpoint.WAITING_CI));
        edges.put(StageCheckpoint.MERGING, Set.of(
                StageCheckpoint.READY_TO_MERGE,
                StageCheckpoint.COMPLETED,
                StageCheckpoint.WAITING_CI));
        edges.put(StageCheckpoint.WAITING_QUIESCENCE, Set.of(StageCheckpoint.CLEANING));
        edges.put(StageCheckpoint.CLEANING, Set.of(StageCheckpoint.COMPLETED));
        edges.put(StageCheckpoint.COMPLETED, Set.of());
        return edges;
    }
}
