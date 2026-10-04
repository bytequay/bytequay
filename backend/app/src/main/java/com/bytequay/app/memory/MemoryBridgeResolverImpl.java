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
package com.bytequay.app.memory;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.MemoryBridge;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Offers the memory tool to development tasks and reviews whose workspace has memory on for them. Spec checks and other restricted runs
 * get none. The tool searches, reads, reports, and remembers: a rule resting on the user's words in the turn, or a recipe resting on a
 * command that passed in the task, both bound by ByteQuay, never named by the agent. Agents never change a memory the user owns. A
 * replay with memory sees only what it could have known and neither reports nor remembers, since it reviews code that has since changed.
 */
@Component
public class MemoryBridgeResolverImpl
        implements BridgeResolver<MemoryBridge>
{
    private final AgentTaskStore tasks;
    private final AgentReviewStore reviews;
    private final MemoryService memories;
    private final MemorySources sources;
    private final ObjectMapper mapper;
    private final WorkspaceRelationships relationships;

    public MemoryBridgeResolverImpl(AgentTaskStore tasks, AgentReviewStore reviews, MemoryService memories, MemorySources sources,
            ObjectMapper mapper, WorkspaceRelationships relationships)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.memories = requireNonNull(memories, "memories is null");
        this.sources = requireNonNull(sources, "sources is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.relationships = requireNonNull(relationships, "relationships is null");
    }

    @Override
    public Optional<MemoryBridge> resolve(String taskId)
    {
        Optional<AgentReview> review = reviews.findReviewByTask(taskId);
        Optional<AgentReview.Replay> replay = review.flatMap(AgentReview::replay);
        Predicate<Memory> visible = MemoryService.visibleTo(replay);
        Optional<AgentTask> open = tasks.find(TaskId.valueOf(taskId)).filter(task -> task.state() == TaskState.OPEN);
        // A session serves one turn; what it remembers rests on that turn's words and commands.
        Optional<TurnId> turn = sources.capture(taskId);
        return open.flatMap(task -> task.workspaceId().filter(workspaceId -> switchedOn(task, review, workspaceId))
                // Remembering needs no memory to exist yet; a replay can only read, so it needs one it could have known.
                .filter(workspaceId -> replay.isEmpty() || memories.list(workspaceId).stream().anyMatch(visible))
                .map(workspaceId -> new MemoryBridge(arguments -> call(workspaceId, task, review.isPresent(), turn, replay, arguments))));
    }

    private boolean switchedOn(AgentTask task, Optional<AgentReview> review, String workspaceId)
    {
        Optional<AgentReview.Replay> replay = review.flatMap(AgentReview::replay);
        if (replay.isPresent()) {
            // The replay's own side of the comparison decides, whatever the workspace switch says now.
            return replay.orElseThrow().memory();
        }
        MemorySwitches switches = memories.switches(workspaceId);
        return review.isPresent() ? switches.reviews() : task.interactive() && switches.development();
    }

    private String call(String workspaceId, AgentTask task, boolean review, Optional<TurnId> turn, Optional<AgentReview.Replay> replay, String json)
    {
        JsonNode arguments;
        try {
            arguments = mapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Arguments are not a JSON object", failure);
        }
        checkArgument(arguments.isObject(), "Arguments are not a JSON object");
        task = tasks.find(task.id()).orElseThrow(() -> new IllegalArgumentException("Task is unavailable"));
        checkArgument(task.state() == TaskState.OPEN && task.workspaceId().filter(workspaceId::equals).isPresent(), "Task is unavailable");
        Optional<AgentReview> currentReview = reviews.findReviewByTask(task.id().toString());
        checkArgument(switchedOn(task, currentReview, workspaceId), "Memory is disabled for this task");
        String selectedWorkspace = text(arguments, "workspaceId").orElse(workspaceId);
        boolean related = !selectedWorkspace.equals(workspaceId);
        if (related) {
            checkArgument(arguments.path("action").asText().equals("SEARCH") || arguments.path("action").asText().equals("READ"),
                    "Only SEARCH and READ may use a related workspace");
            checkArgument(task.interactive() && currentReview.isEmpty(), "Related memory is available only in development conversations");
            relationships.requireRelated(workspaceId, selectedWorkspace);
            checkArgument(memories.switches(selectedWorkspace).development(), "Related workspace memory is disabled");
        }
        Predicate<Memory> visible = MemoryService.visibleTo(replay);
        ObjectNode result = switch (arguments.path("action").asText("")) {
            case "SEARCH" -> search(selectedWorkspace, visible, arguments);
            case "READ" -> read(selectedWorkspace, visible, id(arguments));
            case "REPORT" -> {
                checkArgument(replay.isEmpty(), "A replay reviews past code and cannot report memories");
                yield report(workspaceId, task.id().toString(), arguments);
            }
            case "REMEMBER" -> {
                checkArgument(replay.isEmpty(), "A replay reviews past code and cannot remember anything");
                yield remember(workspaceId, task, review, turn.orElseThrow(() -> new IllegalArgumentException("This session has no turn to remember from")), arguments);
            }
            default -> throw new IllegalArgumentException("action must be SEARCH, READ, REPORT or REMEMBER");
        };
        result.put("workspaceId", selectedWorkspace);
        if (related) {
            result.put("repository", relationships.requireRelated(workspaceId, selectedWorkspace).monitoredRepository().fullName());
            result.put("activityLine", result.path("activityLine").asText() + " · " + selectedWorkspace);
        }
        return result.toString();
    }

    private ObjectNode search(String workspaceId, Predicate<Memory> visible, JsonNode arguments)
    {
        Optional<String> query = text(arguments, "query");
        Optional<String> path = text(arguments, "path");
        MemoryService.Page page = memories.search(workspaceId, query, path, arguments.path("page").asInt(1), visible);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode list = result.putArray("memories");
        for (Memory memory : page.memories()) {
            ObjectNode item = list.addObject();
            item.put("id", memory.id());
            item.put("workspaceId", workspaceId);
            item.put("version", memory.version());
            item.put("kind", memory.kind().name());
            item.put("title", memory.title());
            item.put("text", memory.text());
            scope(item, memory);
            item.put("source", MemoryService.sourceLabel(memory));
            MemoryService.flag(memory).ifPresent(flag -> item.put("flag", flag));
        }
        result.put("page", page.page());
        result.put("pages", page.pages());
        result.put("total", page.total());
        String target = query.map(text -> " for “" + text + "”").orElse("") + path.map(text -> " in " + text).orElse("");
        result.put("activityLine", "Searched memory" + target + " · " + page.total() + (page.total() == 1 ? " memory" : " memories"));
        return result;
    }

    private ObjectNode read(String workspaceId, Predicate<Memory> visible, String memoryId)
    {
        Memory memory = memories.get(workspaceId, memoryId);
        if (!visible.test(memory)) {
            // A memory hidden from a replay reads as missing, so the id alone tells the replay nothing.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory " + memoryId + " is not in this workspace");
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("id", memory.id());
        result.put("version", memory.version());
        result.put("kind", memory.kind().name());
        result.put("title", memory.title());
        result.put("text", memory.text());
        scope(result, memory);
        result.put("savedBy", memory.origin().user() ? "the user; agents cannot change it" : memory.origin().description());
        ArrayNode sources = result.putArray("sources");
        for (MemorySource source : memory.sources()) {
            ObjectNode item = sources.addObject();
            item.put("kind", source.kind().name());
            source.url().ifPresent(url -> item.put("url", url));
            item.put("quote", source.quote());
            item.put("at", source.at().toString());
            source.section().ifPresent(section -> item.put("section", section));
            source.user().ifPresent(part -> item.put("where", part.where().name()));
            source.command().ifPresent(part -> item.put("command", part.command()).put("directory", part.directory()).put("commit", part.commit()));
            source.pullRequest().ifPresent(part -> {
                item.put("pullRequest", part.number());
                item.put("part", part.part().name());
                item.put("author", part.author());
                item.put("maintainer", part.maintainer());
                item.put("merged", part.merged());
                item.put("resolved", part.resolved());
                item.put("outdated", part.outdated());
            });
        }
        ArrayNode reports = result.putArray("reports");
        memory.reports().forEach(report -> reports.addObject().put("reason", report.reason()).put("quote", report.quote()).put("at", report.at().toString()));
        result.put("activityLine", "Read memory " + memory.id());
        return result;
    }

    private ObjectNode report(String workspaceId, String taskId, JsonNode arguments)
    {
        String reason = text(arguments, "reason").orElseThrow(() -> new IllegalArgumentException("REPORT needs a reason"));
        Memory reported = memories.report(workspaceId, id(arguments), reason, text(arguments, "quote").orElse(""), taskId);
        ObjectNode result = mapper.createObjectNode();
        result.put("id", reported.id());
        result.put("reported", true);
        result.put("activityLine", "Reported memory " + reported.id() + " as wrong");
        return result;
    }

    /**
     * Saves a rule resting on the user's words in this turn, or a recipe resting on a command that passed in this task. With an id, the
     * rule or recipe an agent saved before takes the new title, text and scope, and the old ones stay as its previous version.
     */
    private ObjectNode remember(String workspaceId, AgentTask task, boolean review, TurnId turn, JsonNode arguments)
    {
        Memory.Kind kind = switch (text(arguments, "kind").orElse("")) {
            case "RULE" -> Memory.Kind.RULE;
            case "RECIPE" -> Memory.Kind.RECIPE;
            default -> throw new IllegalArgumentException("kind must be RULE or RECIPE");
        };
        String title = text(arguments, "title")
                .orElseThrow(() -> new IllegalArgumentException("REMEMBER needs a title: the situation it covers, in at most " + Memory.MAX_TITLE + " characters"));
        String text = text(arguments, "text").map(value -> value.replaceAll("\\s*\\R\\s*", " "))
                .orElseThrow(() -> new IllegalArgumentException("REMEMBER needs the text, in your own words"));
        List<String> scope = paths(arguments);
        MemorySource source = kind == Memory.Kind.RECIPE
                ? sources.passingCommand(task, turn, text(arguments, "command").orElseThrow(() -> new IllegalArgumentException("A RECIPE needs the command that passed")))
                : sources.userWords(task, turn, text(arguments, "quote"));
        Optional<String> id = text(arguments, "id");
        Memory saved;
        if (id.isPresent()) {
            Memory existing = memories.get(workspaceId, id.orElseThrow());
            checkArgument(existing.kind() == kind, "Memory %s is a %s; remember a %s as a new memory", existing.id(), existing.kind(), kind);
            saved = memories.replace(workspaceId, existing.id(), title, text, scope, ImmutableList.of(source));
        }
        else {
            saved = memories.create(workspaceId, kind, title, text, scope, ImmutableList.of(source),
                    new Memory.Origin(false, review ? "Remembered in a review" : "Remembered in a task", Optional.of(task.id().toString())));
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("id", saved.id());
        result.put("created", id.isEmpty());
        // The transcript shows this line with Undo; its id and title are what the row needs.
        result.put("activityLine", (id.isEmpty() ? "Remembered " : "Updated ") + saved.id() + " · " + saved.title());
        return result;
    }

    private static List<String> paths(JsonNode arguments)
    {
        JsonNode value = arguments.path("scope");
        if (value.isMissingNode() || value.isNull()) {
            return ImmutableList.of();
        }
        checkArgument(value.isArray(), "scope must be a list of repository paths");
        return Memory.checkScope(ImmutableList.copyOf(value.elements()).stream().map(path -> {
            checkArgument(path.isTextual(), "scope must be a list of repository paths");
            return path.textValue().strip();
        }).collect(toImmutableList()));
    }

    private static void scope(ObjectNode node, Memory memory)
    {
        if (memory.repositoryWide()) {
            node.put("scope", "repository");
        }
        else {
            memory.scope().forEach(node.putArray("scope")::add);
        }
    }

    private static String id(JsonNode arguments)
    {
        return text(arguments, "id").orElseThrow(() -> new IllegalArgumentException("READ and REPORT need a memory id"));
    }

    private static Optional<String> text(JsonNode arguments, String field)
    {
        JsonNode value = arguments.path(field);
        checkArgument(value.isMissingNode() || value.isNull() || value.isTextual(), "%s must be a string", field);
        return Optional.ofNullable(value.textValue()).map(String::strip).filter(text -> !text.isEmpty());
    }
}
