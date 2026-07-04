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

import com.bytequay.app.domain.DevReport;
import com.bytequay.app.domain.DevReport.Decision;
import com.bytequay.app.domain.DevReport.TestMapEntry;
import com.bytequay.app.domain.DevReport.TrickySpot;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.review.DevReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * {@code record_dev_report} / {@code read_dev_report} / {@code
 * read_dev_conversation} — the dev → review-round context handoff
 * (plan-rail-runs.md R14-R15). Gated the same way as every other
 * {@code AgentRole.TASK} tool today; dev, ci_fix, and review_round turns
 * all run as TASK, so (as with the local-PR tools) there's no per-run-kind
 * restriction — a run other than the dev agent's own could technically
 * call {@code record_dev_report} too, but it's a local-only write with no
 * posting side effect.
 */
@Component
public class DevReportToolHandlers
{
    private static final int CONVERSATION_DEFAULT_LIMIT = 30;
    private static final int CONVERSATION_MAX_LIMIT = 100;

    private final DevReportService devReports;
    private final StageStore stageStore;
    private final ThreadStore threadStore;
    private final ObjectMapper mapper;

    public DevReportToolHandlers(
            DevReportService devReports, StageStore stageStore, ThreadStore threadStore, ObjectMapper mapper)
    {
        this.devReports = requireNonNull(devReports, "devReports is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Args for {@code record_dev_report}. {@code decisions} / {@code
     *  trickySpots} / {@code testMap} are raw JSON arrays so the schema
     *  stays simple; the handler validates each entry (mirrors {@code
     *  propose_backlog_items}). */
    public record RecordDevReportArgs(
            @ToolParam(description = "One-line-ish summary of the change, <= ~160 chars.",
                    required = true) String summary,
            @ToolParam(description = "JSON array of {what, why, rejectedAlternatives: string[]}.")
            JsonNode decisions,
            @ToolParam(description = "Things future edits must not break.") List<String> invariants,
            @ToolParam(description = "JSON array of {file, note} for non-obvious areas.")
            JsonNode trickySpots,
            @ToolParam(description = "JSON array of {area, tests: string[]}.",
                    wireName = "test_map") JsonNode testMap,
            @ToolParam(description = "Known gaps or deferred work.") List<String> followups) {}

    @AgentTool(
            name = "record_dev_report",
            description = "Record the DevReport — your typed handoff to whatever addresses review "
                    + "comments next. Call this as your last act before record_local_review flips the "
                    + "PR to local-open, while you still have full context. Idempotent: calling again "
                    + "updates the same report.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordDevReport(RecordDevReportArgs args, ToolCall call)
    {
        String taskId = call.taskId();
        if (taskId == null || taskId.isBlank()) {
            return ToolOutcome.Completed.error("no active task on this thread");
        }
        if (args == null || args.summary() == null || args.summary().isBlank()) {
            return ToolOutcome.Completed.error("summary is required");
        }
        List<Decision> decisions = readArray(args.decisions(), n -> new Decision(
                n.path("what").asText(""), n.path("why").asText(""), readStringArray(n.get("rejectedAlternatives"))));
        List<TrickySpot> trickySpots = readArray(args.trickySpots(), n -> new TrickySpot(
                n.path("file").asText(""), n.path("note").asText("")));
        List<TestMapEntry> testMap = readArray(args.testMap(), n -> new TestMapEntry(
                n.path("area").asText(""), readStringArray(n.get("tests"))));
        DevReport report = devReports.record(
                taskId, args.summary(), decisions, args.invariants(), trickySpots, testMap, args.followups());
        return ToolOutcome.Completed.ok("recorded dev report " + report.id());
    }

    /** Args for {@code read_dev_report} — no args; the active task is
     *  resolved from the calling turn. */
    public record ReadDevReportArgs() {}

    @AgentTool(
            name = "read_dev_report",
            description = "Read the active task's DevReport — the dev agent's summary, decisions, "
                    + "invariants, tricky spots, test map, and followups.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome readDevReport(ReadDevReportArgs args, ToolCall call)
    {
        String taskId = call.taskId();
        if (taskId == null || taskId.isBlank()) {
            return ToolOutcome.Completed.error("no active task on this thread");
        }
        Optional<DevReport> report = devReports.find(taskId);
        if (report.isEmpty()) {
            return ToolOutcome.Completed.ok("no dev report recorded for this task");
        }
        return ToolOutcome.Completed.ok(mapper.valueToTree(report.get()).toString());
    }

    /** Args for {@code read_dev_conversation}. */
    public record ReadDevConversationArgs(
            @ToolParam(description = "Text to search for in the dev agent's messages "
                    + "(case-insensitive substring). Omit to get the most recent messages.")
            String query,
            @ToolParam(description = "Max messages to return (default 30, max 100).")
            Integer limit) {}

    @AgentTool(
            name = "read_dev_conversation",
            description = "Search the active task's full dev-stage conversation for a query, or (with "
                    + "no query) read its most recent messages — the rare deep dive into 'why' when the "
                    + "DevReport summary isn't enough. Sibling of read_plan_conversation.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome readDevConversation(ReadDevConversationArgs args, ToolCall call)
    {
        String taskId = call.taskId();
        if (taskId == null || taskId.isBlank()) {
            return ToolOutcome.Completed.error("no active task on this thread");
        }
        List<String> devStageIds = stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.type() == StageType.DEVELOPMENT_STAGE)
                .map(s -> s.id().toString())
                .toList();
        if (devStageIds.isEmpty()) {
            return ToolOutcome.Completed.ok("no dev-stage conversation for this task");
        }
        List<ThreadMessage> messages = threadStore.listStageMessagesByTask(taskId).stream()
                .filter(m -> devStageIds.contains(m.stageId()))
                .sorted(Comparator.comparingLong(ThreadMessage::seq))
                .toList();
        String query = args == null ? null : args.query();
        int limit = args == null || args.limit() == null ? CONVERSATION_DEFAULT_LIMIT
                : Math.min(Math.max(1, args.limit()), CONVERSATION_MAX_LIMIT);
        List<ThreadMessage> matched;
        if (query == null || query.isBlank()) {
            int from = Math.max(0, messages.size() - limit);
            matched = messages.subList(from, messages.size());
        }
        else {
            String needle = query.toLowerCase(Locale.ROOT);
            matched = messages.stream()
                    .filter(m -> m.contentJson() != null && m.contentJson().toLowerCase(Locale.ROOT)
                            .contains(needle))
                    .toList();
            if (matched.size() > limit) {
                matched = matched.subList(matched.size() - limit, matched.size());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("messages", mapper.valueToTree(matched));
        out.put("totalInDevStage", messages.size());
        return ToolOutcome.Completed.ok(out.toString());
    }

    private static <T> List<T> readArray(JsonNode array, Function<JsonNode, T> mapEntry)
    {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (JsonNode node : array) {
            out.add(mapEntry.apply(node));
        }
        return out;
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
