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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.repository.NotificationStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Write durable notification rows when a headless run parks at a
 * gate, and read them back for the bell + notification center +
 * per-thread {@code auto*} feed.
 *
 * <p>This is the bounded service surface; the automation runtime
 * writes through {@link #notifyAwaitingReview} / {@link #notifyNeedsAttention}
 * / {@link #notifyAutoFixDone}, the controller reads through the
 * list methods and patches via {@link #markRead} / {@link #dismiss}.
 */
@Service
public class NotificationService
{
    /** Default page size for the bell + per-thread feeds; large
     *  enough to cover a busy hour without paginating. */
    private static final int DEFAULT_LIMIT = 50;

    private final NotificationStore store;

    public NotificationService(NotificationStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /** Headless run parked with a proposed diff + reply at the
     *  publish gate. {@code payloadJson} is renderer-defined. */
    public Notification notifyAwaitingReview(String threadId, String taskId, String payloadJson)
    {
        return create(NotificationKind.AWAITING_REVIEW, threadId, taskId, payloadJson);
    }

    /** Headless run hit a conflict / question / push rejection and
     *  needs the human to weigh in. */
    public Notification notifyNeedsAttention(String threadId, String taskId, String payloadJson)
    {
        return create(NotificationKind.NEEDS_ATTENTION, threadId, taskId, payloadJson);
    }

    /** Informational: auto-fix / ship-and-continue completed without
     *  needing the human's attention. */
    public Notification notifyAutoFixDone(String threadId, String taskId, String payloadJson)
    {
        return create(NotificationKind.AUTO_FIX_DONE, threadId, taskId, payloadJson);
    }

    public Notification create(NotificationKind kind, String threadId, String taskId, String payloadJson)
    {
        requireNonNull(kind, "kind is null");
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                kind,
                threadId,
                taskId,
                NotificationStatus.UNREAD,
                payloadJson == null ? "{}" : payloadJson,
                Instant.now(),
                /* readAt */ null);
        store.save(notification);
        return notification;
    }

    /** Bell list — newest first. */
    public List<Notification> listRecent()
    {
        return store.listRecent(DEFAULT_LIMIT);
    }

    /** UNREAD only — drives the bell badge count. */
    public List<Notification> listUnread()
    {
        return store.listByStatus(NotificationStatus.UNREAD, DEFAULT_LIMIT);
    }

    /** Per-thread feed (the {@code auto*} row in the threads list). */
    public List<Notification> listForThread(String threadId)
    {
        return store.listForThread(threadId, DEFAULT_LIMIT);
    }

    /** Patch UNREAD → READ + stamp readAt. No-op when already read. */
    public Notification markRead(String id)
    {
        Notification existing = require(id);
        if (existing.status() == NotificationStatus.READ) {
            return existing;
        }
        Notification next = new Notification(
                existing.id(), existing.kind(), existing.threadId(), existing.taskId(),
                NotificationStatus.READ,
                existing.payloadJson(),
                existing.createdAt(),
                Instant.now());
        store.save(next);
        return next;
    }

    /** Patch status → DISMISSED. The row stays around so a swipe
     *  isn't accidentally permanent; use {@link #delete} to drop it
     *  for real. */
    public Notification dismiss(String id)
    {
        Notification existing = require(id);
        Notification next = new Notification(
                existing.id(), existing.kind(), existing.threadId(), existing.taskId(),
                NotificationStatus.DISMISSED,
                existing.payloadJson(),
                existing.createdAt(),
                existing.readAt() != null ? existing.readAt() : Instant.now());
        store.save(next);
        return next;
    }

    public void delete(String id)
    {
        store.delete(id);
    }

    private Notification require(String id)
    {
        Optional<Notification> existing = store.findById(id);
        return existing.orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(404), "no notification: " + id));
    }
}
