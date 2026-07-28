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
package com.bytequay.app.developmentflow.task.creation;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.BaseSource.EXISTING_PR_HEAD;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.BaseSource.FRESH_REMOTE_BASE;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.BaseSource.PLANNING_SNAPSHOT;
import static java.util.Objects.requireNonNull;

/** Frozen inputs consumed by one V2 Task-creation command. */
public record TaskCreationInput(
        String workspaceId,
        TaskAssignment assignment,
        TaskPolicy policy,
        CreationBase base,
        EngineSnapshot engine,
        WorkModelSnapshot workModel,
        Presentation presentation,
        Instant createdAt)
{
    public TaskCreationInput
    {
        requireText(workspaceId, "workspaceId");
        requireNonNull(assignment, "assignment is null");
        requireNonNull(policy, "policy is null");
        requireNonNull(base, "base is null");
        requireNonNull(engine, "engine is null");
        requireNonNull(workModel, "workModel is null");
        requireNonNull(presentation, "presentation is null");
        requireNonNull(createdAt, "createdAt is null");
        if (!assignment.identity().trunkId().equals(policy.trunkId())) {
            throw new IllegalArgumentException(
                    "policy Trunk does not match assignment Trunk");
        }
        if (assignment.baseSource() != base.source()) {
            throw new IllegalArgumentException(
                    "creation base does not match assignment provenance");
        }
        requireExactAssignmentBase(assignment, base);
    }

    /** Compatibility constructor for protocol tests and older internal callers. */
    public TaskCreationInput(
            String workspaceId,
            TaskAssignment assignment,
            TaskPolicy policy,
            CreationBase base,
            EngineSnapshot engine,
            WorkModelSnapshot workModel,
            Instant createdAt)
    {
        this(workspaceId, assignment, policy, base, engine, workModel,
                Presentation.from(assignment), createdAt);
    }

    public TaskAssignment.CreationProvenance provenance()
    {
        return assignment.provenance();
    }

    private static void requireExactAssignmentBase(
            TaskAssignment assignment, CreationBase base)
    {
        switch (assignment) {
            case TaskAssignment.NewFromTrunk newTask -> {
                if (newTask.origin() instanceof TaskAssignment.AgentHandoff origin
                        && (!(base instanceof PlanningSnapshot snapshot)
                        || !origin.planningBaseSha().equals(snapshot.baseSha()))) {
                    throw new IllegalArgumentException(
                            "planning base does not match the approved Trunk snapshot");
                }
            }
            case TaskAssignment.ExistingOwnPr existing ->
                    requireSamePullRequest(existing.pullRequest(), base);
            case TaskAssignment.ReviewFindings review ->
                    requireSamePullRequest(review.pullRequest(), base);
            case TaskAssignment.Issue ignored -> requireFresh(base);
            case TaskAssignment.Automation ignored -> requireFresh(base);
            case TaskAssignment.QualityScan ignored -> requireFresh(base);
        }
    }

    private static void requireSamePullRequest(
            TaskAssignment.PullRequestRef expected, CreationBase base)
    {
        if (!(base instanceof ExistingPrHead existing)
                || !expected.equals(existing.pullRequest())) {
            throw new IllegalArgumentException(
                    "existing-PR base does not match the assignment head");
        }
    }

    private static void requireFresh(CreationBase base)
    {
        if (!(base instanceof FreshRemoteBase)) {
            throw new IllegalArgumentException("assignment requires a fresh remote base");
        }
    }

    public sealed interface CreationBase
            permits PlanningSnapshot, FreshRemoteBase, ExistingPrHead
    {
        TaskAssignment.BaseSource source();

        TaskAssignment.RepositoryRouting repositories();

        String baseRef();
    }

    public record PlanningSnapshot(
            TaskAssignment.RepositoryRouting repositories,
            String baseRef,
            String baseSha)
            implements CreationBase
    {
        public PlanningSnapshot
        {
            requireNonNull(repositories, "repositories is null");
            requireText(baseRef, "baseRef");
            requireText(baseSha, "baseSha");
        }

        @Override
        public TaskAssignment.BaseSource source()
        {
            return PLANNING_SNAPSHOT;
        }
    }

    public record FreshRemoteBase(
            TaskAssignment.RepositoryRouting repositories,
            String baseRef)
            implements CreationBase
    {
        public FreshRemoteBase
        {
            requireNonNull(repositories, "repositories is null");
            requireText(baseRef, "baseRef");
        }

        @Override
        public TaskAssignment.BaseSource source()
        {
            return FRESH_REMOTE_BASE;
        }
    }

    public record ExistingPrHead(TaskAssignment.PullRequestRef pullRequest)
            implements CreationBase
    {
        public ExistingPrHead
        {
            requireNonNull(pullRequest, "pullRequest is null");
        }

        @Override
        public TaskAssignment.BaseSource source()
        {
            return EXISTING_PR_HEAD;
        }

        @Override
        public TaskAssignment.RepositoryRouting repositories()
        {
            return pullRequest.repositories();
        }

        @Override
        public String baseRef()
        {
            return pullRequest.baseRef();
        }
    }

    public record TaskPolicy(
            String id,
            String trunkId,
            int revision,
            String source,
            boolean autoApprove,
            boolean autoMerge,
            int minApprovals,
            int maxBrainRounds,
            int maxCiFixPushes,
            boolean requireRemoteBranchCleanup,
            Optional<String> permissionPolicyRef,
            String createdBy,
            Instant createdAt)
    {
        public TaskPolicy
        {
            requireText(id, "id");
            requireText(trunkId, "trunkId");
            if (revision <= 0) {
                throw new IllegalArgumentException("revision must be positive");
            }
            requireText(source, "source");
            if (minApprovals < 0 || maxBrainRounds < 0 || maxCiFixPushes < 0) {
                throw new IllegalArgumentException("policy limits must be non-negative");
            }
            if (autoMerge && !autoApprove) {
                throw new IllegalArgumentException("autoMerge requires autoApprove");
            }
            requireNonNull(permissionPolicyRef, "permissionPolicyRef is null");
            permissionPolicyRef.ifPresent(
                    value -> requireText(value, "permissionPolicyRef value"));
            requireText(createdBy, "createdBy");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record EngineSnapshot(String provider, String model, String value)
    {
        public EngineSnapshot
        {
            requireText(provider, "provider");
            requireText(model, "model");
            requireText(value, "value");
        }

        /** Unambiguous storage value for the complete frozen engine identity. */
        public String canonicalValue()
        {
            return encode(provider) + encode(model) + encode(value);
        }

        private static String encode(String part)
        {
            return part.length() + ":" + part;
        }
    }

    public record WorkModelSnapshot(String value)
    {
        public WorkModelSnapshot
        {
            requireText(value, "value");
        }
    }

    /** Immutable user-facing identity copied onto the compatibility Task row. */
    public record Presentation(
            String name,
            String taskType,
            Integer linkedIssueNumber,
            String openingPrompt,
            String origin)
    {
        public Presentation
        {
            requireText(name, "name");
            requireText(taskType, "taskType");
            if (linkedIssueNumber != null && linkedIssueNumber < 1) {
                throw new IllegalArgumentException(
                        "linkedIssueNumber must be positive");
            }
            if (openingPrompt != null && openingPrompt.isBlank()) {
                openingPrompt = null;
            }
            requireText(origin, "origin");
        }

        private static Presentation from(TaskAssignment assignment)
        {
            String name;
            String taskType = "DEVELOP";
            Integer issue = null;
            String prompt = null;
            String origin = assignment.provenance().name().toLowerCase(Locale.ROOT);
            switch (assignment) {
                case TaskAssignment.NewFromTrunk value -> {
                    name = value.planSeed();
                    prompt = value.prompt();
                }
                case TaskAssignment.ExistingOwnPr value -> {
                    name = "Work on PR #" + value.pullRequest().number();
                    taskType = "REVIEW";
                }
                case TaskAssignment.ReviewFindings value -> {
                    name = "Address review findings";
                    taskType = "REVIEW";
                }
                case TaskAssignment.Issue value -> {
                    name = "Work on " + value.issueIdentity();
                    issue = parseIssueNumber(value.issueIdentity());
                }
                case TaskAssignment.Automation value ->
                        name = value.reason();
                case TaskAssignment.QualityScan value ->
                        name = "Address quality finding " + value.evidenceIdentity();
            }
            return new Presentation(name, taskType, issue, prompt, origin);
        }

        private static Integer parseIssueNumber(String issueId)
        {
            int hash = issueId == null ? -1 : issueId.lastIndexOf('#');
            if (hash < 0 || hash == issueId.length() - 1) {
                return null;
            }
            try {
                int value = Integer.parseInt(issueId.substring(hash + 1));
                return value > 0 ? value : null;
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
