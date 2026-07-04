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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCheck;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.repository.LocalPRStore;
import com.bytequay.app.service.review.DevReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * State-machine coverage for {@link LocalPRService}: {@link LocalPRService#transition}
 * validation against {@link LocalPR#ALLOWED_TRANSITIONS} and the invariant that
 * every accepted flip writes a {@code status} timeline event, plus the two
 * child-writer branches that decide whether an event is emitted at all.
 */
class TestLocalPRService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final LocalPRStore store = mock(LocalPRStore.class);
    private final DevReportService devReports = mock(DevReportService.class);
    private final LocalPRService service =
            new LocalPRServiceImpl(store, devReports, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    private LocalPR pr(String status)
    {
        LocalPR base = LocalPR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        LocalPR withStatus = base.withStatus(status, NOW);
        when(store.findById("pr1")).thenReturn(Optional.of(withStatus));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return withStatus;
    }

    @Test
    void legalTransitionSavesAndWritesStatusEvent()
    {
        pr(LocalPR.STATUS_LOCAL_DRAFTED);

        LocalPR flipped = service.transition("pr1", LocalPR.STATUS_LOCAL_OPEN, "you");

        assertThat(flipped.status()).isEqualTo(LocalPR.STATUS_LOCAL_OPEN);
        ArgumentCaptor<LocalPRTimelineEvent> event = ArgumentCaptor.forClass(LocalPRTimelineEvent.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(LocalPRTimelineEvent.TYPE_STATUS);
        assertThat(event.getValue().actor()).isEqualTo("you");
    }

    @Test
    void illegalTransitionThrowsAndWritesNothing()
    {
        pr(LocalPR.STATUS_LOCAL_DRAFTED);

        assertThatThrownBy(() -> service.transition("pr1", LocalPR.STATUS_MERGED, "you"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal local-PR transition");
        verify(store, never()).save(any());
        verify(store, never()).addEvent(any());
    }

    @Test
    void terminalStatusIsTerminalHasNoOutgoingEdge()
    {
        pr(LocalPR.STATUS_MERGED);

        assertThatThrownBy(() -> service.transition("pr1", LocalPR.STATUS_CLOSED, "you"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finishedCheckWritesCiEventRunningDoesNot()
    {
        pr(LocalPR.STATUS_LOCAL_DRAFTED);
        when(store.addCheck(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordCheck("pr1", LocalPRCheck.KIND_LOCAL, "mvn verify", LocalPRCheck.STATUS_RUNNING, 10L);
        verify(store, never()).addEvent(any());

        service.recordCheck("pr1", LocalPRCheck.KIND_LOCAL, "mvn verify", LocalPRCheck.STATUS_PASSED, 20L);
        ArgumentCaptor<LocalPRTimelineEvent> event = ArgumentCaptor.forClass(LocalPRTimelineEvent.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(LocalPRTimelineEvent.TYPE_CI);
        assertThat(event.getValue().localOnly()).isTrue();  // local kind never migrates
    }

    @Test
    void fileLineCommentRequiresLocation()
    {
        pr(LocalPR.STATUS_LOCAL_DRAFTED);

        assertThatThrownBy(() -> service.addComment(
                "pr1", LocalPRComment.ORIGIN_LOCAL, LocalPRComment.SCOPE_FILE_LINE,
                /* filePath */ null, /* lineNumber */ null, "you", "body", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filePath");
    }

    @Test
    void resolveCommentStampsResolvedAt()
    {
        LocalPRComment comment = new LocalPRComment(
                "cm1", "pr1", LocalPRComment.ORIGIN_LOCAL, LocalPRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, null, null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(comment));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalPRComment resolved = service.resolveComment("cm1");

        assertThat(resolved.resolvedAt()).isEqualTo(NOW);
        assertThat(resolved.dismissedAt()).isNull();
    }

    @Test
    void dismissCommentStampsDismissedAt()
    {
        LocalPRComment comment = new LocalPRComment(
                "cm1", "pr1", LocalPRComment.ORIGIN_LOCAL, LocalPRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, null, null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(comment));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalPRComment dismissed = service.dismissComment("cm1");

        assertThat(dismissed.dismissedAt()).isEqualTo(NOW);
        assertThat(dismissed.resolvedAt()).isNull();
    }

    @Test
    void resolveCommentUnknownIdThrows()
    {
        when(store.findCommentById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveComment("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordPushStripsLocalsRecordsRemoteAndFlipsStatus()
    {
        pr(LocalPR.STATUS_LOCAL_OPEN);
        LocalPRTimelineEvent localEvent = new LocalPRTimelineEvent(
                "ev1", "pr1", LocalPRTimelineEvent.TYPE_COMMIT, "claude-code",
                /* localOnly */ true, /* strippedOnPushAt */ null, NOW, null);
        LocalPRComment localComment = new LocalPRComment(
                "cm1", "pr1", LocalPRComment.ORIGIN_LOCAL, LocalPRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, /* dismissedAt */ null, /* strippedOnPushAt */ null, null);
        when(store.unstrippedLocalOnlyEvents("pr1")).thenReturn(List.of(localEvent));
        when(store.unstrippedLocalComments("pr1")).thenReturn(List.of(localComment));

        LocalPR pushed = service.recordPush("pr1", 145, "https://github.com/o/r/pull/145");

        // The local event is stamped stripped (never migrates to GitHub).
        ArgumentCaptor<LocalPRTimelineEvent> events = ArgumentCaptor.forClass(LocalPRTimelineEvent.class);
        verify(store, times(2)).addEvent(events.capture()); // stripped event + status event
        assertThat(events.getAllValues().get(0).id()).isEqualTo("ev1");
        assertThat(events.getAllValues().get(0).strippedOnPushAt()).isEqualTo(NOW);
        // The local comment is stamped stripped too.
        ArgumentCaptor<LocalPRComment> comments = ArgumentCaptor.forClass(LocalPRComment.class);
        verify(store).saveComment(comments.capture());
        assertThat(comments.getValue().strippedOnPushAt()).isEqualTo(NOW);
        // The final status flip lands at remote-drafted, and a status event was written.
        assertThat(pushed.status()).isEqualTo(LocalPR.STATUS_REMOTE_DRAFTED);
        assertThat(events.getAllValues().get(1).eventType()).isEqualTo(LocalPRTimelineEvent.TYPE_STATUS);
    }

    @Test
    void recordMergedFlipsToMergedFromRemoteOpen()
    {
        pr(LocalPR.STATUS_REMOTE_OPEN);

        LocalPR merged = service.recordMerged("pr1");

        assertThat(merged.status()).isEqualTo(LocalPR.STATUS_MERGED);
    }

    @Test
    void recordMergedRejectsANonRemoteOpenPr()
    {
        pr(LocalPR.STATUS_LOCAL_OPEN);

        assertThatThrownBy(() -> service.recordMerged("pr1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingStripCountSumsLocalEventsAndComments()
    {
        LocalPRTimelineEvent e = new LocalPRTimelineEvent(
                "ev1", "pr1", LocalPRTimelineEvent.TYPE_COMMIT, "claude-code", true, null, NOW, null);
        LocalPRComment c = new LocalPRComment(
                "cm1", "pr1", LocalPRComment.ORIGIN_LOCAL, LocalPRComment.SCOPE_PR, null, null,
                "you", "n", NOW, null, null, null, null);
        when(store.unstrippedLocalOnlyEvents("pr1")).thenReturn(List.of(e, e));
        when(store.unstrippedLocalComments("pr1")).thenReturn(List.of(c));

        assertThat(service.pendingStripCount("pr1")).isEqualTo(3);
    }
}
