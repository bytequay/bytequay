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

    /**
     * How a run's pull request ended. Observed from the provider; the run
     * never decides this about itself.
     */
    public enum PrResult
    {
        MERGED,
        CLOSED
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

    /**
     * One confirmed commit of the range.
     *
     * @param subject what the picker showed for it, kept so the run surface can
     *         name a commit that has not been applied yet. Blank when the
     *         caller recorded none, which reads as unknown rather than empty.
     */
    public record SelectedCommit(String sha, String subject)
    {
        public SelectedCommit
        {
            requireNonNull(sha, "sha is null");
            subject = subject == null ? "" : subject;
            if (sha.isBlank()) {
                throw new IllegalArgumentException("a selected sha is blank");
            }
        }
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
            List<SelectedCommit> selectedCommits,
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
            selectedCommits = List.copyOf(requireNonNull(
                    selectedCommits, "selectedCommits is null"));
            if (selectedCommits.isEmpty()) {
                throw new IllegalArgumentException(
                        "an upstream sync request selects no commit");
            }
        }

        /** The range itself; the subjects beside it are display only. */
        public List<String> selectedUpstreamShas()
        {
            return selectedCommits.stream().map(SelectedCommit::sha).toList();
        }
    }

    public record UpstreamSyncRun(
            String runId,
            String requestId,
            String taskId,
            RepairPlacementPolicy repairPlacement,
            RunState state,
            int repairTurnBudget,
            int remainingRepairTurns,
            int currentIndex,
            String currentHead,
            String parkReason,
            String verificationRef,
            /** Null while the pull request is open, or before there is one. */
            PrResult prResult,
            long prResultAt,
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
            if (repairTurnBudget < 0 || remainingRepairTurns < 0
                    || remainingRepairTurns > repairTurnBudget
                    || currentIndex < 0) {
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
            return resultCommitSha != null;
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
