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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestNotificationService
{
    private final NotificationStore store = mock(NotificationStore.class);
    private final NotificationService service = new NotificationService(
            store, mock(ApplicationEventPublisher.class));

    @Test
    void actionableBadgeIncludesInterruptedApprovalsNewestFirst()
    {
        Notification unread = notification(
                "unread", NotificationStatus.UNREAD, "2026-05-22T12:00:00Z");
        Notification resolving = notification(
                "resolving", NotificationStatus.RESOLVING, "2026-05-22T12:01:00Z");
        when(store.listByStatus(eq(NotificationStatus.UNREAD), anyInt())).thenReturn(List.of(unread));
        when(store.listByStatus(eq(NotificationStatus.RESOLVING), anyInt())).thenReturn(List.of(resolving));

        assertThat(service.listUnread()).extracting(Notification::id)
                .containsExactly("resolving", "unread");
    }

    @Test
    void emptyActionableBadgeReadsBothOpenAttentionStates()
    {
        when(store.listByStatus(eq(NotificationStatus.UNREAD), anyInt())).thenReturn(List.of());
        when(store.listByStatus(eq(NotificationStatus.RESOLVING), anyInt())).thenReturn(List.of());

        assertThat(service.listUnread()).isEmpty();
    }

    @Test
    void resolvingRowsStayVisibleEvenWhenUnreadStreamIsBusy()
    {
        // A busy UNREAD list (50 newer rows) must not push older
        // RESOLVING rows out of the bell — the v4 design guarantees
        // interrupted approvals stay surfaced.
        List<Notification> unread = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            unread.add(notification(
                    "u-" + i, NotificationStatus.UNREAD,
                    "2026-05-22T14:" + String.format("%02d", i) + ":00Z"));
        }
        Notification resolving = notification(
                "old-resolving", NotificationStatus.RESOLVING, "2026-05-22T12:00:00Z");
        when(store.listByStatus(eq(NotificationStatus.UNREAD), anyInt())).thenReturn(unread);
        when(store.listByStatus(eq(NotificationStatus.RESOLVING), anyInt())).thenReturn(List.of(resolving));

        List<Notification> badge = service.listUnread();
        assertThat(badge).extracting(Notification::id).contains("old-resolving");
        assertThat(badge).hasSize(51);
    }

    @Test
    void claimResolutionDelegatesAtomicallyWithATimestamp()
    {
        when(store.claimResolution(eq("notif-1"), anyLong())).thenReturn(true);
        assertThat(service.claimResolution("notif-1")).isTrue();
        ArgumentCaptor<Long> ts = ArgumentCaptor.forClass(Long.class);
        verify(store).claimResolution(eq("notif-1"), ts.capture());
        // The atomic claim records a read-at stamp so the row gets a
        // "first read" timestamp even when the user never explicitly
        // marked-read before approving.
        assertThat(ts.getValue()).isGreaterThan(0L);
    }

    @Test
    void claimResolutionReturnsFalseWhenAlreadyResolved()
    {
        when(store.claimResolution(eq("notif-1"), anyLong())).thenReturn(false);
        assertThat(service.claimResolution("notif-1")).isFalse();
    }

    @Test
    void finishResolutionDelegatesToStore()
    {
        when(store.finishResolution("notif-1")).thenReturn(true);
        assertThat(service.finishResolution("notif-1")).isTrue();
        verify(store).finishResolution("notif-1");
    }

    @Test
    void markReadDelegatesToTheAtomicStoreUpdateWithATimestamp()
    {
        // The transition (UNREAD → READ, AWAITING_REVIEW skip, claim
        // protection) lives in the atomic store query; the service just
        // stamps a timestamp, delegates, and returns the refreshed row.
        Notification afterUpdate = new Notification(
                "u", NotificationKind.AUTO_FIX_DONE, "thread-1", "task-1",
                NotificationStatus.READ, "{}",
                Instant.parse("2026-05-22T12:00:00Z"),
                Instant.parse("2026-05-22T12:05:00Z"));
        when(store.markRead(eq("u"), anyLong())).thenReturn(true);
        when(store.findById("u")).thenReturn(Optional.of(afterUpdate));

        Notification next = service.markRead("u");

        assertThat(next.status()).isEqualTo(NotificationStatus.READ);
        assertThat(next.readAt()).isNotNull();
        ArgumentCaptor<Long> ts = ArgumentCaptor.forClass(Long.class);
        verify(store).markRead(eq("u"), ts.capture());
        assertThat(ts.getValue()).isGreaterThan(0L);
        verify(store, never()).save(any(Notification.class));
    }

    @Test
    void dismissDelegatesToTheStoreAndReturnsTheUpdatedRow()
    {
        Notification dismissed = notification("d", NotificationStatus.DISMISSED, "2026-05-22T12:00:00Z");
        when(store.dismiss(eq("d"), anyLong())).thenReturn(true);
        when(store.findById("d")).thenReturn(Optional.of(dismissed));

        assertThat(service.dismiss("d").status()).isEqualTo(NotificationStatus.DISMISSED);
        verify(store).dismiss(eq("d"), anyLong());
    }

    @Test
    void dismissRefusesToClobberAnInFlightResolutionClaim()
    {
        // The store update skips RESOLVING rows; when it reports no
        // change and the row is still RESOLVING, dismiss surfaces a 409
        // rather than a silent no-op that masquerades as success.
        Notification resolving = notification("r", NotificationStatus.RESOLVING, "2026-05-22T12:00:00Z");
        when(store.dismiss(eq("r"), anyLong())).thenReturn(false);
        when(store.findById("r")).thenReturn(Optional.of(resolving));

        assertThatThrownBy(() -> service.dismiss("r"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("resolution is in progress");
    }

    @Test
    void dismissOpenForTaskClearsBudgetAttentionAndGatesButSparesOthers()
    {
        // A terminal task (merged / canceled) must clear both its open publish
        // gate and its "needs you" budget-cap prompt so no stale card lingers
        // in the overview panel — while leaving another task's rows, already-read
        // rows, and passive activity untouched.
        Notification gate = row("gate", NotificationKind.AWAITING_REVIEW, "task-1", NotificationStatus.UNREAD);
        Notification budget = row("budget", NotificationKind.NEEDS_ATTENTION, "task-1", NotificationStatus.UNREAD);
        Notification otherTask = row("other", NotificationKind.NEEDS_ATTENTION, "task-2", NotificationStatus.UNREAD);
        Notification alreadyRead = row("read", NotificationKind.NEEDS_ATTENTION, "task-1", NotificationStatus.READ);
        Notification passive = row("passive", NotificationKind.PASSIVE, "task-1", NotificationStatus.UNREAD);
        when(store.listForThread(eq("thread-1"), anyInt()))
                .thenReturn(List.of(gate, budget, otherTask, alreadyRead, passive));

        service.dismissOpenForTask("thread-1", "task-1");

        verify(store).dismiss(eq("gate"), anyLong());
        verify(store).dismiss(eq("budget"), anyLong());
        verify(store, never()).dismiss(eq("other"), anyLong());
        verify(store, never()).dismiss(eq("read"), anyLong());
        verify(store, never()).dismiss(eq("passive"), anyLong());
    }

    @Test
    void releaseResolutionDelegatesToStore()
    {
        when(store.releaseResolution("notif-1")).thenReturn(true);
        assertThat(service.releaseResolution("notif-1")).isTrue();
        verify(store).releaseResolution("notif-1");
    }

    @Test
    void markReadDoesNothingForAlreadyTimestampedReadRow()
    {
        Notification existing = new Notification(
                "r", NotificationKind.AWAITING_REVIEW, "thread-1", "task-1",
                NotificationStatus.READ, "{}",
                Instant.parse("2026-05-22T12:00:00Z"),
                Instant.parse("2026-05-22T12:01:00Z"));
        when(store.findById("r")).thenReturn(Optional.of(existing));

        Notification next = service.markRead("r");

        assertThat(next).isSameAs(existing);
        verify(store, never()).save(any(Notification.class));
    }

    @Test
    void markReadDoesNothingForResolvingRow()
    {
        // RESOLVING is a terminal-ish state for the bell — the user
        // must approve or discard to clear it; markRead must not
        // smudge the state machine.
        Notification existing = new Notification(
                "x", NotificationKind.AWAITING_REVIEW, "thread-1", "task-1",
                NotificationStatus.RESOLVING, "{}",
                Instant.parse("2026-05-22T12:00:00Z"),
                Instant.parse("2026-05-22T12:01:00Z"));
        when(store.findById("x")).thenReturn(Optional.of(existing));

        Notification next = service.markRead("x");

        assertThat(next).isSameAs(existing);
        verify(store, never()).save(any(Notification.class));
    }

    @Test
    void canonicalNotificationReusesItsStableDedupKey()
    {
        Notification existing = new Notification(
                "existing",
                NotificationKind.NEEDS_ATTENTION,
                "trunk-1",
                null,
                NotificationStatus.UNREAD,
                "{}",
                Instant.parse("2026-07-17T00:00:00Z"),
                null,
                "ws-1",
                "budget",
                "Session paused",
                "Budget reached",
                "#/workspace/ws-1/sessions/run-1",
                "session-budget:run-1");
        when(store.findByDedupKey("session-budget:run-1"))
                .thenReturn(Optional.of(existing));

        Notification result = service.createCanonical(
                NotificationKind.NEEDS_ATTENTION,
                "ws-1",
                "trunk-1",
                null,
                "budget",
                "Session paused",
                "Budget reached",
                "#/workspace/ws-1/sessions/run-1",
                "session-budget:run-1",
                "{}");

        assertThat(result).isSameAs(existing);
        verify(store, never()).save(any(Notification.class));
    }

    @Test
    void canonicalNotificationPersistsWorkspaceFieldsAndDeepLink()
    {
        when(store.findByDedupKey("event-42"))
                .thenReturn(Optional.empty());

        service.createCanonical(
                NotificationKind.AUTO_FIX_DONE,
                "ws-1",
                "trunk-1",
                "task-1",
                "ci",
                "CI recovered",
                "All checks pass",
                "#/workspace/ws-1/trunks/trunk-1",
                "event-42",
                "{}");

        ArgumentCaptor<Notification> saved =
                ArgumentCaptor.forClass(Notification.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().workspaceId()).isEqualTo("ws-1");
        assertThat(saved.getValue().publicType()).isEqualTo("ci");
        assertThat(saved.getValue().itemPath())
                .isEqualTo("#/workspace/ws-1/trunks/trunk-1");
        assertThat(saved.getValue().dedupKey()).isEqualTo("event-42");
    }

    private static Notification notification(
            String id, NotificationStatus status, String createdAt)
    {
        return new Notification(
                id, NotificationKind.AWAITING_REVIEW, "thread-1", "task-1",
                status, "{}", Instant.parse(createdAt), null);
    }

    private static Notification row(
            String id, NotificationKind kind, String taskId, NotificationStatus status)
    {
        return new Notification(
                id, kind, "thread-1", taskId, status, "{}",
                Instant.parse("2026-07-22T12:00:00Z"), null);
    }
}
