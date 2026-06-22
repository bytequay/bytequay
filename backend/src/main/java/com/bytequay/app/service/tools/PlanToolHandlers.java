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

import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The brain agent's planning write tool. Distinct from the read-only
 * {@link BrainToolHandlers}: {@code record_plan} mutates — it writes a
 * {@code PLAN_RECORDED} event on the task's open PlanStage. The brain reaches
 * it via the in-JVM {@code BRAIN_TOOL_ALLOWLIST}; on the MCP path it is gated
 * to {@link ThreadKind#BRAIN_AGENT} (see {@code AgentTool.kinds}).
 */
@Component
public class PlanToolHandlers
{
    private static final Logger log = LoggerFactory.getLogger(PlanToolHandlers.class);

    /** Default / max page size for read_plan_conversation. */
    private static final int CONVO_DEFAULT_LIMIT = 50;
    private static final int CONVO_MAX_LIMIT = 200;

    private final StageStore stageStore;
    private final ThreadStore threadStore;
    private final ObjectMapper mapper;

    public PlanToolHandlers(StageStore stageStore, ThreadStore threadStore, ObjectMapper mapper)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public record RecordPlanArgs(
            @ToolParam(description = "Task id whose open PlanStage this plan is recorded on.",
                    required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "The structured PlanResult object: status, understanding "
                    + "(summary, affectedComponents, existingPatterns, constraints), intent "
                    + "(summary; steps — an array of objects each {ordinal, action, files, "
                    + "rationale} where action is the step text, not a bare string; "
                    + "validationStrategy, pushStrategy), and signals (riskLevel, riskNotes, "
                    + "componentsCount, estimatedComplexity, expectedGain). "
                    + "Set status='finalized' when ready for the user to approve.",
                    required = true)
            JsonNode plan) {}

    @AgentTool(
            name = "record_plan",
            description = "Record your structured implementation plan on the task's PlanStage. "
                    + "Call this at least once during planning before signalling done — the user "
                    + "reviews and approves it before any development starts. Calling it again "
                    + "records a revision (e.g. after user feedback). The server assigns the id, "
                    + "timestamp, and source.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK},
            kinds = ThreadKind.BRAIN_AGENT)
    public ToolOutcome recordPlan(RecordPlanArgs args, ToolCall call)
    {
        if (args == null || args.taskId() == null || args.taskId().isBlank()) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        if (args.plan() == null || !args.plan().isObject()) {
            return ToolOutcome.Completed.error("plan must be a PlanResult object");
        }
        Optional<StageInstance> open = stageStore.findActiveStage(args.taskId())
                .filter(stage -> stage.type() == StageType.PLAN_STAGE);
        if (open.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "no open PlanStage for task " + args.taskId() + " — plans can only be recorded "
                            + "while planning is active");
        }
        StageInstance plan = open.get();

        // First plan on this stage is the brain's own draft; any later one is
        // a revision. (A trunk-supplied seed is written by the create flow,
        // not this tool, and counts as a prior plan → revision.)
        boolean hasPrior = stageStore.findEventsByStage(plan.id()).stream()
                .anyMatch(e -> e.eventType() == StageEventType.PLAN_RECORDED);
        String source = hasPrior ? "brain-revision" : "brain";

        Map<String, Object> payload = toMutableMap(args.plan());
        payload.put("id", UUID.randomUUID().toString());
        payload.put("plannedAt", Instant.now().toString());
        payload.put("source", source);

        StageEvent event = stageStore.recordEvent(
                plan.id(), args.taskId(), StageEventType.PLAN_RECORDED, payload);
        log.debug("recorded plan ({}) on PlanStage {} for task {}", source, plan.id(), args.taskId());
        return serialise(event.payloadJson());
    }

    // ── read_plan_summary ───────────────────────────────────────────────

    public record ReadPlanSummaryArgs(
            @ToolParam(description = "Task id whose approved plan to read.",
                    required = true, wireName = "task_id")
            String taskId) {}

    @AgentTool(
            name = "read_plan_summary",
            description = "Read the task's latest finalized plan (the PlanResult the user "
                    + "approved): understanding, intent + numbered steps, validation and push "
                    + "strategy, and risk signals. Cheap — call this at the start of dev work "
                    + "instead of re-reading the whole planning conversation.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK})
    public ToolOutcome readPlanSummary(ReadPlanSummaryArgs args, ToolCall call)
    {
        if (args == null || args.taskId() == null || args.taskId().isBlank()) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        Optional<JsonNode> plan = latestFinalizedPlan(args.taskId());
        if (plan.isEmpty()) {
            return ToolOutcome.Completed.error("no finalized plan recorded for task " + args.taskId());
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("plan", plan.get());
        out.set("conversationSummaries", mapper.createArrayNode());
        return serialiseNode(out);
    }

    // ── read_plan_conversation ──────────────────────────────────────────

    public record ReadPlanConversationArgs(
            @ToolParam(description = "Task id whose planning conversation to read.",
                    required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "Return messages strictly after this message id (pagination).",
                    required = false, wireName = "since_message_id")
            String sinceMessageId,
            @ToolParam(description = "Max messages to return (default 50, max 200).",
                    required = false)
            Integer limit) {}

    @AgentTool(
            name = "read_plan_conversation",
            description = "Read the full plan stage for this task, paginated: the trunk's seed "
                    + "conversation that led to the cut, followed by the brain agent's planning "
                    + "turns. Use this only when the plan summary isn't enough and you need the "
                    + "reasoning behind a decision.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = AgentRole.TASK,
            kinds = {ThreadKind.CLI_AGENT, ThreadKind.LOGIC_LOOP})
    public ToolOutcome readPlanConversation(ReadPlanConversationArgs args, ToolCall call)
    {
        if (args == null || args.taskId() == null || args.taskId().isBlank()) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        Optional<StageInstance> planStage = latestPlanStage(args.taskId());
        Optional<Thread> brain = threadStore.findBrainThreadByTask(args.taskId());
        if (planStage.isEmpty() || brain.isEmpty()) {
            return ToolOutcome.Completed.error("no planning conversation for task " + args.taskId());
        }
        // The brain thread is the full plan stage: the trunk's seed
        // conversation (copied onto it at creation) followed by the brain
        // agent's planning turns. Read it in seq order — one source, no merge.
        List<ThreadMessage> window = threadStore.listMessages(brain.get().id()).stream()
                .sorted(Comparator.comparingLong(ThreadMessage::seq))
                .toList();
        int limit = args.limit() == null ? CONVO_DEFAULT_LIMIT
                : Math.min(Math.max(1, args.limit()), CONVO_MAX_LIMIT);
        int start = 0;
        if (args.sinceMessageId() != null) {
            for (int i = 0; i < window.size(); i++) {
                if (window.get(i).id().equals(args.sinceMessageId())) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<ThreadMessage> page = window.subList(start, Math.min(start + limit, window.size()));
        boolean hasMore = start + limit < window.size();
        ObjectNode out = mapper.createObjectNode();
        out.set("messages", mapper.valueToTree(page));
        out.put("hasMore", hasMore);
        if (hasMore && !page.isEmpty()) {
            out.put("nextSinceId", page.get(page.size() - 1).id());
        }
        return serialiseNode(out);
    }

    // ── note_plan_concern ───────────────────────────────────────────────

    public record NotePlanConcernArgs(
            @ToolParam(description = "Task id whose plan the concern is about.",
                    required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "The free-text concern to surface to the user.",
                    required = true)
            String note) {}

    @AgentTool(
            name = "note_plan_concern",
            description = "Flag a concern that the approved plan was inadequate or missed "
                    + "something. Non-blocking: it surfaces a note to the user on the plan card "
                    + "and you continue your work. Only raise NEEDS_ATTENTION if you genuinely "
                    + "cannot proceed.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK,
            kinds = {ThreadKind.CLI_AGENT, ThreadKind.LOGIC_LOOP})
    public ToolOutcome notePlanConcern(NotePlanConcernArgs args, ToolCall call)
    {
        if (args == null || args.taskId() == null || args.taskId().isBlank()) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        if (args.note() == null || args.note().isBlank()) {
            return ToolOutcome.Completed.error("note is required");
        }
        StageInstance plan = latestPlanStage(args.taskId())
                .orElse(null);
        if (plan == null) {
            return ToolOutcome.Completed.error("no PlanStage for task " + args.taskId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", args.note().trim());
        stageStore.findActiveStage(args.taskId())
                .filter(s -> s.type() != StageType.PLAN_STAGE)
                .ifPresent(s -> payload.put("sourceStageId", s.id().toString()));
        payload.put("createdAt", Instant.now().toString());
        payload.put("status", "open");
        StageEvent event = stageStore.recordEvent(
                plan.id(), args.taskId(), StageEventType.PLAN_FOLLOWUP_NOTED, payload);
        ObjectNode out = mapper.createObjectNode();
        out.put("eventId", event.id().toString());
        return serialiseNode(out);
    }

    /** The task's most-recent PlanStage (open or closed), newest first. */
    private Optional<StageInstance> latestPlanStage(String taskId)
    {
        return stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.type() == StageType.PLAN_STAGE)
                .max(Comparator.comparing(StageInstance::openedAt));
    }

    /** The latest finalized PLAN_RECORDED across the task's PlanStages. */
    private Optional<JsonNode> latestFinalizedPlan(String taskId)
    {
        List<StageEvent> recorded = new ArrayList<>();
        for (StageInstance stage : stageStore.findStagesByTask(taskId)) {
            if (stage.type() != StageType.PLAN_STAGE) {
                continue;
            }
            stageStore.findEventsByStage(stage.id()).stream()
                    .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED)
                    .forEach(recorded::add);
        }
        return recorded.stream()
                .sorted(Comparator.comparing(StageEvent::eventAt).reversed())
                .map(e -> parse(e.payloadJson()))
                .filter(p -> "finalized".equals(p.path("status").asText(null)))
                .findFirst();
    }

    private JsonNode parse(String json)
    {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        }
        catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private ToolOutcome serialiseNode(JsonNode node)
    {
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(node));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise plan tool payload", e);
        }
    }

    private Map<String, Object> toMutableMap(JsonNode plan)
    {
        ObjectNode node = (ObjectNode) plan;
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                map.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class)));
        return map;
    }

    private ToolOutcome serialise(String payloadJson)
    {
        // The stored payload IS the persisted PlanResult JSON; echo it back so
        // the agent sees the assigned id / source.
        return ToolOutcome.Completed.ok(payloadJson == null ? "{}" : payloadJson);
    }
}
