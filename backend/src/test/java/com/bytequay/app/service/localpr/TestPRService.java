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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.DevReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * State-machine coverage for {@link PRService}: {@link PRService#transition}
 * validation against {@link PR#ALLOWED_TRANSITIONS} and the invariant that
 * every accepted flip writes a {@code status} timeline event, plus the two
 * child-writer branches that decide whether an event is emitted at all.
 */
class TestPRService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final PRStore store = mock(PRStore.class);
    private final DevReportService devReports = mock(DevReportService.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PRService service = new PRServiceImpl(
            store, devReports, new ObjectMapper(), stageStore, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private PR pr(String status)
    {
        PR base = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        PR withStatus = base.withStatus(status, NOW);
        when(store.findById("pr1")).thenReturn(Optional.of(withStatus));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return withStatus;
    }

    @Test
    void legalTransitionSavesAndWritesStatusEvent()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);

        PR flipped = service.transition("pr1", PR.STATUS_LOCAL_OPEN, "you");

        assertThat(flipped.status()).isEqualTo(PR.STATUS_LOCAL_OPEN);
        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_STATUS);
        assertThat(event.getValue().actor()).isEqualTo("you");
    }

    @Test
    void legalTransitionPublishesPrUpdated()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);

        service.transition("pr1", PR.STATUS_LOCAL_OPEN, "you");

        verify(events).publishEvent(new PrUpdatedEvent("pr1"));
    }

    @Test
    void illegalTransitionThrowsAndWritesNothing()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);

        assertThatThrownBy(() -> service.transition("pr1", PR.STATUS_MERGED, "you"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal local-PR transition");
        verify(store, never()).save(any());
        verify(store, never()).addEvent(any());
    }

    @Test
    void terminalStatusIsTerminalHasNoOutgoingEdge()
    {
        pr(PR.STATUS_MERGED);

        assertThatThrownBy(() -> service.transition("pr1", PR.STATUS_CLOSED, "you"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finishedCheckWritesCiEventRunningDoesNot()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        when(store.addCheck(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordCheck("pr1", PRCheck.KIND_LOCAL, "mvn verify", PRCheck.STATUS_RUNNING, 10L);
        verify(store, never()).addEvent(any());

        service.recordCheck("pr1", PRCheck.KIND_LOCAL, "mvn verify", PRCheck.STATUS_PASSED, 20L);
        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_CI);
        assertThat(event.getValue().localOnly()).isTrue();  // local kind never migrates
    }

    @Test
    void syncedCheckNeverWritesATimelineEventEvenWhenTerminal()
    {
        pr(PR.STATUS_REMOTE_OPEN);
        when(store.checksFor("pr1")).thenReturn(List.of());
        when(store.addCheck(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSyncedCheck(
                "pr1", "555", "build-success", PRCheck.STATUS_PASSED, NOW, NOW);

        verify(store, never()).addEvent(any());
    }

    @Test
    void retainSyncedChecksPrunesOnlyMissingRemoteRuns()
    {
        pr(PR.STATUS_REMOTE_OPEN);

        service.retainSyncedChecks("pr1", Set.of("current"));

        verify(store).retainChecks("pr1", PRCheck.KIND_REMOTE, Set.of("current"));
    }

    @Test
    void fileLineCommentRequiresLocation()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);

        assertThatThrownBy(() -> service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                /* filePath */ null, /* lineNumber */ null, null, null, null, "you", "body", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filePath");
    }

    @Test
    void addCommentAnchorsToTheRemovedSideAndDefaultsBlankSideToRight()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        PRComment onLeft = service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 12, "left", null, null, "you", "this line was removed", null);
        assertThat(onLeft.side()).isEqualTo("LEFT");

        PRComment blank = service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 12, null, null, null, "you", "no side given", null);
        assertThat(blank.side()).isEqualTo("RIGHT");
    }

    @Test
    void addCommentBuildsAMultiLineRangeWhenStartLineDiffers()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        PRComment range = service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 15, "RIGHT", 12, null, "you", "spans a few lines", null);

        assertThat(range.startLine()).isEqualTo(12);
        // startSide defaults to the end side when omitted.
        assertThat(range.startSide()).isEqualTo("RIGHT");
    }

    @Test
    void addCommentKeepsAValidParentCommentId()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        PRComment parent = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "brain", "question", NOW, null, null, null, null, null, "RIGHT", null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(parent));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        PRComment reply = service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "claude-code", "answer", " cm1 ");

        assertThat(reply.parentCommentId()).isEqualTo("cm1");
    }

    @Test
    void replyInheritsItsParentsFileAnchor()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        PRComment parent = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Parent.java", 15, "brain", "question", NOW,
                null, null, null, null, null, "LEFT", 12, "LEFT");
        when(store.findCommentById("cm1")).thenReturn(Optional.of(parent));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        PRComment reply = service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, "RIGHT", null, null, "you", "answer", "cm1");

        assertThat(reply)
                .extracting(
                        PRComment::scope,
                        PRComment::filePath,
                        PRComment::lineNumber,
                        PRComment::side,
                        PRComment::startLine,
                        PRComment::startSide,
                        PRComment::parentCommentId)
                .containsExactly(
                        PRComment.SCOPE_FILE_LINE,
                        "src/Parent.java",
                        15,
                        "LEFT",
                        12,
                        "LEFT",
                        "cm1");
        verify(store, never()).addEvent(any());
    }

    @Test
    void addCommentRejectsAParentFromAnotherPr()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        PRComment parent = new PRComment(
                "cm-other", "pr2", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "brain", "question", NOW, null, null, null, null, null, "RIGHT", null, null);
        when(store.findCommentById("cm-other")).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "claude-code", "answer", "cm-other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another PR");
    }

    @Test
    void addRemoteCommentSavesAnOriginRemoteCommentAndATaggedTimelineEvent()
    {
        pr(PR.STATUS_REMOTE_DRAFTED);
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));
        Instant createdAt = Instant.parse("2026-06-20T10:00:00Z");

        PRComment comment = service.addRemoteComment("pr1", "@octocat", "Can you handle nulls?", createdAt, 5001L);

        assertThat(comment.origin()).isEqualTo(PRComment.ORIGIN_REMOTE);
        assertThat(comment.scope()).isEqualTo(PRComment.SCOPE_PR);
        assertThat(comment.filePath()).isNull();
        assertThat(comment.author()).isEqualTo("@octocat");
        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_COMMENT);
        assertThat(event.getValue().localOnly()).isFalse();
        assertThat(event.getValue().remoteEventId()).isEqualTo(5001L);
    }

    @Test
    void recordRemoteReviewWritesAReviewEventWithVerdictAndBody()
    {
        pr(PR.STATUS_REMOTE_DRAFTED);
        Instant when = Instant.parse("2026-06-20T10:00:00Z");

        service.recordRemoteReview("pr1", "@reviewer1", "APPROVED", "Nice cleanup, LGTM.", when, 9001L);

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        PRTimelineEntry saved = event.getValue();
        assertThat(saved.eventType()).isEqualTo(PRTimelineEntry.TYPE_REVIEW);
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
        PRComment comment = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, null, null, null, null, "RIGHT", null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(comment));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        PRComment resolved = service.resolveComment("cm1");

        assertThat(resolved.resolvedAt()).isEqualTo(NOW);
        assertThat(resolved.dismissedAt()).isNull();
    }

    @Test
    void dismissCommentStampsDismissedAt()
    {
        PRComment comment = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, null, null, null, null, "RIGHT", null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(comment));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        PRComment dismissed = service.dismissComment("cm1");

        assertThat(dismissed.dismissedAt()).isEqualTo(NOW);
        assertThat(dismissed.resolvedAt()).isNull();
    }

    @Test
    void deleteDraftCommentDeletesOpenLocalDraft()
    {
        PRComment comment = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, null, null, null, null, "RIGHT", null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(comment));

        service.deleteDraftComment("cm1");

        verify(store).deleteComment("cm1");
        verify(events).publishEvent(new PrUpdatedEvent("pr1"));
    }

    @Test
    void reopenCommentClearsClosedState()
    {
        PRComment comment = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "you", "note", NOW, NOW, NOW, null, null, null, "RIGHT", null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(comment));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        PRComment reopened = service.reopenComment("cm1");

        assertThat(reopened.resolvedAt()).isNull();
        assertThat(reopened.dismissedAt()).isNull();
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
        pr(PR.STATUS_LOCAL_OPEN);
        PRTimelineEntry localEvent = new PRTimelineEntry(
                "ev1", "pr1", PRTimelineEntry.TYPE_COMMIT, "claude-code",
                /* localOnly */ true, /* strippedOnPushAt */ null, NOW, null, /* remoteEventId */ null);
        PRComment localComment = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, /* dismissedAt */ null, /* strippedOnPushAt */ null, null, null,
                "RIGHT", null, null);
        when(store.unstrippedLocalOnlyEvents("pr1")).thenReturn(List.of(localEvent));
        when(store.unstrippedLocalComments("pr1")).thenReturn(List.of(localComment));

        PR pushed = service.recordPush("pr1", "o/r", 145, "https://github.com/o/r/pull/145");

        // No external twin existed, so nothing is folded away.
        verify(store, never()).reparentChildren(any(), any());
        verify(store, never()).deletePr(any());

        // The local event is stamped stripped (never migrates to GitHub).
        ArgumentCaptor<PRTimelineEntry> events = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store, times(2)).addEvent(events.capture()); // stripped event + status event
        assertThat(events.getAllValues().get(0).id()).isEqualTo("ev1");
        assertThat(events.getAllValues().get(0).strippedOnPushAt()).isEqualTo(NOW);
        // The local comment is stamped stripped too.
        ArgumentCaptor<PRComment> comments = ArgumentCaptor.forClass(PRComment.class);
        verify(store).saveComment(comments.capture());
        assertThat(comments.getValue().strippedOnPushAt()).isEqualTo(NOW);
        // The final status flip lands at remote-drafted, and a status event was written.
        assertThat(pushed.status()).isEqualTo(PR.STATUS_REMOTE_DRAFTED);
        assertThat(events.getAllValues().get(1).eventType()).isEqualTo(PRTimelineEntry.TYPE_STATUS);
    }

    @Test
    void foldExternalTwinReparentsTheTwinsChildrenAndDeletesIt()
    {
        // A pushed task PR whose (repo, number) also has a dashboard twin.
        PR task = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW)
                .withRemote("o/r", 145, "https://github.com/o/r/pull/145", NOW);
        when(store.findById("pr1")).thenReturn(Optional.of(task));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PR external = PR.createExternal(
                "ext1", "o/r", 145, "https://github.com/o/r/pull/145", "@octocat",
                "head", "main", "T", "", PR.STATUS_REMOTE_OPEN, NOW, null, null);
        when(store.findByRepoAndRemotePrNumber("o/r", 145)).thenReturn(Optional.of(external));

        service.foldExternalTwinIntoTask("pr1");

        verify(store).reparentChildren("ext1", "pr1");
        verify(store).deletePr("ext1");
    }

    @Test
    void foldExternalTwinWaitsForItsRunningAgentReview()
    {
        PR task = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW)
                .withRemote("o/r", 145, "https://github.com/o/r/pull/145", NOW);
        when(store.findById("pr1")).thenReturn(Optional.of(task));
        PR external = PR.createExternal(
                "ext1", "o/r", 145, "https://github.com/o/r/pull/145", "@octocat",
                "head", "main", "T", "", PR.STATUS_REMOTE_OPEN, NOW, null, null);
        when(store.findByRepoAndRemotePrNumber("o/r", 145)).thenReturn(Optional.of(external));
        when(store.hasRunningAgentReview("ext1")).thenReturn(true);

        service.foldExternalTwinIntoTask("pr1");

        verify(store, never()).reparentChildren(any(), any());
        verify(store, never()).deletePr(any());
    }

    @Test
    void foldExternalTwinIsANoOpWhenTheTaskPrHasNoTwin()
    {
        PR task = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW)
                .withRemote("o/r", 145, "https://github.com/o/r/pull/145", NOW);
        when(store.findById("pr1")).thenReturn(Optional.of(task));
        when(store.findByRepoAndRemotePrNumber("o/r", 145)).thenReturn(Optional.empty());

        service.foldExternalTwinIntoTask("pr1");

        verify(store, never()).reparentChildren(any(), any());
        verify(store, never()).deletePr(any());
    }

    @Test
    void recordMergedFlipsToMergedFromRemoteOpen()
    {
        pr(PR.STATUS_REMOTE_OPEN);

        PR merged = service.recordMerged("pr1");

        assertThat(merged.status()).isEqualTo(PR.STATUS_MERGED);
    }

    @Test
    void recordMergedRejectsANonRemoteOpenPr()
    {
        pr(PR.STATUS_LOCAL_OPEN);

        assertThatThrownBy(() -> service.recordMerged("pr1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingStripCountSumsLocalEventsAndComments()
    {
        PRTimelineEntry e = new PRTimelineEntry(
                "ev1", "pr1", PRTimelineEntry.TYPE_COMMIT, "claude-code", true, null, NOW, null, null);
        PRComment c = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "you", "n", NOW, null, null, null, null, null, "RIGHT", null, null);
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

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_REVIEW);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_BRAIN);
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
    void recordBrainReviewWritesATimelineEventWhenThePrExists()
    {
        PR existing = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        when(store.findByTaskId("task1")).thenReturn(Optional.of(existing));

        service.recordBrainReview("task1", "dev", "changes_requested", 2, "round-1", "Review summary");

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_REVIEW);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_BRAIN);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().payloadJson())
                .contains("\"reviewEvent\":\"finished\"")
                .contains("\"scope\":\"dev\"").contains("\"verdict\":\"changes_requested\"")
                .contains("\"iteration\":2").contains("\"roundId\":\"round-1\"")
                .contains("\"body\":\"Review summary\"");
    }

    @Test
    void recordBrainReviewNoOpsWhenTheTaskHasNoPrYet()
    {
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());

        service.recordBrainReview("task1", "plan", "approved", 1, null, null);

        verify(store, never()).addEvent(any());
    }

    @Test
    void createForTaskBackfillsThePlanApprovalOntoTheNewRowsTimeline()
    {
        UUID planStageId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        Instant approvedAt = NOW.minusSeconds(500);
        StageInstance plan = new StageInstance(
                planStageId, "task1", StageType.PLAN_STAGE, StageState.CLOSED, NOW.minusSeconds(700), NOW, null);
        StageEvent approved = new StageEvent(
                UUID.randomUUID(), planStageId, "task1", StageEventType.PLAN_APPROVED, approvedAt,
                "{\"approvedAt\":\"" + approvedAt + "\"}");
        when(stageStore.findStagesByTask("task1")).thenReturn(List.of(plan));
        when(stageStore.findEventsByStage(planStageId)).thenReturn(List.of(approved));
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createForTask("task1", "dev/x", "main", "T", "");

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_PLAN_FINALIZED);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_USER);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().createdAt()).isEqualTo(approvedAt);
        assertThat(event.getValue().payloadJson()).contains("\"planStageId\":\"" + planStageId + "\"");
    }

    @Test
    void recordPlanApprovedWritesATimelineEventWhenThePrExists()
    {
        PR existing = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        when(store.findByTaskId("task1")).thenReturn(Optional.of(existing));

        service.recordPlanApproved("task1", "plan-stage-1");

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_PLAN_FINALIZED);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_USER);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().payloadJson()).contains("\"planStageId\":\"plan-stage-1\"");
    }

    @Test
    void recordPlanApprovedNoOpsWhenTheTaskHasNoPrYet()
    {
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());

        service.recordPlanApproved("task1", "plan-stage-1");

        verify(store, never()).addEvent(any());
    }
}
