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
package com.bytequay.app.service.tools;

import com.bytequay.app.service.backlog.BacklogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Agent tools for the per-thread backlog. {@code propose_backlog_items} lets
 * the trunk capture several candidate sub-tasks in one batch (the "trunk
 * found N cleanup candidates, which do we want?" flow) instead of cutting
 * tasks — the user then triages each (start dev / keep / skip).
 */
@Component
public class BacklogToolHandlers
{
    private final BacklogService backlog;
    private final ObjectMapper mapper;

    public BacklogToolHandlers(BacklogService backlog, ObjectMapper mapper)
    {
        this.backlog = requireNonNull(backlog, "backlog is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Args for {@code propose_backlog_items}. {@code items} is a raw JSON
     *  array so the schema stays simple; the handler validates each entry. */
    public record ProposeBacklogItemsArgs(
            @ToolParam(
                    description = "A JSON array of candidate items. Each entry is an object with: "
                            + "title (string, required), body (string, the proposal text), "
                            + "tags (array of strings, optional), priority (low|medium|high, optional).",
                    required = true)
            JsonNode items,
            @ToolParam(description = "A brief framing line shown above the proposed items in the conversation.")
            String contextNote)
    {
    }

    @AgentTool(
            name = "propose_backlog_items",
            description = "Record several distinct candidate sub-tasks as backlog items in one batch, "
                    + "instead of cutting tasks. Use when a broad request surfaces multiple separate "
                    + "opportunities and you want the user to triage which to pursue. The items are saved "
                    + "immediately to the current thread's backlog — there is NO approval step — and appear "
                    + "in the Backlog tab and as 'Proposed by the trunk' triage cards, cross-linked as a "
                    + "group; the user then starts, keeps, or skips each. Tell the user the items are "
                    + "recorded (and visible in the Backlog tab), never that they are parked awaiting approval.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public ToolOutcome proposeBacklogItems(ProposeBacklogItemsArgs args, ToolCall call)
    {
        String threadId = call.threadId();
        if (threadId == null || threadId.isBlank()) {
            return ToolOutcome.Completed.error("no thread bound to this call");
        }
        if (args == null || args.items() == null || !args.items().isArray() || args.items().isEmpty()) {
            return ToolOutcome.Completed.error("items must be a non-empty JSON array");
        }
        List<BacklogService.NewBacklogItem> items = new ArrayList<>();
        for (JsonNode node : args.items()) {
            String title = node.path("title").asText("").strip();
            if (title.isEmpty()) {
                return ToolOutcome.Completed.error("every item needs a non-empty title");
            }
            String priority = node.hasNonNull("priority") ? node.get("priority").asText() : null;
            items.add(new BacklogService.NewBacklogItem(
                    title,
                    node.path("body").asText(""),
                    readStringArray(node.get("tags")),
                    priority));
        }
        BacklogService.BatchResult result;
        try {
            result = backlog.createBatch(threadId, items);
        }
        catch (RuntimeException e) {
            return ToolOutcome.Completed.error("could not create backlog items: " + e.getMessage());
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode idsNode = out.putArray("backlogItemIds");
        result.backlogItemIds().forEach(idsNode::add);
        ArrayNode itemsNode = out.putArray("backlogItems");
        for (int i = 0; i < result.backlogItemIds().size(); i++) {
            itemsNode.addObject()
                    .put("id", result.backlogItemIds().get(i))
                    .put("title", items.get(i).title());
        }
        out.put("relatedBacklogGroupId", result.relatedBacklogGroupId());
        out.put("count", result.backlogItemIds().size());
        return ToolOutcome.Completed.ok(out.toString());
    }

    private static List<String> readStringArray(JsonNode node)
    {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode element : node) {
            String value = element.asText("").strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }
}
