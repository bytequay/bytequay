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
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.LocalPRStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.DevReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private final StageStore stageStore = mock(StageStore.class);
    private final LocalPRService service = new LocalPRServiceImpl(
            store, devReports, new ObjectMapper(), stageStore, Clock.fixed(NOW, ZoneOffset.UTC));

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
    void addRemoteCommentSavesAnOriginRemoteCommentAndATaggedTimelineEvent()
    {
        pr(LocalPR.STATUS_REMOTE_DRAFTED);
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));
        Instant createdAt = Instant.parse("2026-06-20T10:00:00Z");

        LocalPRComment comment = service.addRemoteComment("pr1", "@octocat", "Can you handle nulls?", createdAt, 5001L);

        assertThat(comment.origin()).isEqualTo(LocalPRComment.ORIGIN_REMOTE);
        assertThat(comment.scope()).isEqualTo(LocalPRComment.SCOPE_PR);
        assertThat(comment.filePath()).isNull();
        assertThat(comment.author()).isEqualTo("@octocat");
        ArgumentCaptor<LocalPRTimelineEvent> event = ArgumentCaptor.forClass(LocalPRTimelineEvent.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(LocalPRTimelineEvent.TYPE_COMMENT);
        assertThat(event.getValue().localOnly()).isFalse();
        assertThat(event.getValue().remoteEventId()).isEqualTo(5001L);
    }

    @Test
    void recordRemoteReviewWritesAReviewEventWithVerdictAndBody()
    {
        pr(LocalPR.STATUS_REMOTE_DRAFTED);
        Instant when = Instant.parse("2026-06-20T10:00:00Z");

        service.recordRemoteReview("pr1", "@reviewer1", "APPROVED", "Nice cleanup, LGTM.", when, 9001L);

        ArgumentCaptor<LocalPRTimelineEvent> event = ArgumentCaptor.forClass(LocalPRTimelineEvent.class);
        verify(store).addEvent(event.capture());
        LocalPRTimelineEvent saved = event.getValue();
        assertThat(saved.eventType()).isEqualTo(LocalPRTimelineEvent.TYPE_REVIEW);
        assertThat(saved.actor()).isEqualTo("@reviewer1");
        assertThat(saved.localOnly()).isFalse();
        assertThat(saved.remoteEventId()).isEqualTo(9001L);
        assertThat(saved.payloadJson()).contains("APPROVED").contains("Nice cleanup, LGTM.");
    }

    @Test
    void hasRemoteEventDelegatesToTheStore()
    {
        when(store.timelineEventExistsByRemoteId("pr1", 5001L)).thenReturn(true);

        assertThat(service.hasRemoteEvent("pr1", 5001L)).isTrue();
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
                /* localOnly */ true, /* strippedOnPushAt */ null, NOW, null, /* remoteEventId */ null);
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
                "ev1", "pr1", LocalPRTimelineEvent.TYPE_COMMIT, "claude-code", true, null, NOW, null, null);
        LocalPRComment c = new LocalPRComment(
                "cm1", "pr1", LocalPRComment.ORIGIN_LOCAL, LocalPRComment.SCOPE_PR, null, null,
                "you", "n", NOW, null, null, null, null);
        when(store.unstrippedLocalOnlyEvents("pr1")).thenReturn(List.of(e, e));
        when(store.unstrippedLocalComments("pr1")).thenReturn(List.of(c));

        assertThat(service.pendingStripCount("pr1")).isEqualTo(3);
    }

    // ── brain adversarial review (plan-rail-runs.md R24) ─────────────────

    @Test
    void createForTaskBackfillsThePlanSelfReviewOntoTheNewRowsTimeline()
    {
        UUID planStageId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        Instant reviewedAt = NOW.minusSeconds(600);
        StageInstance plan = new StageInstance(
                planStageId, "task1", StageType.PLAN_STAGE, StageState.CLOSED, NOW.minusSeconds(700), NOW, null);
        StageEvent reviewed = new StageEvent(
                UUID.randomUUID(), planStageId, "task1", StageEventType.PLAN_SELF_REVIEWED, reviewedAt,
                "{\"verdict\":\"approved\"}");
        when(stageStore.findStagesByTask("task1")).thenReturn(List.of(plan));
        when(stageStore.findEventsByStage(planStageId)).thenReturn(List.of(reviewed));
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createForTask("task1", "dev/x", "main", "T", "");

        ArgumentCaptor<LocalPRTimelineEvent> event = ArgumentCaptor.forClass(LocalPRTimelineEvent.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(LocalPRTimelineEvent.TYPE_REVIEW);
        assertThat(event.getValue().actor()).isEqualTo(LocalPRTimelineEvent.ACTOR_BRAIN);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().createdAt()).isEqualTo(reviewedAt);
        assertThat(event.getValue().payloadJson()).contains("\"verdict\":\"approved\"");
    }

    @Test
    void createForTaskWritesNoBackfillEventWhenThereWasNoSelfReview()
    {
        when(stageStore.findStagesByTask("task1")).thenReturn(List.of());
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createForTask("task1", "dev/x", "main", "T", "");

        verify(store, never()).addEvent(any());
    }

    @Test
    void recordBrainReviewWritesATimelineEventWhenTheLocalPrExists()
    {
        LocalPR existing = LocalPR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        when(store.findByTaskId("task1")).thenReturn(Optional.of(existing));

        service.recordBrainReview("task1", "dev", "changes_requested", 2);

        ArgumentCaptor<LocalPRTimelineEvent> event = ArgumentCaptor.forClass(LocalPRTimelineEvent.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(LocalPRTimelineEvent.TYPE_REVIEW);
        assertThat(event.getValue().actor()).isEqualTo(LocalPRTimelineEvent.ACTOR_BRAIN);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().payloadJson())
                .contains("\"scope\":\"dev\"").contains("\"verdict\":\"changes_requested\"").contains("\"iteration\":2");
    }

    @Test
    void recordBrainReviewNoOpsWhenTheTaskHasNoLocalPrYet()
    {
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());

        service.recordBrainReview("task1", "plan", "approved", 1);

        verify(store, never()).addEvent(any());
    }
}
