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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntime.PreparedUpstreamSyncAdmission;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.upstream.UpstreamPicker;
import com.bytequay.app.flow.upstream.UpstreamPicker.PickResult;
import com.bytequay.app.flow.upstream.UpstreamPicker.UnresolvedRepairException;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PickState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamPick;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRequest;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Deterministically advances an upstream range to one semantic boundary. */
final class UpstreamSyncProgram
{
    private static final Logger log = LoggerFactory.getLogger(
            UpstreamSyncProgram.class);

    private final FlowRuntime runtime;
    private final UpstreamSync upstreamSync;

    UpstreamSyncProgram(FlowRuntime runtime, UpstreamSync upstreamSync)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.upstreamSync = requireNonNull(
                upstreamSync, "upstreamSync is null");
    }

    void run(Claim claim, Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(leaseTtl, "leaseTtl is null");
        Task task = runtime.task(claim.taskId()).orElseThrow();
        Path repositoryRoot = Path.of(task.repositoryRoot());
        Path worktree = Path.of(task.worktreePath());
        PreparedUpstreamSyncAdmission prepared =
                runtime.prepareUpstreamSyncAdmission(claim, repositoryRoot);
        WriterFence fence = runtime.startInspectedUpstreamSyncWriter(
                claim, prepared, leaseTtl);
        ProgramAuthority authority = new ProgramAuthority(
                runtime, claim, fence, leaseTtl, repositoryRoot);
        String resultRef;
        try {
            resultRef = pickRange(authority, worktree, task);
        }
        catch (RuntimeException failure) {
            log.warn("deterministic upstream program failed", failure);
            UpstreamSyncRun run = upstreamSync.runForTask(task.taskId())
                    .orElseThrow();
            abort(authority, new UpstreamPicker(worktree));
            upstreamSync.park(
                    run.runId(), "UPSTREAM_SYNC_FAILED:"
                            + UpstreamSyncCoordinator.describe(failure));
            resultRef = "UPSTREAM_SYNC_FAILED";
        }
        runtime.finishUpstreamSyncProgram(
                authority.claim, authority.fence, resultRef);
    }

    private String pickRange(
            ProgramAuthority authority, Path worktree, Task task)
    {
        UpstreamSyncRun run = upstreamSync.runForTask(task.taskId())
                .orElseThrow();
        UpstreamSyncRequest request = upstreamSync.request(run.requestId())
                .orElseThrow();
        UpstreamPicker picker = new UpstreamPicker(worktree);
        String expectedHead = run.currentHead() == null
                ? request.targetRef() : run.currentHead();
        if (!request.targetRef().equals(task.currentBaseSha())
                || !expectedHead.equals(picker.head())) {
            upstreamSync.park(run.runId(), "TARGET_BASE_MISMATCH");
            return "TARGET_BASE_MISMATCH";
        }
        picker.requireObjects(request.selectedUpstreamShas());
        upstreamSync.advanceState(run.runId(), RunState.PICKING);
        List<String> selected = request.selectedUpstreamShas();
        for (int ordinal = 0; ordinal < selected.size(); ordinal++) {
            if (upstreamSync.closeRequested(run.runId())) {
                abort(authority, picker);
                upstreamSync.advanceState(run.runId(), RunState.CANCELED);
                return "UPSTREAM_SYNC_CLOSED";
            }
            if (upstreamSync.pauseRequested(run.runId())) {
                abort(authority, picker);
                upstreamSync.park(run.runId(), "USER_PAUSED");
                return "USER_PAUSED";
            }
            PickStep step = applyOne(
                    authority, picker, task.taskId(), run.runId(), ordinal,
                    selected.get(ordinal));
            if (step.parkReason() != null) {
                abort(authority, picker);
                upstreamSync.park(run.runId(), step.parkReason());
                return step.parkReason();
            }
            if (step.conflict() != null) {
                abort(authority, picker);
                return "CONFLICT:" + step.conflict().pickId();
            }
        }
        upstreamSync.beginFinalReview(run.runId(), picker.head());
        return "FINAL_REVIEW";
    }

    private static void abort(
            ProgramAuthority authority, UpstreamPicker picker)
    {
        authority.call(() -> {
            picker.abortSequencer();
            return null;
        });
    }

    private PickStep applyOne(
            ProgramAuthority authority,
            UpstreamPicker picker,
            String taskId,
            String runId,
            int ordinal,
            String upstreamSha)
    {
        Optional<UpstreamPick> recorded = upstreamSync.pick(runId, ordinal);
        if (recorded.isPresent()) {
            UpstreamPick pick = recorded.orElseThrow();
            if (!pick.upstreamSha().equals(upstreamSha)) {
                throw new IllegalStateException(
                        "durable pick does not match the confirmed range");
            }
            if (pick.state() != PickState.CONFLICTED) {
                return PickStep.advanced();
            }
            upstreamSync.reenterConflictRepair(pick.pickId());
            return new PickStep(null, pick);
        }
        String preHead = authority.call(picker::head);
        PickResult result;
        try {
            result = authority.call(() -> picker.pick(upstreamSha));
        }
        catch (UnresolvedRepairException refused) {
            log.warn("upstream pick refused to advance", refused);
            return new PickStep("PICK_REFUSED", null);
        }
        return switch (result.outcome()) {
            case EMPTY -> {
                upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, null, null,
                        PickState.SKIPPED_EMPTY, List.of(), false, null);
                yield PickStep.advanced();
            }
            case CLEAN -> {
                upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, result.head(),
                        result.commitSha(), PickState.CLEAN, List.of(),
                        result.provenanceVerified(),
                        authority.adopt(taskId, result.head()));
                yield PickStep.advanced();
            }
            case CONFLICTED -> new PickStep(null, upstreamSync.recordPick(
                    runId, ordinal, upstreamSha, preHead, result.head(),
                    result.commitSha(), PickState.CONFLICTED,
                    result.conflictedPaths(), result.provenanceVerified(),
                    null));
        };
    }

    private record PickStep(String parkReason, UpstreamPick conflict)
    {
        private static PickStep advanced()
        {
            return new PickStep(null, null);
        }
    }

    private static final class ProgramAuthority
    {
        private final FlowRuntime runtime;
        private final Duration leaseTtl;
        private final Path repositoryRoot;
        private Claim claim;
        private WriterFence fence;

        private ProgramAuthority(
                FlowRuntime runtime,
                Claim claim,
                WriterFence fence,
                Duration leaseTtl,
                Path repositoryRoot)
        {
            this.runtime = runtime;
            this.claim = claim;
            this.fence = fence;
            this.leaseTtl = leaseTtl;
            this.repositoryRoot = repositoryRoot;
        }

        private <T> T call(Supplier<T> action)
        {
            claim = runtime.renewClaim(claim, leaseTtl);
            fence = runtime.renewWriterLease(claim, fence, leaseTtl);
            runtime.assertWriterFence(claim, fence);
            return action.get();
        }

        private String adopt(String taskId, String head)
        {
            ChangeSetRevision current = call(() ->
                    runtime.adoptUpstreamChangeSet(
                            claim, fence, repositoryRoot,
                            runtime.currentChangeSet(taskId)
                                    .map(ChangeSetRevision::changeSetRevisionId)
                                    .orElse(null)));
            if (!current.headSha().equals(head)) {
                throw new UnresolvedRepairException(
                        "the adopted change set is not the picked head");
            }
            return current.changeSetRevisionId();
        }
    }
}
