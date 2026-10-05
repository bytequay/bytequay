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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Saves request links; task, turn, worktree and delivery services remain the execution authority. */
@Service
public class RelatedWorkspaceWork
{
    private static final String PREFIX = "workspace.linked-work.";
    private static final String SOURCE = "linked-work.source.";
    private static final DevelopmentAutomation INVESTIGATION = new DevelopmentAutomation(true, false, false, Optional.empty(), false);
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final WorkspaceRelationships relationships;
    private final WorkspaceRepositoryService repositories;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentApprovalStore approvals;
    private final TaskLogStore logs;
    private final AgentReviewStore reviews;
    private final InteractiveAgentTaskCreator creator;
    private final AgentTurnService turnService;
    private final AgentTaskService taskService;
    private final ModelSettingsService models;
    private final AppSettingsService appSettings;
    private final GitCommandRunner git;
    private final Striped<Lock> requests = Striped.lock(64);

    public RelatedWorkspaceWork(AppSettingsStore settings, ObjectMapper mapper, WorkspaceRelationships relationships,
            WorkspaceRepositoryService repositories, AgentTaskStore tasks, AgentTurnStore turns, AgentApprovalStore approvals,
            TaskLogStore logs, AgentReviewStore reviews, InteractiveAgentTaskCreator creator, AgentTurnService turnService,
            AgentTaskService taskService, ModelSettingsService models, AppSettingsService appSettings, GitCommandRunner git)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.relationships = requireNonNull(relationships, "relationships is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.logs = requireNonNull(logs, "logs is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.creator = requireNonNull(creator, "creator is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.models = requireNonNull(models, "models is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.git = requireNonNull(git, "git is null");
    }

    public enum Purpose { INVESTIGATE, FIX }

    public record Request(String operationKey, String workspaceId, Purpose purpose, String question, Optional<String> commit, Optional<String> revision)
    {
        public Request
        {
            checkNotBlank(operationKey, "operationKey");
            checkNotBlank(workspaceId, "workspaceId");
            checkNotBlank(question, "question");
            checkArgument(operationKey.length() <= 100 && workspaceId.length() <= 100 && question.length() <= 16_000, "Request is too long");
            requireNonNull(purpose, "purpose is null");
            requireNonNull(commit, "commit is null");
            requireNonNull(revision, "revision is null");
            checkArgument((purpose == Purpose.INVESTIGATE) == commit.isPresent(), "Investigation requires an exact commit; fixes use the normal development branch");
            commit.ifPresent(value -> checkArgument(value.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Commit must be a full SHA"));
            revision.ifPresent(value -> checkArgument(!value.isBlank() && value.length() <= 200, "Revision label must contain 1 to 200 characters"));
        }
    }

    public record Link(String sourceTaskId, String sourceTurnId, String targetTaskId, Request request, String repository,
            WorkspaceRelationships.Kind kind, AgentLaneId laneId, AgentTaskDefaults defaults, Instant createdAt, Optional<String> failure)
    {
        Link withFailure(Optional<String> failure)
        {
            return new Link(sourceTaskId, sourceTurnId, targetTaskId, request, repository, kind, laneId, defaults, createdAt, failure);
        }
    }

    public record View(String sourceWorkspaceId, String sourceRepository, String sourceTaskId, String sourceTitle, String sourceTurnId, String targetTaskId,
            String targetWorkspaceId, String targetTitle, String repository, String kind, String purpose, String question,
            String revision, Optional<String> commit, String state, Optional<String> turnId, Optional<String> result, Optional<String> limitation,
            Instant createdAt, boolean archived) {}

    public Optional<AgentTask> eligible(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId)).filter(task -> task.interactive() && task.state() == TaskState.OPEN
                && task.workspaceId().isPresent() && task.worktreeRemovedAt().isEmpty() && task.localReviewHead().isEmpty())
                .filter(task -> reviews.findReviewByTask(taskId).isEmpty());
    }

    public View start(String sourceTaskId, TurnId acceptedTurn, Request request)
    {
        AgentTask source = requireTurn(sourceTaskId, acceptedTurn, true);
        String workspaceId = source.workspaceId().orElseThrow();
        Lock requestLock = requests.get(sourceTaskId + ":" + request.operationKey());
        requestLock.lock();
        try {
            Link link = WorkspaceOperationLocks.withLock(workspaceId, () -> {
                Optional<Link> saved = find(workspaceId, sourceTaskId, request.operationKey());
                if (saved.isPresent()) {
                    checkArgument(saved.orElseThrow().request().equals(request), "The request changed; choose a new operationKey");
                    return saved.orElseThrow();
                }
                var destination = relationships.requireRelated(workspaceId, request.workspaceId());
                var relationship = relationships.list(workspaceId).stream().filter(value -> value.workspaceId().equals(destination.id())).findFirst().orElseThrow();
                var model = models.agentModel(AgentModelType.DEVELOPMENT, Optional.of(destination.id()));
                var automation = request.purpose() == Purpose.INVESTIGATE ? INVESTIGATION : appSettings.developmentAutomation();
                var defaults = new AgentTaskDefaults(Optional.of(model.model()), model.effort(), AgentPermissionPolicy.AUTO).withDevelopmentAutomation(automation);
                Link created = new Link(sourceTaskId, acceptedTurn.toString(), TaskId.random().toString(), request,
                        destination.monitoredRepository().fullName(), relationship.kind(), new AgentLaneId(model.configId()), defaults, Instant.now(), Optional.empty());
                save(workspaceId, created);
                return created;
            });
            requireDestination(workspaceId, link);
            settings.put(SOURCE + link.targetTaskId(), workspaceId);
            String key = "related-work:" + link.targetTaskId();
            TaskId target = TaskId.valueOf(link.targetTaskId());
            if (turns.findByIdempotencyKey(target, key).isPresent()) {
                if (link.failure().isPresent()) {
                    save(workspaceId, link.withFailure(Optional.empty()));
                }
                return view(workspaceId, link.withFailure(Optional.empty()));
            }
            try {
                requireTurn(sourceTaskId, acceptedTurn, true);
                var destination = requireDestination(workspaceId, link);
                checkArgument(Files.exists(Path.of(destination.localRepositoryPath()).resolve(".git")), "Related workspace checkout is unavailable");
                if (request.commit().isPresent()) {
                    var local = repositories.requireLocalRepository(destination.id());
                    String sha = request.commit().orElseThrow();
                    var resolved = git.run(local.repository(), "rev-parse", "--verify", "--end-of-options", sha + "^{commit}");
                    checkArgument(resolved.successful() && !resolved.truncated() && resolved.output().strip().equals(sha),
                            "Investigation commit is unavailable locally; synchronize the related workspace, then retry");
                }
                String message = message(link);
                creator.createRelated(target, request.workspaceId(), message, link.laneId(), link.defaults(), key, request.commit());
                requireTurn(sourceTaskId, acceptedTurn, true);
                requireDestination(workspaceId, link);
                turnService.start(StartAgentTurnCommand.trustedInitialInteractiveTurn(target, message, ImmutableList.of(), key)
                        .withDevelopmentAutomation(link.defaults().developmentAutomation()));
                save(workspaceId, link.withFailure(Optional.empty()));
            }
            catch (RuntimeException failure) {
                save(workspaceId, link.withFailure(Optional.of(Optional.ofNullable(failure.getMessage()).orElse("Could not start related work"))));
            }
            return view(workspaceId, find(workspaceId, sourceTaskId, request.operationKey()).orElseThrow());
        }
        finally {
            requestLock.unlock();
        }
    }

    public View read(String sourceTaskId, TurnId acceptedTurn, String operationKey)
    {
        AgentTask source = requireTurn(sourceTaskId, acceptedTurn, false);
        String workspaceId = source.workspaceId().orElseThrow();
        Link link = find(workspaceId, sourceTaskId, operationKey).orElseThrow(() -> new IllegalArgumentException("No linked work for this request"));
        requireDestination(workspaceId, link);
        return view(workspaceId, link);
    }

    public List<View> list(String workspaceId)
    {
        repositories.getWorkspace(workspaceId);
        return links(workspaceId).stream().map(link -> view(workspaceId, link)).collect(toImmutableList());
    }

    public Optional<View> incoming(String workspaceId, String taskId)
    {
        taskService.getInteractive(workspaceId, TaskId.valueOf(taskId));
        return settings.get(SOURCE + taskId).flatMap(source -> links(source).stream().filter(link -> link.targetTaskId().equals(taskId))
                .findFirst().map(link -> view(source, link)));
    }

    private Workspace requireDestination(String workspaceId, Link link)
    {
        var destination = relationships.requireRelated(workspaceId, link.request().workspaceId());
        checkArgument(destination.monitoredRepository().fullName().equalsIgnoreCase(link.repository()), "Related repository routing changed");
        return destination;
    }

    private AgentTask requireTurn(String taskId, TurnId turnId, boolean start)
    {
        AgentTask task = eligible(taskId).orElseThrow(() -> new AgentConflictException("The source conversation is unavailable"));
        AgentTurn active = turns.findActive(task.id()).filter(turn -> turn.id().equals(turnId) && turn.cancellationRequestedAt().isEmpty())
                .orElseThrow(() -> new AgentConflictException("The requesting turn is no longer active"));
        checkArgument(!start || (active.developmentOrigin().isEmpty() && !active.idempotencyKey().equals("related-work:" + taskId)),
                "An automated turn cannot authorize new related work");
        return task;
    }

    private Optional<Link> find(String workspaceId, String sourceTaskId, String key)
    {
        return links(workspaceId).stream().filter(link -> link.sourceTaskId().equals(sourceTaskId) && link.request().operationKey().equals(key)).findFirst();
    }

    private List<Link> links(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                return ImmutableList.copyOf(mapper.readValue(json, new TypeReference<List<Link>>() {}));
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Cannot read linked work", failure);
            }
        }).orElse(ImmutableList.of());
    }

    private void save(String workspaceId, Link link)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            // ponytail: one list per workspace; move to indexed rows if its linked-work history becomes large.
            List<Link> next = ImmutableList.<Link>builder().addAll(links(workspaceId).stream()
                            .filter(value -> !value.targetTaskId().equals(link.targetTaskId())).collect(toImmutableList()))
                    .add(link).build();
            try {
                settings.put(PREFIX + workspaceId, mapper.writeValueAsString(next));
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Cannot save linked work", failure);
            }
            return null;
        });
    }

    private View view(String workspaceId, Link link)
    {
        Optional<AgentTask> target = tasks.find(TaskId.valueOf(link.targetTaskId()));
        Optional<AgentTask> source = tasks.find(TaskId.valueOf(link.sourceTaskId()));
        Optional<AgentTurn> turn = turns.findByIdempotencyKey(TaskId.valueOf(link.targetTaskId()), "related-work:" + link.targetTaskId());
        String state = turn.map(value -> switch (value.state()) {
            case ACCEPTED, RUNNING -> value.cancellationRequestedAt().isPresent() ? "STOPPING" : approvals.listPending(value.id()).isEmpty() ? "RUNNING" : "NEEDS_YOU";
            case COMPLETED -> "FINISHED";
            case CANCELLED -> "STOPPED";
            default -> "FAILED";
        }).orElse(link.failure().isPresent() ? "UNAVAILABLE" : "STARTING");
        Optional<String> result = turn.filter(value -> value.state() == TurnState.COMPLETED).flatMap(value -> logs.findLatest(value.taskId(), event ->
                        event.turnId().equals(Optional.of(value.id())) && event.rootAnswer().isPresent(), 10_000))
                .flatMap(AgentEventEnvelope::rootAnswer);
        Optional<String> limitation = link.failure().or(() -> turn.flatMap(AgentTurn::terminalReason));
        if (turn.isEmpty() && source.flatMap(task -> turns.findActive(task.id()))
                .filter(value -> value.id().toString().equals(link.sourceTurnId())).isEmpty()) {
            state = "UNAVAILABLE";
            limitation = limitation.or(() -> Optional.of("Requested work is unavailable. Open the source conversation to review or retry."));
        }
        if (state.equals("FINISHED") && result.isEmpty()) {
            limitation = Optional.of("Requested turn finished, but its answer is missing or outside retained history. Open the conversation.");
        }
        if (result.filter(text -> text.length() > 64_000).isPresent()) {
            result = result.map(text -> text.substring(0, 64_000));
            limitation = Optional.of("Answer shortened; open the destination conversation for the full result.");
        }
        return new View(workspaceId, source.map(task -> task.repository().toString()).orElse(workspaceId), link.sourceTaskId(),
                source.map(AgentTask::title).orElse("Unavailable conversation"), link.sourceTurnId(),
                target.map(task -> task.id().toString()).orElse(null), link.request().workspaceId(), target.map(AgentTask::title).orElse("Not started"),
                link.repository(), link.kind().name(), link.request().purpose().name(), link.request().question(), link.request().revision().orElse("default branch"),
                target.flatMap(AgentTask::taskWorktree).map(worktree -> worktree.startCommit()).or(() -> link.request().commit()), state,
                turn.map(value -> value.id().toString()), result, limitation, link.createdAt(), source.flatMap(AgentTask::archivedAt).isPresent());
    }

    private static String message(Link link)
    {
        return "ByteQuay related-work request: " + link.request().purpose() + "\n\n" + link.request().question()
                + link.request().commit().map(commit -> "\n\nInvestigate exactly " + commit
                        + ". Do not switch to another revision. PR, CI and review-summary automation are off.").orElse("")
                + "\n\nAnswer with the tested revision, finding and test outcomes."
                + " End with a Markdown heading '### Commands and limitations' followed by commands and limitations. Never claim unrun tests passed."
                + " This conversation receives only this focused request, not the requesting conversation's history or memory.";
    }
}
