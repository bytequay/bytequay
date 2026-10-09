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
package com.bytequay.app.google;

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.GoogleWorkspaceBridge;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public class GoogleWorkspaceBridgeResolver
        implements BridgeResolver<GoogleWorkspaceBridge>
{
    private final GoogleWorkspaceService google;
    private final AgentTaskStore tasks;
    private final ObjectMapper mapper;

    public GoogleWorkspaceBridgeResolver(GoogleWorkspaceService google, AgentTaskStore tasks, ObjectMapper mapper)
    {
        this.google = requireNonNull(google, "google is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public Optional<GoogleWorkspaceBridge> resolve(String taskId)
    {
        return readable(taskId).map(snapshot -> new GoogleWorkspaceBridge(raw -> call(taskId, raw)));
    }

    private Optional<GoogleSnapshot> readable(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId)).filter(task -> task.interactive() && task.state() == TaskState.OPEN
                        && task.workspaceId().isPresent() && task.worktreeRemovedAt().isEmpty())
                .flatMap(task -> google.snapshot()).filter(GoogleSnapshot::agentAccess);
    }

    private String call(String taskId, String raw)
    {
        GoogleSnapshot data = readable(taskId).orElseThrow(() -> new IllegalStateException("Google data access is disabled or this task is no longer open."));
        checkArgument(raw.length() <= 4096, "Google request is too long.");
        try {
            JsonNode args = mapper.readTree(raw);
            checkArgument(args != null && args.isObject(), "Expected a Google tool request object.");
            args.fieldNames().forEachRemaining(field -> checkArgument(ImmutableSet.of("action", "source", "id", "query").contains(field), "Unknown field: %s", field));
            String action = args.path("action").asText();
            String source = args.path("source").asText();
            checkArgument(action.equals("LIST") || action.equals("READ"), "action must be LIST or READ.");
            checkArgument(source.equals("MAIL") || source.equals("CALENDAR"), "source must be MAIL or CALENDAR.");
            boolean mail = source.equals("MAIL");
            ObjectNode result = mapper.createObjectNode();
            result.put("savedAt", mail ? data.mailSavedAt() : data.calendarSavedAt());
            result.put("loaded", !(mail ? data.mailSavedAt() : data.calendarSavedAt()).isEmpty());
            result.put("coverage", mail ? "One saved Gmail search page, up to 25 messages. Query: " + data.query()
                    : "One connector result for " + data.calendarId() + ", " + data.timeMin() + " to " + data.timeMax()
                            + ". The connector may omit events in busy calendars and declined invitations.");
            String query = args.path("query").asText("").toLowerCase(Locale.ROOT);
            if (action.equals("LIST")) {
                if (mail) {
                    result.set("items", mapper.valueToTree(data.mail().stream()
                            .filter(item -> (item.subject() + " " + item.from() + " " + item.body()).toLowerCase(Locale.ROOT).contains(query))
                            .map(item -> new Entry(item.id(), item.subject(), item.from(), item.date())).collect(toImmutableList())));
                }
                else {
                    result.set("items", mapper.valueToTree(data.events().stream()
                            .filter(item -> (item.summary() + " " + item.description()).toLowerCase(Locale.ROOT).contains(query))
                            .map(item -> new Entry(item.id(), item.summary(), "", item.start())).collect(toImmutableList())));
                }
            }
            else {
                String id = args.path("id").asText();
                checkArgument(!id.isBlank(), "READ requires an id from LIST.");
                Object item = mail ? data.mail().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow()
                        : data.events().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
                result.set("item", mapper.valueToTree(item));
            }
            String response = mapper.writeValueAsString(result);
            checkState(response.length() <= 1100000, "Saved Google result is too large.");
            return response;
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid Google tool request.");
        }
    }

    private record Entry(String id, String title, String from, String date) {}
}
