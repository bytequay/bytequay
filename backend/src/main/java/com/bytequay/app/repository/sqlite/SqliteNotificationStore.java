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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
public class SqliteNotificationStore
{
    private final NotificationJpaRepository notifications;

    SqliteNotificationStore(NotificationJpaRepository notifications)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
    }

    @Transactional
    public void save(Notification notification)
    {
        NotificationEntity entity = notifications.findById(notification.id()).orElseGet(NotificationEntity::new);
        entity.setId(notification.id());
        entity.setKind(notification.kind().name());
        entity.setThreadId(notification.threadId());
        entity.setTaskId(notification.taskId());
        entity.setStatus(notification.status().name());
        entity.setPayloadJson(notification.payloadJson() == null ? "{}" : notification.payloadJson());
        entity.setCreatedAtMs(notification.createdAt().toEpochMilli());
        entity.setReadAtMs(Timestamps.epochMilli(notification.readAt()));
        entity.setWorkspaceId(notification.workspaceId());
        entity.setPublicType(notification.publicType());
        entity.setTitle(notification.title());
        entity.setSummary(notification.summary());
        entity.setItemPath(notification.itemPath());
        entity.setDedupKey(notification.dedupKey());
        notifications.save(entity);
    }

    @Transactional
    public boolean claimResolution(String id, long readAtMs)
    {
        return notifications.claimResolution(id, readAtMs) == 1;
    }

    @Transactional
    public boolean finishResolution(String id)
    {
        return notifications.finishResolution(id) == 1;
    }

    @Transactional
    public boolean resolveOpen(String id)
    {
        return notifications.resolveOpen(id) == 1;
    }

    @Transactional
    public boolean releaseResolution(String id)
    {
        return notifications.releaseResolution(id) == 1;
    }

    @Transactional
    public boolean markRead(String id, long readAtMs)
    {
        return notifications.markRead(id, readAtMs) == 1;
    }

    @Transactional
    public boolean dismiss(String id, long readAtMs)
    {
        return notifications.dismiss(id, readAtMs) == 1;
    }

    public Optional<Notification> findById(String id)
    {
        return notifications.findById(id).map(SqliteNotificationStore::toDomain);
    }

    public List<Notification> listRecent(int limit)
    {
        return notifications.findAllByOrderByCreatedAtMsDesc(firstPage(limit))
                .stream()
                .map(SqliteNotificationStore::toDomain)
                .toList();
    }

    public List<Notification> listByStatus(NotificationStatus status, int limit)
    {
        return notifications.findByStatusOrderByCreatedAtMsDesc(status.name(), firstPage(limit))
                .stream()
                .map(SqliteNotificationStore::toDomain)
                .toList();
    }

    public List<Notification> listForThread(String threadId, int limit)
    {
        return notifications.findByThreadIdOrderByCreatedAtMsDesc(threadId, firstPage(limit))
                .stream()
                .map(SqliteNotificationStore::toDomain)
                .toList();
    }

    public List<Notification> listForWorkspace(String workspaceId, int limit)
    {
        return notifications.findByWorkspaceIdOrderByCreatedAtMsDesc(
                        workspaceId, firstPage(limit))
                .stream()
                .map(SqliteNotificationStore::toDomain)
                .toList();
    }

    @Transactional
    public int markAllReadForWorkspace(String workspaceId, long readAtMs)
    {
        return notifications.markAllReadForWorkspace(workspaceId, readAtMs);
    }

    public Optional<Notification> findByDedupKey(String dedupKey)
    {
        return notifications.findByDedupKey(dedupKey)
                .map(SqliteNotificationStore::toDomain);
    }

    @Transactional
    public void delete(String id)
    {
        notifications.deleteById(id);
    }

    private static Notification toDomain(NotificationEntity e)
    {
        return new Notification(
                e.getId(),
                NotificationKind.valueOf(e.getKind()),
                e.getThreadId(),
                e.getTaskId(),
                NotificationStatus.valueOf(e.getStatus()),
                e.getPayloadJson(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Timestamps.instant(e.getReadAtMs()),
                e.getWorkspaceId(),
                e.getPublicType(),
                e.getTitle(),
                e.getSummary(),
                e.getItemPath(),
                e.getDedupKey());
    }
}
