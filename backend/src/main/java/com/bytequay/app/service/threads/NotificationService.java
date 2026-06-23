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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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

    /** A shipped PR is ready to merge (CI green, no unresolved comments,
     *  reviewers approved). De-dup is the caller's job via the task's
     *  merge-notification sentinel; this just writes the row. */
    public Notification notifyReadyToMerge(String threadId, String taskId, String payloadJson)
    {
        return create(NotificationKind.READY_TO_MERGE, threadId, taskId, payloadJson);
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

    /** Notifications needing attention in the bell badge. A claimed
     *  publish that did not finalize stays visible as RESOLVING until
     *  the user explicitly finishes or discards it. RESOLVING rows
     *  are never dropped by the page cap — a busy UNREAD stream must
     *  not push an interrupted-approval out of the user's view. */
    public List<Notification> listUnread()
    {
        List<Notification> resolving = store.listByStatus(NotificationStatus.RESOLVING, INTERRUPTED_VISIBILITY_CAP);
        List<Notification> unread = store.listByStatus(NotificationStatus.UNREAD, DEFAULT_LIMIT);
        return Stream.concat(resolving.stream(), unread.stream())
                .sorted(Comparator.comparing(Notification::createdAt).reversed())
                .toList();
    }

    /** Hard cap on how many RESOLVING rows the bell can carry. Real
     *  installs will see single-digit counts; the cap is here to keep
     *  a runaway storage state from blowing up the bell list. */
    private static final int INTERRUPTED_VISIBILITY_CAP = 200;

    /** Per-thread feed (the {@code auto*} row in the threads list). */
    public List<Notification> listForThread(String threadId)
    {
        return store.listForThread(threadId, DEFAULT_LIMIT);
    }

    /** Patch UNREAD to READ and stamp readAt. Legacy READ rows without
     *  a timestamp are repaired. AWAITING_REVIEW rows and rows holding a
     *  RESOLVING claim are left untouched — the atomic update can't race
     *  a concurrent claim into a stale READ. A no-op (terminal/parked
     *  row) returns the row unchanged; a missing row is a 404. */
    public Notification markRead(String id)
    {
        store.markRead(id, Instant.now().toEpochMilli());
        return require(id);
    }

    /** Patch status → DISMISSED. The row stays around so a swipe
     *  isn't accidentally permanent; use {@link #delete} to drop it
     *  for real. A row holding an in-flight RESOLVING claim cannot be
     *  dismissed (409) — it must be finished or discarded through the
     *  publish gate so the claim isn't silently clobbered. */
    public Notification dismiss(String id)
    {
        boolean dismissed = store.dismiss(id, Instant.now().toEpochMilli());
        Notification current = require(id);
        if (!dismissed && current.status() == NotificationStatus.RESOLVING) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "cannot dismiss a notification while its resolution is in progress: " + id);
        }
        return current;
    }

    /**
     * Atomically reserve an open notification for approve/discard.
     * Resolution writes RESOLVING up front so retries cannot repeat a
     * remote side effect while local finalization is pending.
     */
    public boolean claimResolution(String id)
    {
        return store.claimResolution(id, Instant.now().toEpochMilli());
    }

    /** Atomically close an active resolution claim. */
    public boolean finishResolution(String id)
    {
        return store.finishResolution(id);
    }

    /** Release an active claim back to UNREAD after the approve was
     *  rejected before touching the remote, so the row stays actionable
     *  for a retry instead of pinning in RESOLVING. */
    public boolean releaseResolution(String id)
    {
        return store.releaseResolution(id);
    }

    public void delete(String id)
    {
        store.delete(id);
    }

    /** Single-row lookup; returns empty when the id is unknown so a
     *  caller (e.g. the publish gate's approve endpoint) can decide
     *  whether absence is a 404 or a no-op. */
    public Optional<Notification> find(String id)
    {
        return store.findById(id);
    }

    /** Rewrite a notification's free-form payload in place, leaving its
     *  kind / status / timestamps untouched. Used by the publish gate to
     *  let the user edit a parked proposal's editable copy before
     *  approving. Returns the persisted row, or 404 when the id is
     *  unknown. */
    public Notification updatePayload(String id, String payloadJson)
    {
        Notification existing = require(id);
        Notification updated = new Notification(
                existing.id(),
                existing.kind(),
                existing.threadId(),
                existing.taskId(),
                existing.status(),
                payloadJson == null ? "{}" : payloadJson,
                existing.createdAt(),
                existing.readAt());
        store.save(updated);
        return updated;
    }

    private Notification require(String id)
    {
        Optional<Notification> existing = store.findById(id);
        return existing.orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(404), "no notification: " + id));
    }
}
