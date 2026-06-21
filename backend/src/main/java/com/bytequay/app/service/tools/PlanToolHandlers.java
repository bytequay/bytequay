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
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.repository.StageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
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

    private final StageStore stageStore;
    private final ObjectMapper mapper;

    public PlanToolHandlers(StageStore stageStore, ObjectMapper mapper)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public record RecordPlanArgs(
            @ToolParam(description = "Task id whose open PlanStage this plan is recorded on.",
                    required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "The structured PlanResult object: status, understanding "
                    + "(summary, affectedComponents, existingPatterns, constraints), intent "
                    + "(summary, numbered steps, validationStrategy, pushStrategy), and signals "
                    + "(riskLevel, riskNotes, componentsCount, estimatedComplexity, expectedGain). "
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
