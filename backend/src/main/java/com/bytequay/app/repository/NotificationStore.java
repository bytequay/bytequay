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

    /**
     * Atomically move an unread/read row to RESOLVING before a gated
     * action resolves it. Returns false when another request already
     * claimed or dismissed the row.
     */
    boolean claimResolution(String id, long readAtMs);

    /** Complete a claimed gated action. Returns false if the row was
     *  deleted or no longer holds the active claim. */
    boolean finishResolution(String id);

    /** Release a claim back to UNREAD when the approve was rejected
     *  before any remote state changed. Returns false unless the row
     *  currently holds a RESOLVING claim. */
    boolean releaseResolution(String id);

    /** Atomically flip an UNREAD (or legacy timestamp-less READ) row to
     *  READ. AWAITING_REVIEW rows and rows holding a RESOLVING claim are
     *  left untouched. Returns false when nothing was updated. */
    boolean markRead(String id, long readAtMs);

    /** Atomically set a row to DISMISSED, never clobbering an in-flight
     *  RESOLVING claim. Returns false when nothing was updated. */
    boolean dismiss(String id, long readAtMs);

    Optional<Notification> findById(String id);

    /** Recent notifications, newest-{@code created_at_ms} first, up
     *  to {@code limit}. Drives the bell / notification center list. */
    List<Notification> listRecent(int limit);

    /** Notifications for one status, newest-first. The notification
     *  service combines open statuses for its actionable badge feed. */
    List<Notification> listByStatus(NotificationStatus status, int limit);

    /** Newest-first feed for a single thread (the auto* per-thread row). */
    List<Notification> listForThread(String threadId, int limit);

    /** Permanent removal. Notifications cascade on thread/task delete
     *  via FK, but explicit dismiss can also delete. */
    void delete(String id);
}
