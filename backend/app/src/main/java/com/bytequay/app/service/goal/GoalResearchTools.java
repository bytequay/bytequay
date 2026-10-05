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
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.store.GoalKnowledgeNoteStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The one tool of a Goal's research run: save a knowledge note that cites the pages and files it rests on. */
@Component
public class GoalResearchTools
{
    static final String SAVED = "goal-note:";
    static final int MAX_NOTES = 40;
    private static final Set<String> FIELDS = ImmutableSet.of("topic", "body", "sources");
    private static final String POLICY = "This research run reads the repository, its dependency checkouts and documentation pages, and saves knowledge notes "
            + "through its tool only. It must not commit, push, comment on GitHub, change files, read credentials or start child agents.";

    private final ContextBuildStore builds;
    private final ContextBuildService service;
    private final GoalKnowledgeNoteStore notes;
    private final AgentTaskStore tasks;
    private final AgentUnitOfWork unitOfWork;
    private final ObjectMapper mapper;
    private final Clock clock;

    public GoalResearchTools(ContextBuildStore builds, ContextBuildService service, GoalKnowledgeNoteStore notes, AgentTaskStore tasks, AgentUnitOfWork unitOfWork,
            ObjectMapper mapper, Clock clock)
    {
        this.builds = requireNonNull(builds, "builds is null");
        this.service = requireNonNull(service, "service is null");
        this.notes = requireNonNull(notes, "notes is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
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

    private JsonNode save(ContextBuild build, TurnId turn, JsonNode args)
    {
        args.fieldNames().forEachRemaining(field -> checkArgument(FIELDS.contains(field), "%s: not a field of a note", field));
        ContextBuild.Research research = build.research().orElseThrow();
        long saved = build.capture().attempts().keySet().stream().filter(key -> key.startsWith(SAVED)).count();
        checkArgument(saved < MAX_NOTES, "This run already saved %s notes; end the run", MAX_NOTES);
        List<GoalKnowledgeNote.Source> sources = strings(args).stream().map(value -> source(build, value)).collect(toImmutableList());
        GoalKnowledgeNote note = new GoalKnowledgeNote("note-" + UUID.randomUUID(), research.goalId(), build.workspaceId(), text(args, "topic"), text(args, "body"),
                sources, build.taskId(), Instant.now(clock), Optional.empty());
        notes.save(note);
        String line = "Saved note · " + ContextText.excerpt(note.topic(), 120);
        WorkspaceOperationLocks.withLock(build.workspaceId(), () -> unitOfWork.execute(() -> {
            ContextBuild current = service.authorize(build.taskId(), Optional.of(turn));
            return builds.save(Optional.of(current), current.withCapture(current.capture().withAttempts(ImmutableMap.of(SAVED + note.id(), "1"))
                    .logged(Instant.now(clock), line, ContextBuild.Activity.Tone.DONE)));
        }));
        return mapper.createObjectNode().put("id", note.id()).put("activityLine", line);
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
            JsonNode args = mapper.readTree(raw);
            checkArgument(args != null && args.isObject(), "Tool arguments must be an object");
            ContextBuild build = service.authorize(taskId, turn);
            checkArgument(build.research().isPresent(), "This run is not a research run");
            return save(build, turn.orElseThrow(), args).toString();
        }
        catch (JsonProcessingException | RuntimeException failure) {
            ObjectNode error = mapper.createObjectNode();
            error.putObject("error").put("message", ContextText.excerpt(Optional.ofNullable(failure.getMessage()).orElse("The note was not saved"), 1000));
            return error.toString();
        }
    }

    private static String text(JsonNode args, String key)
    {
        JsonNode value = args.get(key);
        checkArgument(value != null && value.isTextual() && !value.textValue().isBlank(), "%s: required", key);
        return value.textValue().strip();
    }

    private static List<String> strings(JsonNode args)
    {
        JsonNode values = args.get("sources");
        checkArgument(values != null && values.isArray() && !values.isEmpty(), "sources: cite at least one page or file");
        ImmutableList.Builder<String> result = ImmutableList.builder();
        values.forEach(value -> {
            checkArgument(value.isTextual() && !value.textValue().isBlank(), "sources: items are text");
            result.add(value.textValue().strip());
        });
        return result.build();
    }
}
