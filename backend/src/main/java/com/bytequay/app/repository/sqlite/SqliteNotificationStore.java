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
import com.bytequay.app.repository.NotificationStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
class SqliteNotificationStore
        implements NotificationStore
{
    private final NotificationJpaRepository notifications;

    SqliteNotificationStore(NotificationJpaRepository notifications)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
    }

    @Override
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
        entity.setReadAtMs(notification.readAt() == null ? null : notification.readAt().toEpochMilli());
        notifications.save(entity);
    }

    @Override
    public Optional<Notification> findById(String id)
    {
        return notifications.findById(id).map(SqliteNotificationStore::toDomain);
    }

    @Override
    public List<Notification> listRecent(int limit)
    {
        return notifications.findAllByOrderByCreatedAtMsDesc(firstPage(limit))
                .stream()
                .map(SqliteNotificationStore::toDomain)
                .toList();
    }

    @Override
    public List<Notification> listByStatus(NotificationStatus status, int limit)
    {
        return notifications.findByStatusOrderByCreatedAtMsDesc(status.name(), firstPage(limit))
                .stream()
                .map(SqliteNotificationStore::toDomain)
                .toList();
    }

    @Override
    public List<Notification> listForThread(String threadId, int limit)
    {
        return notifications.findByThreadIdOrderByCreatedAtMsDesc(threadId, firstPage(limit))
                .stream()
                .map(SqliteNotificationStore::toDomain)
                .toList();
    }

    @Override
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
                e.getReadAtMs() == null ? null : Instant.ofEpochMilli(e.getReadAtMs()));
    }
}
