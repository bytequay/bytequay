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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowCanaryRoute;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.review.ReviewBuildSelectionStore;
import com.bytequay.app.service.review.ReviewBuildSpawnService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.WorkspaceRelationService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Production adapter from the existing Task-create request to one exact V2 command. */
@Component
public final class V2TaskCreationService
{
    private static final int CONCURRENT_CREATION_ATTEMPTS = 8;

    private final DevelopmentFlowCanaryRoute route;
    private final TaskCreationHandoff handoff;
    private final JdbcTemplate jdbc;
    private final ThreadStore threads;
    private final TaskStore tasks;
    private final ThreadEngineOverrides engines;
    private final WorkspaceRepositoryResolver repositories;
    private final WorkspaceRelationService relations;
    private final PullRequestRepository pullRequests;
    private final PatResolver pats;
    private final ReviewBuildSelectionStore reviewSelections;
    private final ObjectMapper json;
    private final V2DevelopmentFlowProjection projection;

    public V2TaskCreationService(
            DevelopmentFlowCanaryRoute route,
            TaskCreationHandoff handoff,
            JdbcTemplate jdbc,
            ThreadStore threads,
            TaskStore tasks,
            ThreadEngineOverrides engines,
            WorkspaceRepositoryResolver repositories,
            WorkspaceRelationService relations,
            PullRequestRepository pullRequests,
            PatResolver pats,
            ReviewBuildSelectionStore reviewSelections,
            ObjectMapper json,
            V2DevelopmentFlowProjection projection)
    {
        this.route = requireNonNull(route, "route is null");
        this.handoff = requireNonNull(handoff, "handoff is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.relations = requireNonNull(relations, "relations is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.reviewSelections = requireNonNull(
                reviewSelections, "reviewSelections is null");
        this.json = requireNonNull(json, "json is null");
        this.projection = requireNonNull(projection, "projection is null");
    }

    public boolean routes(String workspaceId)
    {
        return route.routesNewTaskToV2(workspaceId);
    }

    /** Switches only a quiescent Trunk. The database guard is the final authority. */
    public void prepareTrunk(String trunkId, String workspaceId)
    {
        requireText(trunkId, "trunkId");
        requireText(workspaceId, "workspaceId");
        if (!routes(workspaceId)) {
            return;
        }
        int changed = jdbc.update("""
                UPDATE threads
                SET lifecycle_state = COALESCE(lifecycle_state, 'ACTIVE'),
                    turn_version = 'V2'
                WHERE id = ? AND workspace_id = ? AND turn_version = 'LEGACY'
                  AND NOT EXISTS (
                      SELECT 1 FROM thread_turns legacy
                      WHERE legacy.thread_id = threads.id
                        AND legacy.status IN ('QUEUED', 'RUNNING'))
                  AND NOT EXISTS (
                      SELECT 1 FROM thread_turn typed
                      WHERE typed.trunk_id = threads.id
                        AND typed.status IN (
                            'REQUESTED','QUEUED','CLAIMED','RUNNING'))
                """, trunkId, workspaceId);
        Integer ready = jdbc.queryForObject("""
                SELECT COUNT(*) FROM threads
                WHERE id = ? AND workspace_id = ? AND turn_version = 'V2'
                  AND lifecycle_state IN ('ACTIVE','IDLE')
                """, Integer.class, trunkId, workspaceId);
        if ((ready == null || ready != 1) && changed != 1) {
            throw new IllegalStateException(
                    "Trunk must be quiescent before V2 Task creation");
        }
    }

    public Task create(
            Thread trunk,
            ThreadService.NewTaskRequest request)
    {
        requireNonNull(trunk, "trunk is null");
        requireNonNull(request, "request is null");
        requireText(trunk.id(), "trunk.id");
        if (!trunk.workspaceId().equals(request.workspaceId())) {
            throw new IllegalArgumentException(
                    "Task request does not belong to its Trunk Workspace");
        }
        Path repositoryRoot = Path.of(requireText(request.workingDir(), "workingDir"))
                .toAbsolutePath().normalize();
        if (!isV2Trunk(trunk.id(), trunk.workspaceId())) {
            if (!routes(trunk.workspaceId())) {
                throw new IllegalStateException("Trunk is not routed to V2");
            }
            prepareTrunk(trunk.id(), trunk.workspaceId());
        }

        for (int attempt = 1; attempt <= CONCURRENT_CREATION_ATTEMPTS; attempt++) {
            try {
                return createAttempt(trunk, request, repositoryRoot);
            }
            catch (CommandRejectedException failure) {
                if (!isConcurrent(failure)
                        || attempt == CONCURRENT_CREATION_ATTEMPTS) {
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("unreachable Task creation retry state");
    }

    private boolean isV2Trunk(String trunkId, String workspaceId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM threads
                WHERE id = ? AND workspace_id = ? AND turn_version = 'V2'
                """, Integer.class, trunkId, workspaceId);
        return count != null && count == 1;
    }

    private Task createAttempt(
            Thread trunk,
            ThreadService.NewTaskRequest request,
            Path repositoryRoot)
    {
        Instant now = Instant.now();
        String commandId = id("create", trunk.id() + ":" + UUID.randomUUID());
        String assignmentId = id("assignment", commandId);
        String authorizationId = id("authorization", commandId);
        String actor = actor(request.origin());
        TaskAssignment.Identity identity = new TaskAssignment.Identity(
                assignmentId, trunk.id(), authorizationId, actor, now);
        AssignmentAndBase exact = assignment(
                trunk, request, identity, repositoryRoot);
        WorkModel model = engines.forAudience(trunk.id(), SessionAudience.PLAN)
                .orElseGet(() -> requireNonNull(
                        request.workModel(), "Trunk Plan engine snapshot is missing"));
        String modelName = requireText(model.model(), "Plan engine model");
        String provider = requireText(model.agentOrProvider(), "Plan engine provider");
        String frozenModel = write(model);
        int policyRevision = nextPolicyRevision(trunk.id());
        TaskCreationInput.TaskPolicy policy = new TaskCreationInput.TaskPolicy(
                id("policy", commandId), trunk.id(), policyRevision,
                "TASK_CREATION", false, false, 0, 3, 3, false,
                Optional.empty(), actor, now);
        TaskCreationInput input = new TaskCreationInput(
                trunk.workspaceId(), exact.assignment(), policy, exact.base(),
                new TaskCreationInput.EngineSnapshot(
                        provider, modelName, frozenModel),
                new TaskCreationInput.WorkModelSnapshot(frozenModel),
                new TaskCreationInput.Presentation(
                        displayName(request, trunk), taskType(request),
                        request.linkedIssueNumber(), prompt(request),
                        requireText(request.origin(), "origin")),
                now);
        long trunkVersion = requireTrunkVersion(trunk.id());
        TaskCreationHandoff.Result created = handoff.create(
                new TaskCreationHandoff.Command(
                        new TrunkManager.TaskCreationCommand(
                                commandId, actor, trunkVersion, input),
                        repositoryRoot));
        Task raw = tasks.findTaskById(created.task().task().id())
                .orElseThrow(() -> new IllegalStateException(
                        "Created V2 Task is not readable"));
        return projection.project(raw);
    }

    private AssignmentAndBase assignment(
            Thread trunk,
            ThreadService.NewTaskRequest request,
            TaskAssignment.Identity identity,
            Path repositoryRoot)
    {
        if (request.linkedPrNumber() != null) {
            return existingPullRequest(trunk, request, identity);
        }
        TaskAssignment.RepositoryRouting routing = workspaceRouting(
                trunk.workspaceId());
        String baseRef = baseRef(trunk.workspaceId());
        if (Task.ORIGIN_ISSUE_MONITOR.equals(request.origin())) {
            String issue = routing.repositoryId() + "#"
                    + requirePositive(request.linkedIssueNumber(), "linkedIssueNumber");
            return new AssignmentAndBase(
                    new TaskAssignment.Issue(identity, issue),
                    new TaskCreationInput.FreshRemoteBase(routing, baseRef));
        }
        if (Task.ORIGIN_QUALITY_SCAN.equals(request.origin())) {
            String evidence = id("quality-evidence",
                    trunk.id() + ":" + prompt(request));
            return new AssignmentAndBase(
                    new TaskAssignment.QualityScan(identity, evidence),
                    new TaskCreationInput.FreshRemoteBase(routing, baseRef));
        }
        if (Task.ORIGIN_AUTOMATION.equals(request.origin())) {
            return new AssignmentAndBase(
                    new TaskAssignment.Automation(
                            identity, "bytequay", prompt(request)),
                    new TaskCreationInput.FreshRemoteBase(routing, baseRef));
        }
        String seed = planSeed(request, trunk);
        if (Task.ORIGIN_AGENT.equals(request.origin())) {
            ThreadStore.PlanningSnapshot snapshot = threads
                    .findPlanningSnapshot(trunk.id())
                    .filter(value -> repositoryRoot.toString().equals(value.repoRoot()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Agent Task handoff requires the exact Trunk planning snapshot"));
            return new AssignmentAndBase(
                    new TaskAssignment.NewFromTrunk(
                            identity,
                            new TaskAssignment.AgentHandoff(snapshot.baseSha()),
                            seed, prompt(request)),
                    new TaskCreationInput.PlanningSnapshot(
                            routing, baseRef, snapshot.baseSha()));
        }
        return new AssignmentAndBase(
                new TaskAssignment.NewFromTrunk(
                        identity, new TaskAssignment.DirectUser(),
                        seed, prompt(request)),
                new TaskCreationInput.FreshRemoteBase(routing, baseRef));
    }

    private AssignmentAndBase existingPullRequest(
            Thread trunk,
            ThreadService.NewTaskRequest request,
            TaskAssignment.Identity identity)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity workspace =
                repositories.resolve(trunk.workspaceId());
        int number = requirePositive(request.linkedPrNumber(), "linkedPrNumber");
        ReviewBuildSelectionStore.Selection reviewSelection =
                reviewSelections.find(trunk.id()).orElse(null);
        if (trunk.parentReviewPassId() == null && reviewSelection != null) {
            throw new IllegalStateException(
                    "review build selection has no parent ReviewSession");
        }
        if (trunk.parentReviewPassId() != null && reviewSelection == null) {
            throw new IllegalStateException(
                    "review build Task is missing its frozen finding selection");
        }
        if (reviewSelection != null
                && ReviewBuildSpawnService.MODE_SUGGESTED.equals(
                reviewSelection.spawn().mode())) {
            throw new IllegalStateException(
                    "suggested-change review builds are comment-only and cannot "
                            + "materialize a writable V2 Task");
        }
        if (reviewSelection != null
                && !reviewSelections.matchesCurrent(reviewSelection)) {
            throw new IllegalStateException(
                    "review build selection changed before V2 Task materialization");
        }
        String prRepository = reviewSelection == null
                ? workspace.fullName()
                : reviewSelection.spawn().baseRepositoryId();
        String[] prParts = repositoryParts(prRepository);
        PullRequestRef ref = PullRequestRef.of(
                prParts[0], prParts[1], number);
        PrRawDetail raw = pullRequests.fetchPrDetail(
                pats.resolve(prRepository), ref);
        requireText(raw.headSha(), "PR head SHA");
        requireText(raw.baseSha(), "PR base SHA");
        requireText(raw.headRef(), "PR head ref");
        requireText(raw.baseRef(), "PR base ref");
        String headRepo = requireText(raw.headRepo(), "PR head repository");
        String baseRepo = requireText(raw.baseRepo(), "PR base repository");
        if (reviewSelection == null
                && !workspace.fullName().equalsIgnoreCase(headRepo)) {
            throw new IllegalStateException(
                    "V2 existing-PR Task requires the Workspace-owned head repository");
        }
        TaskAssignment.RepositoryRouting routing;
        if (reviewSelection == null) {
            routing = baseRepo.equalsIgnoreCase(headRepo)
                    ? new TaskAssignment.Direct(headRepo)
                    : new TaskAssignment.Fork(baseRepo, headRepo);
        }
        else {
            routing = workspaceRouting(trunk.workspaceId());
            if (!ReviewBuildSpawnService.MODE_AUTHOR.equals(
                    reviewSelection.spawn().mode())
                    || !routing.baseRepositoryId().equalsIgnoreCase(
                    reviewSelection.spawn().baseRepositoryId())
                    || !routing.publishRepositoryId().equalsIgnoreCase(
                    reviewSelection.spawn().headRepositoryId())) {
                throw new IllegalStateException(
                        "review build Workspace route cannot write the frozen PR head");
            }
        }
        TaskAssignment.PullRequestRef exact = new TaskAssignment.PullRequestRef(
                routing, number, raw.baseRef(), raw.headRef(),
                raw.baseSha(), raw.headSha());
        TaskAssignment assignment;
        if (reviewSelection == null) {
            assignment = new TaskAssignment.ExistingOwnPr(identity, exact);
        }
        else {
            if (!trunk.parentReviewPassId().equals(
                    reviewSelection.reviewPassId())
                    || reviewSelection.prNumber() != number
                    || !reviewSelection.repoFullName().equalsIgnoreCase(baseRepo)
                    || !reviewSelection.reviewedHeadSha().equals(raw.headSha())
                    || !reviewSelection.spawn().baseRepositoryId()
                    .equalsIgnoreCase(baseRepo)
                    || !reviewSelection.spawn().headRepositoryId()
                    .equalsIgnoreCase(headRepo)
                    || !reviewSelection.spawn().baseRef().equals(raw.baseRef())
                    || !reviewSelection.spawn().headRef().equals(raw.headRef())) {
                throw new IllegalStateException(
                        "review build selection is stale or names another PR");
            }
            assignment = new TaskAssignment.ReviewFindings(
                    identity, reviewSelection.reviewPassId(), exact,
                    reviewSelection.findings().stream()
                            .map(finding -> new TaskAssignment.ReviewFindingRef(
                                    finding.reviewPassId(), finding.findingId(),
                                    finding.findingRevision(),
                                    finding.contentDigest()))
                            .toList());
        }
        return new AssignmentAndBase(
                assignment, new TaskCreationInput.ExistingPrHead(exact));
    }

    private TaskAssignment.RepositoryRouting workspaceRouting(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity target =
                repositories.resolve(workspaceId);
        return relations.find(workspaceId)
                .map(ignored -> relations.requireResolved(workspaceId))
                .<TaskAssignment.RepositoryRouting>map(resolved ->
                        new TaskAssignment.Fork(
                                resolved.upstream().fullName(),
                                resolved.target().fullName()))
                .orElseGet(() -> new TaskAssignment.Direct(target.fullName()));
    }

    private String baseRef(String workspaceId)
    {
        return relations.find(workspaceId)
                .map(ignored -> relations.requireResolved(workspaceId)
                        .upstream().defaultBaseBranch())
                .orElseGet(() -> repositories.resolve(workspaceId)
                        .defaultBaseBranch());
    }

    private static String[] repositoryParts(String repository)
    {
        String[] parts = requireText(repository, "repository").split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalStateException(
                    "review build repository identity is invalid: " + repository);
        }
        return parts;
    }

    private long requireTrunkVersion(String trunkId)
    {
        Long value = jdbc.queryForObject("""
                SELECT aggregate_version FROM threads
                WHERE id = ? AND turn_version = 'V2'
                  AND lifecycle_state IN ('ACTIVE','IDLE')
                """, Long.class, trunkId);
        return requireNonNull(value, "V2 Trunk version is missing");
    }

    private int nextPolicyRevision(String trunkId)
    {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM task_policy_revision WHERE trunk_id = ?
                """, Integer.class, trunkId);
        return requireNonNull(value, "next Task policy revision is missing");
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not freeze Task creation input", e);
        }
    }

    private String planSeed(ThreadService.NewTaskRequest request, Thread trunk)
    {
        return request.trunkPlan() == null
                ? displayName(request, trunk)
                : write(request.trunkPlan());
    }

    private static boolean isConcurrent(CommandRejectedException failure)
    {
        return failure.reason() == CommandRejectedException.Reason.STALE_VERSION
                || failure.reason()
                        == CommandRejectedException.Reason.CONCURRENT_UPDATE;
    }

    private static String displayName(
            ThreadService.NewTaskRequest request, Thread trunk)
    {
        if (request.title() != null && !request.title().isBlank()) {
            return request.title().strip();
        }
        if (trunk != null && trunk.title() != null && !trunk.title().isBlank()) {
            return trunk.title().strip();
        }
        return "Development task";
    }

    private static String taskType(ThreadService.NewTaskRequest request)
    {
        return request.taskType() == null || request.taskType().isBlank()
                ? "DEVELOP" : request.taskType().strip();
    }

    private static String prompt(ThreadService.NewTaskRequest request)
    {
        if (request.initialPrompt() != null && !request.initialPrompt().isBlank()) {
            return request.initialPrompt().strip();
        }
        if (request.description() != null && !request.description().isBlank()) {
            return request.description().strip();
        }
        return displayName(request, null);
    }

    private static String actor(String origin)
    {
        return switch (requireText(origin, "origin")) {
            case Task.ORIGIN_AGENT -> "trunk-agent";
            case Task.ORIGIN_AUTOMATION, Task.ORIGIN_ISSUE_MONITOR,
                    Task.ORIGIN_QUALITY_SCAN -> origin;
            default -> "user";
        };
    }

    private static int requirePositive(Integer value, String name)
    {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String id(String namespace, String value)
    {
        return UUID.nameUUIDFromBytes(
                ("bytequay-v2-production:" + namespace + ":" + value)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private record AssignmentAndBase(
            TaskAssignment assignment,
            TaskCreationInput.CreationBase base)
    {
        private AssignmentAndBase
        {
            requireNonNull(assignment, "assignment is null");
            requireNonNull(base, "base is null");
        }
    }
}
