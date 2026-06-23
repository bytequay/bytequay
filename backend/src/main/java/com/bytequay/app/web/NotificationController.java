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
package com.bytequay.app.web;

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.PublishService;
import com.bytequay.app.service.threads.PublishService.PublishResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface over the {@code notifications} table. Three list
 * shapes serve the three things the UI cares about:
 *
 *   * No filter   → notification center (newest first).
 *   * status=UNREAD → bell badge count + unresolved actions
 *     (including interrupted publish resolution).
 *   * threadId=…  → the {@code auto*} per-thread feed.
 *
 * Patches: {@code POST /{id}/read} flips status to READ + stamps
 * read_at; {@code POST /{id}/dismiss} sets DISMISSED. {@code DELETE}
 * drops the row permanently.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController
{
    private final NotificationService notifications;
    private final PublishService publishes;
    private final TaskStore tasks;

    public NotificationController(
            NotificationService notifications,
            PublishService publishes,
            TaskStore tasks)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.publishes = requireNonNull(publishes, "publishes is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @GetMapping
    public List<Notification> list(
            @RequestParam(value = "status", required = false) NotificationStatus status,
            @RequestParam(value = "threadId", required = false) String threadId)
    {
        if (threadId != null && !threadId.isBlank()) {
            return notifications.listForThread(threadId);
        }
        if (status != null) {
            return status == NotificationStatus.UNREAD
                    ? notifications.listUnread()
                    : notifications.listRecent();
        }
        return notifications.listRecent();
    }

    @PostMapping("/{id}/read")
    public Notification markRead(@PathVariable String id)
    {
        return notifications.markRead(id);
    }

    @PostMapping("/{id}/dismiss")
    public Notification dismiss(@PathVariable String id)
    {
        Notification existing = notifications.find(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no notification: " + id));
        if (isOpenParkedNotification(existing)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), blockedReason(existing));
        }
        return notifications.dismiss(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id)
    {
        Notification existing = notifications.find(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no notification: " + id));
        if (isOpenParkedNotification(existing)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), blockedReason(existing));
        }
        notifications.delete(id);
    }

    /**
     * Approve a parked AWAITING_REVIEW publish: the backend runs the
     * deferred action and resolves the task according to the proposal
     * type. Optional {@code editedBody} replaces editable parked copy;
     * {@code expectedAction} protects the visible approve button from
     * resolving a payload that changed after it was rendered.
     */
    @PostMapping("/{id}/approve")
    public PublishResult approve(
            @PathVariable String id,
            @RequestBody(required = false) JsonNode body)
    {
        String editedBody = optionalText(body, "editedBody");
        String expectedAction = optionalText(body, "expectedAction");
        return publishes.approve(id, editedBody, expectedAction);
    }

    /**
     * Edit a parked ship proposal's PR title/body before approving it.
     * Body: {@code {prTitle, prBody}} (both optional). Returns the updated
     * notification so the gate can re-render. Rejects when the row isn't
     * an open ship proposal.
     */
    @PostMapping("/{id}/ship-description")
    public Notification updateShipDescription(
            @PathVariable String id,
            @RequestBody(required = false) JsonNode body)
    {
        String prTitle = optionalText(body, "prTitle");
        String prBody = optionalText(body, "prBody");
        return publishes.updateShipDescription(id, prTitle, prBody);
    }

    /** Discard a parked AWAITING_REVIEW publish. Advance proposals
     *  return to local idle work; completed publish proposals close.
     *  The proposed remote side effect never runs. */
    @PostMapping("/{id}/discard")
    public PublishResult discard(
            @PathVariable String id,
            @RequestBody(required = false) JsonNode body)
    {
        String expectedAction = optionalText(body, "expectedAction");
        return publishes.discard(id, expectedAction);
    }

    private static String optionalText(JsonNode body, String name)
    {
        if (body == null) {
            return null;
        }
        JsonNode value = body.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Human-readable reason a parked row can't be dismissed/deleted,
     *  tailored to the kind so the UI can show an actionable hint. */
    private static String blockedReason(Notification notification)
    {
        if (notification.kind() == NotificationKind.NEEDS_ATTENTION) {
            // Don't prescribe "jump in" — the user may have already done
            // that (jump-in transfers the lease but doesn't clear
            // NEEDS_ATTENTION). Point at the goal: resolve the task.
            return "this task still needs attention — resolve it in its thread before dismissing";
        }
        return "parked notification must be resolved from its review flow";
    }

    private boolean isOpenParkedNotification(Notification notification)
    {
        if (!isOpenStatus(notification.status())) {
            return false;
        }
        // AWAITING_REVIEW rows always have a structured approve/discard
        // flow that must reject a generic dismiss.
        if (notification.kind() == NotificationKind.AWAITING_REVIEW) {
            return true;
        }
        // NEEDS_ATTENTION rows are dismissible once they're just
        // informational — e.g. a CI-failure row whose task already
        // shipped. But while the underlying task is still in
        // NEEDS_ATTENTION it is actively gating the thread (see
        // McpController#isThreadParked); dismissing the bell row then
        // would clear the only affordance pointing at a stuck task while
        // the agent stays blocked. Keep those undismissible until the
        // task is resolved.
        if (notification.kind() == NotificationKind.NEEDS_ATTENTION) {
            return taskStillNeedsAttention(notification.taskId());
        }
        return false;
    }

    private static boolean isOpenStatus(NotificationStatus status)
    {
        return status == NotificationStatus.UNREAD
                || status == NotificationStatus.READ
                || status == NotificationStatus.RESOLVING;
    }

    private boolean taskStillNeedsAttention(String taskId)
    {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        return tasks.findTaskById(taskId)
                .map(task -> task.status() == TaskStatus.NEEDS_ATTENTION)
                .orElse(false);
    }
}
