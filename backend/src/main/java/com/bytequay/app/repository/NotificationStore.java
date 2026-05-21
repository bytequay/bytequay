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
package com.bytequay.app.repository;

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationStatus;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for the {@code notifications} surface. */
public interface NotificationStore
{
    /** Insert or update a notification by primary key. */
    void save(Notification notification);

    Optional<Notification> findById(String id);

    /** Recent notifications, newest-{@code created_at_ms} first, up
     *  to {@code limit}. Drives the bell / notification center list. */
    List<Notification> listRecent(int limit);

    /** Notifications still {@link NotificationStatus#UNREAD},
     *  newest-first. Powers the unread badge count + active toasts. */
    List<Notification> listByStatus(NotificationStatus status, int limit);

    /** Newest-first feed for a single thread (the auto* per-thread row). */
    List<Notification> listForThread(String threadId, int limit);

    /** Permanent removal. Notifications cascade on thread/task delete
     *  via FK, but explicit dismiss can also delete. */
    void delete(String id);
}
