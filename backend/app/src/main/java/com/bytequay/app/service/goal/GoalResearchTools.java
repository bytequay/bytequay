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
package com.bytequay.app.service.goal;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.context.ContextBuild;
import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.context.ContextBuildStore;
import com.bytequay.app.context.ContextText;
import com.bytequay.app.json.JsonContracts;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.store.GoalKnowledgeNoteStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The one tool of a Goal's research run: save a knowledge note that cites the pages and files it rests on. */
@Component
public class GoalResearchTools
{
    static final String SAVED = "goal-note:";
    static final int MAX_NOTES = 40;
    private static final ObjectMapper MAPPER = JsonContracts.mapper();
    private static final String POLICY = "This research run reads the repository, its dependency checkouts and documentation pages, and saves knowledge notes "
            + "through its tool only. It must not commit, push, comment on GitHub, change files, read credentials or start child agents.";

    private final ContextBuildStore builds;
    private final ContextBuildService service;
    private final GoalKnowledgeNoteStore notes;
    private final AgentTaskStore tasks;
    private final AgentUnitOfWork unitOfWork;
    private final Clock clock;

    public GoalResearchTools(ContextBuildStore builds, ContextBuildService service, GoalKnowledgeNoteStore notes, AgentTaskStore tasks, AgentUnitOfWork unitOfWork, Clock clock)
    {
        this.builds = requireNonNull(builds, "builds is null");
        this.service = requireNonNull(service, "service is null");
        this.notes = requireNonNull(notes, "notes is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<ReviewBridge> bridge(String taskId)
    {
        return builds.findByTask(taskId).filter(build -> build.research().isPresent())
                .flatMap(build -> tasks.find(TaskId.valueOf(taskId)).filter(task -> task.state() == TaskState.OPEN).map(this::bridge));
    }

    private ReviewBridge bridge(AgentTask task)
    {
        Optional<TurnId> turn = task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN)
                .map(claim -> TurnId.valueOf(claim.ownerId()));
        String taskId = task.id().toString();
        return new ReviewBridge(ImmutableList.of(new ReviewTool("goal_knowledge_save", "Save one knowledge note for the goal: a topic, a body in your own "
                        + "words, and the sources it rests on, each a page address with its #section or a file as owner/repo:path@commit at a commit this run reads.",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                                "topic", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 120),
                                "body", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2_000),
                                "sources", ImmutableMap.of("type", "array", "minItems", 1, "maxItems", GoalKnowledgeNote.MAX_SOURCES,
                                        "items", ImmutableMap.of("type", "string", "maxLength", 700))),
                        "required", ImmutableList.of("topic", "body", "sources"), "additionalProperties", false),
                arguments -> call(taskId, turn, arguments))), POLICY);
    }

    private Saved save(String taskId, Optional<TurnId> turn, SaveNote request)
    {
        ContextBuild authorized = service.authorize(taskId, turn);
        return WorkspaceOperationLocks.withLock(authorized.workspaceId(), () -> unitOfWork.execute(() -> {
            ContextBuild build = service.authorize(taskId, turn);
            checkArgument(build.research().isPresent(), "This run is not a research run");
            ContextBuild.Research research = build.research().orElseThrow();
            long saved = build.capture().attempts().keySet().stream().filter(key -> key.startsWith(SAVED)).count();
            checkArgument(saved < MAX_NOTES, "This run already saved %s notes; end the run", MAX_NOTES);
            List<GoalKnowledgeNote.Source> sources = request.sources().stream().map(value -> source(build, value)).collect(toImmutableList());
            GoalKnowledgeNote note = new GoalKnowledgeNote("note-" + UUID.randomUUID(), research.goalId(), build.workspaceId(), request.topic(), request.body(),
                    sources, build.taskId(), Instant.now(clock), Optional.empty());
            String line = "Saved note · " + ContextText.excerpt(note.topic(), 120);
            notes.save(note);
            builds.save(Optional.of(build), build.withCapture(build.capture().withAttempts(ImmutableMap.of(SAVED + note.id(), "1"))
                    .logged(Instant.now(clock), line, ContextBuild.Activity.Tone.DONE)));
            return new Saved(note.id(), line);
        }));
    }

    /** A page as it was given, or a file of this run's repository or of a dependency, at the commit the run reads it at. */
    static GoalKnowledgeNote.Source source(ContextBuild build, String value)
    {
        if (value.startsWith("https://")) {
            return new GoalKnowledgeNote.Source(value, Optional.empty());
        }
        int at = value.lastIndexOf('@');
        int colon = value.indexOf(':');
        checkArgument(at > colon && colon > 0, "sources: %s is neither an https page nor owner/repo:path@commit", value);
        String repository = value.substring(0, colon);
        String commit = value.substring(at + 1);
        boolean read = repository.equalsIgnoreCase(build.repository().fullName()) && commit.equals(build.baselineSha())
                || build.research().orElseThrow().related().stream().anyMatch(related -> related.repository().equalsIgnoreCase(repository) && related.commit().equals(commit));
        checkArgument(read, "sources: %s is not at a commit this run reads", value);
        return new GoalKnowledgeNote.Source(value.substring(0, at), Optional.of(commit));
    }

    private String call(String taskId, Optional<TurnId> turn, String raw)
    {
        try {
            checkArgument(ContextLimits.bytes(raw) <= 64 * 1024, "Tool arguments exceed 64 KiB");
            SaveNote request = MAPPER.readValue(raw, SaveNote.class);
            checkArgument(request != null, "Tool arguments must be an object");
            return encode(save(taskId, turn, request));
        }
        catch (JsonProcessingException failure) {
            return encode(new Failed(new Failure(ContextText.excerpt(failure.getOriginalMessage(), 1000))));
        }
        catch (IllegalArgumentException failure) {
            return encode(new Failed(new Failure(ContextText.excerpt(failure.getMessage(), 1000))));
        }
    }

    private static String encode(Object result)
    {
        try {
            return MAPPER.writeValueAsString(result);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode the knowledge note result", failure);
        }
    }

    private record SaveNote(String topic, String body, List<String> sources)
    {
        private SaveNote
        {
            checkText(topic, "topic", 120);
            checkText(body, "body", 2_000);
            topic = topic.strip();
            body = body.strip();
            sources = ImmutableList.copyOf(sources);
            checkArgument(!sources.isEmpty(), "sources: cite at least one page or file");
            checkArgument(sources.size() <= GoalKnowledgeNote.MAX_SOURCES, "sources: cite at most %s sources", GoalKnowledgeNote.MAX_SOURCES);
            sources.forEach(value -> checkText(value, "source", 700));
            sources = sources.stream().map(String::strip).collect(toImmutableList());
        }
    }

    private record Saved(String id, String activityLine)
    {
        private Saved
        {
            checkText(id, "id");
            checkText(activityLine, "activityLine");
        }
    }

    private record Failed(Failure error)
    {
        private Failed
        {
            requireNonNull(error, "error is null");
        }
    }

    private record Failure(String message)
    {
        private Failure
        {
            checkText(message, "message", 1000);
        }
    }
}
