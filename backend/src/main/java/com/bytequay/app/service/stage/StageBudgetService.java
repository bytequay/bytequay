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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.stage.StageMetrics.AutoPushBudget;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskAutoPushEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Owns the per-stage-instance auto-push budget. A fresh
 * {@code CiFixingStage} starts with {@link #DEFAULT_AUTO_PUSH_BUDGET}
 * autonomous pushes; each {@link TaskAutoPushEvent} spends one. On
 * exhaustion the stage records the audit event, flags itself exhausted,
 * and fires a needs-attention notification so the user can extend the
 * budget or fall back to per-push review (see {@code StageBudgetController}).
 *
 * <p>This is the per-instance accounting from the design; the task-level
 * consecutive-auto-push cap the phase machine enforces is a separate,
 * coarser guard that parks the phase.
 */
@Component
public class StageBudgetService
{
    /** Default autonomous pushes per ci-fixing stage instance. */
    public static final int DEFAULT_AUTO_PUSH_BUDGET = 5;

    private static final Logger log = LoggerFactory.getLogger(StageBudgetService.class);

    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final NotificationService notifications;
    private final ObjectMapper mapper;

    public StageBudgetService(
            StageStore stageStore,
            TaskStore taskStore,
            NotificationService notifications,
            ObjectMapper mapper)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Seed a freshly-opened monitor stage's metrics: ci-fixing gets a
     * budget and autonomous pushes; review-monitor gates every push. A
     * no-op for every other stage type.
     */
    @Transactional
    public void onStageOpened(StageInstance stage)
    {
        switch (stage.type()) {
            case CI_FIXING_STAGE -> writeMetrics(stage.id(),
                    StageMetrics.empty().withBudget(AutoPushBudget.fresh(DEFAULT_AUTO_PUSH_BUDGET)));
            case REVIEW_MONITOR_STAGE -> writeMetrics(stage.id(),
                    StageMetrics.empty().withInternalReviewEnabled(true));
            default -> {
                // No metrics seeded for non-monitor stages.
            }
        }
    }

    @EventListener
    @Transactional
    public void onAutoPush(TaskAutoPushEvent event)
    {
        Optional<StageInstance> active = stageStore.findActiveStage(event.taskId())
                .filter(s -> s.type() == StageType.CI_FIXING_STAGE);
        if (active.isEmpty()) {
            return;
        }
        StageInstance stage = active.get();
        StageMetrics metrics = readMetrics(stage.id());
        if (metrics.autoPushBudget() == null) {
            return;
        }
        AutoPushBudget spent = metrics.autoPushBudget().decremented();
        StageMetrics updated = metrics.withBudget(spent);
        if (spent.exhausted() && !metrics.budgetExhausted()) {
            updated = updated.withBudgetExhausted(true);
            onExhausted(event.taskId(), stage.id(), spent);
        }
        writeMetrics(stage.id(), updated);
    }

    private void onExhausted(String taskId, UUID stageId, AutoPushBudget budget)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "auto_push_budget_exhausted");
        payload.put("limit", budget.limit());
        payload.put("used", budget.used());
        stageStore.recordEvent(stageId, taskId, StageEventType.BUDGET_EXHAUSTED, payload);

        taskStore.findTaskById(taskId).ifPresent(task ->
                notifications.notifyNeedsAttention(task.threadId(), taskId, toJson(payload)));
    }

    StageMetrics readMetrics(UUID stageId)
    {
        String json = stageStore.findMetricsJson(stageId).orElse(null);
        if (json == null || json.isBlank()) {
            return StageMetrics.empty();
        }
        try {
            return mapper.readValue(json, StageMetrics.class);
        }
        catch (JsonProcessingException e) {
            log.warn("unparseable metrics_json for stage {}: {}", stageId, e.getMessage());
            return StageMetrics.empty();
        }
    }

    void writeMetrics(UUID stageId, StageMetrics metrics)
    {
        stageStore.updateMetricsJson(stageId, toJson(metrics));
    }

    private String toJson(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("stage metrics JSON serialise failed", e);
        }
    }
}
