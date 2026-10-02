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
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.MemoryBridge;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Offers the memory tool to development tasks and reviews whose workspace has memories and has memory on for them. Spec checks
 * and other restricted runs get none. The tool only searches, reads and reports; nothing an agent sends changes a memory's text or sources.
 */
@Component
public class MemoryBridgeResolverImpl
        implements BridgeResolver<MemoryBridge>
{
    private final AgentTaskStore tasks;
    private final AgentReviewStore reviews;
    private final MemoryService memories;
    private final ObjectMapper mapper;

    public MemoryBridgeResolverImpl(AgentTaskStore tasks, AgentReviewStore reviews, MemoryService memories, ObjectMapper mapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.memories = requireNonNull(memories, "memories is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public Optional<MemoryBridge> resolve(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId))
                .filter(task -> task.state() == TaskState.OPEN)
                .flatMap(task -> task.workspaceId().filter(workspaceId -> switchedOn(task, workspaceId)))
                .filter(workspaceId -> !memories.list(workspaceId).isEmpty())
                .map(workspaceId -> new MemoryBridge(arguments -> call(workspaceId, taskId, arguments)));
    }

    private boolean switchedOn(AgentTask task, String workspaceId)
    {
        MemorySwitches switches = memories.switches(workspaceId);
        return reviews.findReviewByTask(task.id().toString()).isPresent() ? switches.reviews() : task.interactive() && switches.development();
    }

    String call(String workspaceId, String taskId, String json)
    {
        JsonNode arguments;
        try {
            arguments = mapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Arguments are not a JSON object", failure);
        }
        checkArgument(arguments.isObject(), "Arguments are not a JSON object");
        ObjectNode result = switch (arguments.path("action").asText("")) {
            case "SEARCH" -> search(workspaceId, arguments);
            case "READ" -> read(workspaceId, id(arguments));
            case "REPORT" -> report(workspaceId, taskId, arguments);
            default -> throw new IllegalArgumentException("action must be SEARCH, READ or REPORT");
        };
        return result.toString();
    }

    private ObjectNode search(String workspaceId, JsonNode arguments)
    {
        Optional<String> query = text(arguments, "query");
        Optional<String> path = text(arguments, "path");
        MemoryService.Page page = memories.search(workspaceId, query, path, arguments.path("page").asInt(1));
        ObjectNode result = mapper.createObjectNode();
        ArrayNode list = result.putArray("memories");
        for (Memory memory : page.memories()) {
            ObjectNode item = list.addObject();
            item.put("id", memory.id());
            item.put("kind", memory.kind().name());
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

    private ObjectNode read(String workspaceId, String memoryId)
    {
        Memory memory = memories.get(workspaceId, memoryId);
        ObjectNode result = mapper.createObjectNode();
        result.put("id", memory.id());
        result.put("kind", memory.kind().name());
        result.put("text", memory.text());
        scope(result, memory);
        result.put("savedBy", memory.origin().user() ? "the user; agents cannot change it" : memory.origin().description());
        ArrayNode sources = result.putArray("sources");
        for (MemorySource source : memory.sources()) {
            ObjectNode item = sources.addObject();
            item.put("kind", source.kind().name());
            item.put("url", source.url());
            item.put("quote", source.quote());
            item.put("at", source.at().toString());
            source.section().ifPresent(section -> item.put("section", section));
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
