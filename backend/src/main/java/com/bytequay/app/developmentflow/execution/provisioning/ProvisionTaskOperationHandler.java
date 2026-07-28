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
package com.bytequay.app.developmentflow.execution.provisioning;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Executes one exact, already-authorized V2 Task provisioning request. */
public final class ProvisionTaskOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "PROVISION_TASK";
    public static final String CALLBACK_ROUTE = "TASK_PROVISION_RESULT";

    private final OperationStore operations;
    private final ProvisioningGit git;
    private final WorktreeWriterLeaseManager writers;
    private final ObjectMapper json;

    public ProvisionTaskOperationHandler(
            OperationStore operations,
            ProvisioningGit git,
            WorktreeWriterLeaseManager writers,
            ObjectMapper json)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.git = requireNonNull(git, "git is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        return provision(context);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        // The first action in provision() is the exact on-disk probe. No Git
        // mutation is replayed until that probe proves the target absent.
        return provision(context);
    }

    private DispatchTicket.DispatchResult provision(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        ProvisionRequest request = operations.requireByOperationId(
                envelope.fence().operationId());
        MutationTarget target = validate(envelope, request);
        cancelBeforeMutation(context);

        WorktreeWriterLeaseManager.Lease lease = writers.acquire(
                context, target.worktreePath().toString());
        ProvisioningGit.Probe before = git.probe(target);
        if (before.state() == ProvisioningGit.ProbeState.EXACT) {
            return exactResult(context, request, target, before.headSha());
        }
        requireAbsent(before);

        ResolvedSource source;
        try {
            source = resolveSource(context, lease, request, target);
        }
        catch (ProvisioningGit.MutationFailure failure) {
            return recoverAfterMutationFailure(
                    context, request, target, null, failure);
        }

        cancelBeforeMutation(context);
        try {
            mutate(context, lease, fence -> {
                git.createWorktree(
                        target,
                        source.headSha(),
                        fence);
                return null;
            });
        }
        catch (ProvisioningGit.MutationFailure failure) {
            return recoverAfterMutationFailure(
                    context, request, target, source, failure);
        }

        cancelBeforeMutation(context);
        ProvisioningGit.Probe created = git.probe(target);
        requireExactHead(created, source.headSha());
        return success(context, request, target, source.baseSha(), source.headSha());
    }

    private ResolvedSource resolveSource(
            ExecutionContext context,
            WorktreeWriterLeaseManager.Lease lease,
            ProvisionRequest request,
            MutationTarget target)
            throws Exception
    {
        return switch (request.baseSource()) {
            case PLANNING_SNAPSHOT -> {
                requireLocalCommit(
                        target.repositoryRoot(), request.expectedBaseSha(),
                        "planning snapshot");
                yield new ResolvedSource(
                        request.expectedBaseSha(), request.expectedBaseSha());
            }
            case FRESH_REMOTE_BASE -> {
                String remote = git.requireExactRemote(
                        target.repositoryRoot(), request.baseRepositoryId());
                fetch(context, lease, target, remote);
                String base = git.resolveRemoteBranch(
                                target.repositoryRoot(), remote, request.baseRef())
                        .orElseThrow(() -> new ExecutionPorts.RetryableExecutionException(
                                "fresh base ref is not available after exact fetch"));
                yield new ResolvedSource(base, base);
            }
            case EXISTING_PR_HEAD -> {
                String baseRemote = git.requireExactRemote(
                        target.repositoryRoot(), request.baseRepositoryId());
                String headRemote = git.requireExactRemote(
                        target.repositoryRoot(), request.assignmentHeadRepositoryId());
                fetch(context, lease, target, baseRemote);
                if (!headRemote.equals(baseRemote)) {
                    fetch(context, lease, target, headRemote);
                }
                String base = requireRemoteCommit(
                        target.repositoryRoot(), baseRemote, request.baseRef(),
                        request.expectedBaseSha(), "existing PR base");
                String head = requireRemoteCommit(
                        target.repositoryRoot(), headRemote, request.headRef(),
                        request.expectedHeadSha(), "existing PR head");
                yield new ResolvedSource(base, head);
            }
        };
    }

    private void fetch(
            ExecutionContext context,
            WorktreeWriterLeaseManager.Lease lease,
            MutationTarget target,
            String remote)
            throws Exception
    {
        cancelBeforeMutation(context);
        mutate(context, lease, fence -> {
            git.fetchRemote(target, remote, fence);
            return null;
        });
        cancelBeforeMutation(context);
    }

    private <T> T mutate(
            ExecutionContext context,
            WorktreeWriterLeaseManager.Lease lease,
            Function<WorktreeWriterLeaseManager.MutationFence, T> mutation)
    {
        return writers.authorizeMutation(context, lease).run(mutation);
    }

    private DispatchTicket.DispatchResult recoverAfterMutationFailure(
            ExecutionContext context,
            ProvisionRequest request,
            MutationTarget target,
            ResolvedSource source,
            ProvisioningGit.MutationFailure failure)
            throws Exception
    {
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "provisioning was canceled during Git mutation");
        }
        ProvisioningGit.Probe after = git.probe(target);
        if (after.state() == ProvisioningGit.ProbeState.EXACT) {
            if (source != null) {
                requireExactHead(after, source.headSha());
                return success(
                        context, request, target, source.baseSha(), source.headSha());
            }
            return exactResult(context, request, target, after.headSha());
        }
        if (after.state() == ProvisioningGit.ProbeState.ABSENT) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "Git mutation left no branch or worktree", failure);
        }
        throw new ExecutionPorts.IndeterminateExecutionException(
                "Git mutation left conflicting provisioning state: " + after.detail(),
                failure);
    }

    private DispatchTicket.DispatchResult exactResult(
            ExecutionContext context,
            ProvisionRequest request,
            MutationTarget target,
            String observedHead)
            throws JsonProcessingException, ExecutionPorts.IndeterminateExecutionException
    {
        String base = switch (request.baseSource()) {
            case PLANNING_SNAPSHOT -> request.expectedBaseSha();
            case FRESH_REMOTE_BASE -> observedHead;
            case EXISTING_PR_HEAD -> request.expectedBaseSha();
        };
        String requiredHead = request.baseSource() == BaseSource.EXISTING_PR_HEAD
                ? request.expectedHeadSha()
                : base;
        requireExactHead(
                ProvisioningGit.Probe.exact(observedHead), requiredHead);
        if (request.baseSource() != BaseSource.FRESH_REMOTE_BASE) {
            requireLocalCommit(target.repositoryRoot(), base, "provisioning base");
        }
        return success(context, request, target, base, observedHead);
    }

    private DispatchTicket.DispatchResult success(
            ExecutionContext context,
            ProvisionRequest request,
            MutationTarget target,
            String baseSha,
            String headSha)
            throws JsonProcessingException
    {
        String fingerprint = requireText(
                git.codeFingerprint(target), "codeFingerprint");
        ProvisionEvidence evidence = new ProvisionEvidence(
                "PROVISION_TASK_V1",
                request.operationId(),
                request.taskId(),
                request.taskEpoch(),
                request.baseSource(),
                request.repositoryId(),
                request.branchName(),
                request.worktreePath(),
                requireText(baseSha, "baseSha"),
                requireText(headSha, "headSha"),
                fingerprint);
        String serialized = json.writeValueAsString(evidence);
        return new DispatchTicket.DispatchResult(
                context.envelope().fence(), SUCCEEDED, serialized, serialized, null);
    }

    private void requireLocalCommit(Path repositoryRoot, String expected, String label)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        String resolved = git.resolveCommit(repositoryRoot, expected).orElse(null);
        if (!Objects.equals(expected, resolved)) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    label + " does not resolve to its frozen SHA");
        }
    }

    private String requireRemoteCommit(
            Path repositoryRoot,
            String remote,
            String ref,
            String expected,
            String label)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        String resolved = git.resolveRemoteBranch(repositoryRoot, remote, ref).orElse(null);
        if (!Objects.equals(expected, resolved)) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    label + " does not match its frozen SHA");
        }
        return resolved;
    }

    private static MutationTarget validate(
            DispatchTicket.DispatchEnvelope envelope,
            ProvisionRequest request)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        requireEnvelope(envelope, request);
        requireFrozenGraph(request);

        Path repositoryRoot = normalizedExactPath(
                request.localClonePath(), "local repository");
        Path worktreePath = normalizedExactPath(
                request.worktreePath(), "provisioning worktree");
        Path taskSegment = Path.of(request.taskId());
        if (taskSegment.getNameCount() != 1
                || taskSegment.isAbsolute()
                || taskSegment.toString().equals(".")
                || taskSegment.toString().equals("..")) {
            throw indeterminate("Task id cannot identify an exact worktree path");
        }
        Path expectedPath = repositoryRoot
                .resolve(".worktrees")
                .resolve(taskSegment)
                .toAbsolutePath()
                .normalize();
        if (!worktreePath.equals(expectedPath)) {
            throw indeterminate("provisioning worktree path is not the frozen Task path");
        }
        if (!request.branchName().equals("dev/" + request.taskId())) {
            throw indeterminate("provisioning branch is not the frozen Task branch");
        }
        return new MutationTarget(
                request.taskId(), request.taskEpoch(), request.operationId(),
                repositoryRoot, request.branchName(), worktreePath);
    }

    private static void requireEnvelope(
            DispatchTicket.DispatchEnvelope envelope,
            ProvisionRequest request)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        CapacityManager.CapacityScope scope = capacity.scope();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != DispatchTicket.AsyncFamily.LOCAL_GIT
                || envelope.owner().kind() != TASK
                || !request.taskId().equals(envelope.owner().id())
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !Objects.equals(request.taskEpoch(), fence.taskEpoch())
                || fence.stageId() != null
                || fence.stageGeneration() != null
                || !request.operationId().equals(fence.operationId())
                || request.attempt() != fence.attempt()
                || fence.expectedCodeFingerprint() != null
                || !Objects.equals(request.expectedHeadSha(), fence.expectedHeadSha())
                || !Objects.equals(request.expectedBaseSha(), fence.expectedBaseSha())
                || capacity.source() != V2
                || !capacity.lanes().equals(Set.of(LOCAL_GIT))
                || capacity.trunkControl()
                || !capacity.exclusiveTask()
                || !capacity.writerRequired()
                || !request.workspaceId().equals(scope.workspaceId())
                || !request.trunkId().equals(scope.trunkId())
                || !request.taskId().equals(scope.taskId())
                || !Objects.equals(request.taskEpoch(), scope.taskEpoch())) {
            throw indeterminate("provisioning envelope does not match its frozen operation");
        }
    }

    private static void requireFrozenGraph(ProvisionRequest request)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        if (!"V2".equals(request.workflowVersion())
                || !"PROVISIONING".equals(request.taskLifecycle())
                || !"DISPATCHED".equals(request.operationStatus())
                || !request.taskAssignmentId().equals(request.operationAssignmentId())
                || !request.taskAssignmentId().equals(request.contextAssignmentId())
                || !request.repositoryId().equals(request.contextRepositoryId())
                || !request.repositoryId().equals(
                        request.contextPublishRepositoryId())
                || !request.repositoryId().equals(request.targetRepositoryId())
                || !request.contextPublishRepositoryId().equals(
                        request.targetPublishRepositoryId())
                || request.baseSource() != request.contextBaseSource()
                || !request.baseRepositoryId().equals(
                        request.contextBaseRepositoryId())
                || !request.baseRef().equals(request.contextBaseRef())
                || !request.branchName().equals(request.targetBranchName())
                || !request.worktreePath().equals(request.targetWorktreePath())) {
            throw indeterminate("provisioning rows do not form one exact frozen graph");
        }
        String routedBaseRepository = request.contextUpstreamRepositoryId() == null
                ? request.repositoryId()
                : request.contextUpstreamRepositoryId();
        if (!routedBaseRepository.equals(request.baseRepositoryId())) {
            throw indeterminate("provisioning base repository route is not exact");
        }

        switch (request.baseSource()) {
            case PLANNING_SNAPSHOT -> {
                if (!"NEW_FROM_TRUNK".equals(request.assignmentKind())
                        || !Objects.equals(
                                request.contextPlanningBaseSha(), request.expectedBaseSha())
                        || !Objects.equals(
                                request.assignmentPlanningBaseSha(), request.expectedBaseSha())
                        || request.expectedHeadSha() != null
                        || hasExistingPrSource(request)) {
                    throw indeterminate("planning snapshot source is not exact");
                }
            }
            case FRESH_REMOTE_BASE -> {
                if (!Set.of(
                                "NEW_FROM_TRUNK", "ISSUE", "AUTOMATION", "QUALITY_SCAN")
                        .contains(request.assignmentKind())
                        || request.expectedBaseSha() != null
                        || request.expectedHeadSha() != null
                        || request.contextPlanningBaseSha() != null
                        || request.assignmentPlanningBaseSha() != null
                        || hasExistingPrSource(request)) {
                    throw indeterminate("fresh remote base source is not exact");
                }
            }
            case EXISTING_PR_HEAD -> {
                if (!Set.of("EXISTING_OWN_PR", "REVIEW_FINDINGS")
                        .contains(request.assignmentKind())
                        || !request.repositoryId().equals(
                                request.assignmentRepositoryId())
                        || !request.baseRepositoryId().equals(
                                request.assignmentBaseRepositoryId())
                        || !request.contextPublishRepositoryId().equals(
                                request.assignmentHeadRepositoryId())
                        || !request.baseRef().equals(request.assignmentBaseRef())
                        || !Objects.equals(
                                request.contextAssignmentBaseSha(),
                                request.expectedBaseSha())
                        || !Objects.equals(
                                request.contextAssignmentHeadSha(),
                                request.expectedHeadSha())
                        || !Objects.equals(
                                request.assignmentRemoteBaseSha(),
                                request.expectedBaseSha())
                        || !Objects.equals(
                                request.assignmentRemoteHeadSha(),
                                request.expectedHeadSha())
                        || request.headRef() == null
                        || request.expectedBaseSha() == null
                        || request.expectedHeadSha() == null
                        || request.headRef().isBlank()
                        || request.contextPlanningBaseSha() != null
                        || request.assignmentPlanningBaseSha() != null) {
                    throw indeterminate("existing PR source is not exact");
                }
            }
        }
    }

    private static boolean hasExistingPrSource(ProvisionRequest request)
    {
        return request.assignmentRepositoryId() != null
                || request.assignmentBaseRepositoryId() != null
                || request.assignmentHeadRepositoryId() != null
                || request.assignmentBaseRef() != null
                || request.headRef() != null
                || request.assignmentRemoteBaseSha() != null
                || request.assignmentRemoteHeadSha() != null
                || request.contextAssignmentBaseSha() != null
                || request.contextAssignmentHeadSha() != null;
    }

    private static Path normalizedExactPath(String value, String label)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        try {
            Path raw = Path.of(requireText(value, label));
            Path normalized = raw.toAbsolutePath().normalize();
            if (!raw.isAbsolute() || !raw.equals(normalized)) {
                throw indeterminate(label + " path is not absolute and normalized");
            }
            return normalized;
        }
        catch (RuntimeException failure) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    label + " path is invalid", failure);
        }
    }

    private static void requireAbsent(ProvisioningGit.Probe probe)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        if (probe.state() != ProvisioningGit.ProbeState.ABSENT) {
            throw indeterminate(
                    "provisioning target conflicts with frozen identity: " + probe.detail());
        }
    }

    private static void requireExactHead(ProvisioningGit.Probe probe, String expected)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        if (probe.state() != ProvisioningGit.ProbeState.EXACT
                || !Objects.equals(expected, probe.headSha())) {
            throw indeterminate("provisioning target HEAD is not exact");
        }
    }

    private static void cancelBeforeMutation(ExecutionContext context)
            throws ExecutionPorts.OperationCanceledException
    {
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "provisioning canceled before Git mutation");
        }
    }

    private static ExecutionPorts.IndeterminateExecutionException indeterminate(
            String message)
    {
        return new ExecutionPorts.IndeterminateExecutionException(message);
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    public interface OperationStore
    {
        ProvisionRequest requireByOperationId(String operationId);
    }

    public interface ProvisioningGit
    {
        Probe probe(MutationTarget target);

        String requireExactRemote(Path repositoryRoot, String repositoryId);

        Optional<String> resolveCommit(Path repositoryRoot, String ref);

        Optional<String> resolveRemoteBranch(
                Path repositoryRoot, String remote, String branch);

        void fetchRemote(
                MutationTarget target,
                String remote,
                WorktreeWriterLeaseManager.MutationFence fence);

        void createWorktree(
                MutationTarget target,
                String headSha,
                WorktreeWriterLeaseManager.MutationFence fence);

        String codeFingerprint(MutationTarget target);

        enum ProbeState
        {
            ABSENT,
            EXACT,
            CONFLICT
        }

        record Probe(ProbeState state, String headSha, String detail)
        {
            public Probe
            {
                requireNonNull(state, "state is null");
                if ((state == ProbeState.EXACT) != (headSha != null)) {
                    throw new IllegalArgumentException(
                            "only an exact probe carries HEAD");
                }
            }

            public static Probe absent()
            {
                return new Probe(ProbeState.ABSENT, null, null);
            }

            public static Probe exact(String headSha)
            {
                return new Probe(
                        ProbeState.EXACT, requireText(headSha, "headSha"), null);
            }

            public static Probe conflict(String detail)
            {
                return new Probe(
                        ProbeState.CONFLICT, null, requireText(detail, "detail"));
            }
        }

        final class MutationFailure
                extends RuntimeException
        {
            public MutationFailure(String message, Throwable cause)
            {
                super(requireText(message, "message"), requireNonNull(cause, "cause is null"));
            }
        }
    }

    public enum BaseSource
    {
        PLANNING_SNAPSHOT,
        FRESH_REMOTE_BASE,
        EXISTING_PR_HEAD
    }

    public record MutationTarget(
            String taskId,
            long taskEpoch,
            String operationId,
            Path repositoryRoot,
            String branchName,
            Path worktreePath)
    {
        public MutationTarget
        {
            requireText(taskId, "taskId");
            requireText(operationId, "operationId");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireText(branchName, "branchName");
            requireNonNull(worktreePath, "worktreePath is null");
            if (taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
        }
    }

    public record ProvisionRequest(
            String operationRowId,
            String taskId,
            String trunkId,
            String workspaceId,
            String workflowVersion,
            String taskLifecycle,
            long taskEpoch,
            String taskAssignmentId,
            String operationAssignmentId,
            String operationId,
            int attempt,
            String repositoryId,
            BaseSource baseSource,
            String baseRepositoryId,
            String baseRef,
            String expectedBaseSha,
            String expectedHeadSha,
            String branchName,
            String worktreePath,
            String operationStatus,
            String contextAssignmentId,
            String contextRepositoryId,
            String contextUpstreamRepositoryId,
            String contextPublishRepositoryId,
            BaseSource contextBaseSource,
            String contextBaseRepositoryId,
            String contextBaseRef,
            String contextPlanningBaseSha,
            String contextAssignmentBaseSha,
            String contextAssignmentHeadSha,
            String targetRepositoryId,
            String targetPublishRepositoryId,
            String targetBranchName,
            String targetWorktreePath,
            String assignmentKind,
            String assignmentRepositoryId,
            String assignmentPlanningBaseSha,
            String assignmentBaseRepositoryId,
            String assignmentHeadRepositoryId,
            String assignmentBaseRef,
            String headRef,
            String assignmentRemoteBaseSha,
            String assignmentRemoteHeadSha,
            String localClonePath)
    {
        public ProvisionRequest
        {
            requireText(operationRowId, "operationRowId");
            requireText(taskId, "taskId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(workflowVersion, "workflowVersion");
            requireText(taskLifecycle, "taskLifecycle");
            requireText(taskAssignmentId, "taskAssignmentId");
            requireText(operationAssignmentId, "operationAssignmentId");
            requireText(operationId, "operationId");
            requireText(repositoryId, "repositoryId");
            requireNonNull(baseSource, "baseSource is null");
            requireText(baseRepositoryId, "baseRepositoryId");
            requireText(baseRef, "baseRef");
            requireText(branchName, "branchName");
            requireText(worktreePath, "worktreePath");
            requireText(operationStatus, "operationStatus");
            requireText(contextAssignmentId, "contextAssignmentId");
            requireText(contextRepositoryId, "contextRepositoryId");
            requireText(contextPublishRepositoryId, "contextPublishRepositoryId");
            requireNonNull(contextBaseSource, "contextBaseSource is null");
            requireText(contextBaseRepositoryId, "contextBaseRepositoryId");
            requireText(contextBaseRef, "contextBaseRef");
            requireText(targetRepositoryId, "targetRepositoryId");
            requireText(targetPublishRepositoryId, "targetPublishRepositoryId");
            requireText(targetBranchName, "targetBranchName");
            requireText(targetWorktreePath, "targetWorktreePath");
            requireText(assignmentKind, "assignmentKind");
            requireText(localClonePath, "localClonePath");
            if (taskEpoch < 1 || attempt < 1) {
                throw new IllegalArgumentException(
                        "Task epoch and operation attempt must be positive");
            }
        }
    }

    public record ProvisionEvidence(
            String schema,
            String operationId,
            String taskId,
            long taskEpoch,
            BaseSource baseSource,
            String repositoryId,
            String branchName,
            String worktreePath,
            String baseSha,
            String headSha,
            String codeFingerprint) {}

    private record ResolvedSource(String baseSha, String headSha)
    {
        private ResolvedSource
        {
            requireText(baseSha, "baseSha");
            requireText(headSha, "headSha");
        }
    }
}
