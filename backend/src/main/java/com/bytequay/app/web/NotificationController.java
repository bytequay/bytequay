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
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.PublishService;
import com.bytequay.app.service.threads.PublishService.PublishResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface over the {@code notifications} table. Three list
 * shapes serve the three things the UI cares about:
 *
 *   * No filter   → notification center (newest first).
 *   * status=UNREAD → bell badge count + active toasts.
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

    public NotificationController(
            NotificationService notifications,
            PublishService publishes)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.publishes = requireNonNull(publishes, "publishes is null");
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
        return notifications.dismiss(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id)
    {
        notifications.delete(id);
    }

    /**
     * Approve a parked AWAITING_REVIEW publish: the backend runs the
     * deferred {@code git push} / {@code createIssueComment} and the
     * task transitions to COMPLETED on success. Optional {@code
     * editedBody} (post_comment only) replaces the parked body so the
     * user can tweak copy in the review pane before publishing.
     */
    @PostMapping("/{id}/approve")
    public PublishResult approve(
            @PathVariable String id,
            @RequestBody(required = false) JsonNode body)
    {
        String editedBody = body == null ? null : body.path("editedBody").asText(null);
        return publishes.approve(id, editedBody);
    }

    /** Discard a parked AWAITING_REVIEW publish. Marks the
     *  notification DISMISSED, transitions the task to COMPLETED, and
     *  writes an audit row — the proposed side effect never runs. */
    @PostMapping("/{id}/discard")
    public PublishResult discard(@PathVariable String id)
    {
        return publishes.discard(id);
    }
}
