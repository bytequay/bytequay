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
package com.bytequay.app.flow.upstream;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Greenfield records for one upstream cherry-pick synchronization. */
public final class UpstreamSyncRecords
{
    private UpstreamSyncRecords() {}

    /**
     * Where a program-resolved repair belongs in a Task's history.
     *
     * <p>Ordinary Tasks keep {@link #TIP}. Only a Task whose branch this
     * component built is a reviewable upstream series, and only there does an
     * opaque tip commit spanning several picks destroy the property the range
     * exists for.
     */
    public enum RepairPlacementPolicy
    {
        TIP,
        ATTRIBUTED_FIXUP
    }

    public enum RequestState
    {
        REQUESTED,
        STARTED,
        CANCELED,
        NEEDS_ATTENTION
    }

    public enum RunState
    {
        READY,
        PICKING,
        WAITING_CONFLICT_REPAIR,
        WAITING_USER,
        FINAL_REVIEW,
        WAITING_INITIAL_PUBLISH,
        HANDED_OFF,
        CANCELED,
        NEEDS_ATTENTION
    }

    public enum PickState
    {
        CLEAN,
        CONFLICTED,
        RESOLVED,
        SKIPPED_EMPTY,
        NEEDS_ATTENTION
    }

    public enum FixupKind
    {
        ADJACENT_FIXUP,
        STANDALONE
    }

    public record UpstreamSyncRequest(
            String requestId,
            String requestKey,
            String repositoryId,
            String goalText,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            List<String> selectedUpstreamShas,
            RequestState state,
            String requestedByUserId,
            long createdAt)
    {
        public UpstreamSyncRequest
        {
            requireNonNull(requestId, "requestId is null");
            requireNonNull(requestKey, "requestKey is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(goalText, "goalText is null");
            requireNonNull(state, "state is null");
            selectedUpstreamShas = List.copyOf(requireNonNull(
                    selectedUpstreamShas, "selectedUpstreamShas is null"));
            if (selectedUpstreamShas.isEmpty()) {
                throw new IllegalArgumentException(
                        "an upstream sync request selects no commit");
            }
        }
    }

    public record UpstreamSyncRun(
            String runId,
            String requestId,
            String taskId,
            RepairPlacementPolicy repairPlacement,
            RunState state,
            int remainingRepairTurns,
            int currentIndex,
            String currentHead,
            String parkReason,
            String verificationRef,
            long createdAt,
            long updatedAt)
    {
        public UpstreamSyncRun
        {
            requireNonNull(runId, "runId is null");
            requireNonNull(requestId, "requestId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(repairPlacement, "repairPlacement is null");
            requireNonNull(state, "state is null");
            if (remainingRepairTurns < 0 || currentIndex < 0) {
                throw new IllegalArgumentException(
                        "upstream sync run counters are negative");
            }
        }
    }

    public record UpstreamPick(
            String pickId,
            String runId,
            int ordinal,
            String upstreamSha,
            String preHead,
            String resultHead,
            String resultCommitSha,
            PickState state,
            List<String> conflictedPaths,
            boolean provenanceVerified,
            String changeSetRevisionId,
            long recordedAt)
    {
        public UpstreamPick
        {
            requireNonNull(pickId, "pickId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(upstreamSha, "upstreamSha is null");
            requireNonNull(preHead, "preHead is null");
            requireNonNull(state, "state is null");
            conflictedPaths = List.copyOf(requireNonNull(
                    conflictedPaths, "conflictedPaths is null"));
        }

        public boolean landedCommit()
        {
            return state != PickState.SKIPPED_EMPTY;
        }
    }

    public record UpstreamFixup(
            String fixupId,
            String runId,
            String pickId,
            String ownerUpstreamSha,
            FixupKind kind,
            String currentCommitSha,
            List<String> changedPaths,
            String createdByRunId,
            int amendCount,
            String changeSetRevisionId,
            long recordedAt)
    {
        public UpstreamFixup
        {
            requireNonNull(fixupId, "fixupId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(pickId, "pickId is null");
            requireNonNull(ownerUpstreamSha, "ownerUpstreamSha is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(currentCommitSha, "currentCommitSha is null");
            changedPaths = List.copyOf(requireNonNull(
                    changedPaths, "changedPaths is null"));
            if (amendCount < 0) {
                throw new IllegalArgumentException("amendCount is negative");
            }
        }
    }
}
