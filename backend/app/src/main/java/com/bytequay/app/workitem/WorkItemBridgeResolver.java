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
package com.bytequay.app.workitem;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.WorkItemBridge;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.WorkItemStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

@Component
public class WorkItemBridgeResolver
        implements BridgeResolver<WorkItemBridge>
{
    private final AgentTaskStore tasks;
    private final ObjectProvider<WorkItemCatalog> catalog;
    private final ObjectProvider<WorkItemService> work;
    private final WorkItemStore items;
    private final ObjectMapper mapper;

    public WorkItemBridgeResolver(AgentTaskStore tasks, ObjectProvider<WorkItemCatalog> catalog, ObjectProvider<WorkItemService> work, WorkItemStore items, ObjectMapper mapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.catalog = requireNonNull(catalog, "catalog is null");
        this.work = requireNonNull(work, "work is null");
        this.items = requireNonNull(items, "items is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public Optional<WorkItemBridge> resolve(String taskId)
    {
        return readable(taskId).map(task -> new WorkItemBridge(raw -> call(taskId, raw)));
    }

    private Optional<AgentTask> readable(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId)).filter(task -> task.state() == TaskState.OPEN && task.workspaceId().isPresent()
                && task.worktreeRemovedAt().isEmpty());
    }

    private String call(String taskId, String raw)
    {
        AgentTask task = readable(taskId).orElseThrow(() -> new IllegalStateException("Work items are no longer available to this task"));
        String workspaceId = task.workspaceId().orElseThrow();
        try {
            checkArgument(raw.length() <= 400000, "Work item request is too long");
            JsonNode args = mapper.readTree(raw);
            checkArgument(args != null && args.isObject(), "Expected an object");
            String action = text(args, "action", 10);
            Set<String> allowed = switch (action) {
                case "LIST" -> ImmutableSet.of("action", "query", "offset");
                case "READ" -> ImmutableSet.of("action", "id");
                case "CREATE" -> ImmutableSet.of("action", "operationKey", "title", "description", "epicId");
                default -> throw new IllegalArgumentException("action must be LIST, READ or CREATE");
            };
            args.fieldNames().forEachRemaining(key -> checkArgument(allowed.contains(key), "Unknown field: %s", key));
            Object result = switch (action) {
                case "LIST" -> {
                    String query = args.has("query") ? text(args, "query", 500).toLowerCase(Locale.ROOT) : "";
                    checkArgument(!args.has("offset") || args.get("offset").isIntegralNumber() && args.get("offset").canConvertToInt(), "offset must be an integer");
                    int offset = args.path("offset").asInt();
                    checkArgument(offset >= 0, "offset must be nonnegative");
                    yield catalog.getObject().list(workspaceId).stream().filter(item -> (item.title() + " " + item.id()).toLowerCase(Locale.ROOT).contains(query))
                            .skip(offset).limit(50).map(item -> new Entry(item.id(), item.source(), item.title(), item.status())).collect(toImmutableList());
                }
                case "READ" -> {
                    String id = args.has("id") ? text(args, "id", 200) : items.findByIdentity(WorkItemLink.taskIdentity(taskId))
                            .filter(item -> item.workspaceId().equals(workspaceId)).map(WorkItem::id)
                            .orElseThrow(() -> new IllegalArgumentException("This task has no work item; LIST then READ an id"));
                    yield catalog.getObject().read(workspaceId, id);
                }
                case "CREATE" -> {
                    String operation = text(args, "operationKey", 100);
                    String id = UUID.nameUUIDFromBytes(("agent-work:" + taskId + ":" + operation).getBytes(UTF_8)).toString();
                    String title = text(args, "title", 500);
                    String description = text(args, "description", 65536);
                    if (args.has("epicId")) {
                        yield work.getObject().createInEpic(workspaceId, id, text(args, "epicId", 200), title, WorkItem.Kind.DEVELOP, description,
                                WorkItem.CompletionRule.MERGED_DELIVERY);
                    }
                    yield work.getObject().create(workspaceId, id, Optional.empty(), title, WorkItem.Kind.DEVELOP, description, WorkItem.CompletionRule.MERGED_DELIVERY);
                }
                default -> throw new IllegalArgumentException("Unknown action");
            };
            return mapper.writeValueAsString(result);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid work item JSON", failure);
        }
    }

    private static String text(JsonNode args, String key, int limit)
    {
        JsonNode value = args.path(key);
        checkArgument(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= limit, "%s must contain 1–%s characters", key, limit);
        return value.asText();
    }

    private record Entry(String id, UnifiedWorkItem.Source source, String title, String status) {}
}
