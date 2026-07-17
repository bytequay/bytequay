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
import com.bytequay.app.service.threads.NotificationMuteService;
import com.bytequay.app.service.threads.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Canonical workspace-scoped aliases for the unified notification stream. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/notifications")
public class WorkspaceNotificationController
{
    private final NotificationService notifications;
    private final NotificationMuteService mutes;

    public WorkspaceNotificationController(
            NotificationService notifications,
            NotificationMuteService mutes)
    {
        this.notifications = requireNonNull(
                notifications, "notifications is null");
        this.mutes = requireNonNull(mutes, "mutes is null");
    }

    @GetMapping
    public List<Notification> list(@PathVariable String workspaceId)
    {
        return notifications.listForWorkspace(workspaceId).stream()
                .filter(notification -> !mutes.muted(
                        workspaceId, notification.publicType()))
                .toList();
    }

    @PostMapping("/mark-all-read")
    public int markAllRead(@PathVariable String workspaceId)
    {
        return notifications.markAllReadForWorkspace(workspaceId);
    }

    @GetMapping("/mutes")
    public List<NotificationMuteService.MuteRule> listMutes(
            @PathVariable String workspaceId)
    {
        return mutes.list(workspaceId);
    }

    @PostMapping("/mutes/{publicType}")
    public NotificationMuteService.MuteRule setMute(
            @PathVariable String workspaceId,
            @PathVariable String publicType,
            @RequestBody MuteRequest request)
    {
        return mutes.set(workspaceId, publicType, request.muted());
    }

    public record MuteRequest(boolean muted) {}
}
