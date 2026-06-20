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

import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Fires the ready-to-merge notification once a shipped PR reaches the
 * merge-ready state — CI green, no unresolved remote comments, reviewers
 * approved — and auto-resets when any condition breaks. The first monitor
 * sweep to notice wins an atomic compare-and-set on the task's sentinel,
 * so the two monitor loops never both notify for the same ready state.
 */
@Component
public class ReadyToMergeService
{
    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final NotificationService notifications;
    private final ObjectMapper mapper;

    public ReadyToMergeService(
            TaskStore taskStore,
            StageStore stageStore,
            NotificationService notifications,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Re-evaluate the ready-to-merge state for a shipped task against its
     *  freshly-fetched PR detail, firing or auto-resetting the notification. */
    @Transactional
    public void evaluate(Task task, PullRequestDetail detail)
    {
        if (detail == null) {
            return;
        }
        boolean armed = taskStore.mergeNotificationSentAt(task.id()).isPresent();

        // A terminal PR can't be "ready to merge"; just disarm.
        if (detail.merged() || isClosed(detail)) {
            if (armed) {
                taskStore.clearMergeNotificationSent(task.id());
            }
            return;
        }

        boolean ready = detail.ciStatus() == CiStatus.PASSING
                && reviewersApproved(detail)
                && noUnresolvedRemoteComments(task.id());

        if (ready && !armed) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", "ready_to_merge");
            payload.put("repo", detail.repo());
            payload.put("number", detail.number());
            if (taskStore.markMergeNotificationSentIfUnset(task.id(), Instant.now())) {
                notifications.notifyReadyToMerge(task.threadId(), task.id(), toJson(payload));
                recordOnActiveStage(task.id(), StageEventType.NOTIFY_FIRED, payload);
            }
            else {
                recordOnActiveStage(task.id(), StageEventType.NOTIFY_SKIPPED,
                        Map.of("reason", "already_sent"));
            }
        }
        else if (!ready && armed) {
            taskStore.clearMergeNotificationSent(task.id());
            recordOnActiveStage(task.id(), StageEventType.NOTIFY_SKIPPED,
                    Map.of("reason", "conditions_broke"));
        }
    }

    private boolean noUnresolvedRemoteComments(String taskId)
    {
        return stageStore.findUnresolvedComments(taskId).stream()
                .noneMatch(c -> c.source() == ReviewCommentSource.REMOTE_REVIEWER);
    }

    private void recordOnActiveStage(String taskId, StageEventType type, Map<String, Object> payload)
    {
        stageStore.findActiveStage(taskId).ifPresent(stage ->
                stageStore.recordEvent(stage.id(), taskId, type, payload));
    }

    private static boolean reviewersApproved(PullRequestDetail detail)
    {
        return detail.approvalCount() > 0
                && detail.changesRequestedCount() == 0
                && detail.pendingReviewerCount() == 0;
    }

    private static boolean isClosed(PullRequestDetail detail)
    {
        return detail.state() != null && "closed".equalsIgnoreCase(detail.state());
    }

    private String toJson(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
