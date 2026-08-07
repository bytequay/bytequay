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

import com.bytequay.app.developmentflow.stage.V2LocalReviewControl;
import com.bytequay.app.domain.LocalReviewSubmission;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.sqlite.TaskPushStore;
import com.bytequay.app.service.review.DevReportService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final LocalReviewSubmissionStore submissions = mock(LocalReviewSubmissionStore.class);
    private final TaskPushStore pushes = mock(TaskPushStore.class);
    private final PlatformTransactionManager transactions = new TestTransactionManager();
    private final TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
    private final PRService service = new PRServiceImpl(
            store, devReports, new ObjectMapper(), stageStore, taskStore, turnStore,
            submissions, pushes, commands, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private PR pr(String status)
    {
        PR base = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        PR withStatus = base.withStatus(status, NOW);
        when(store.findById("pr1")).thenReturn(Optional.of(withStatus));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        taskAt(TaskPhase.AWAITING_PUSH);
        return withStatus;
    }

    private PR externalPr()
    {
        PR external = PR.createExternal(
                "pr1", "acme/widget", 42, "https://example.test/pr/42",
                "@octocat", "feature", "main", "T", "",
                PR.STATUS_REMOTE_OPEN, NOW, null, null);
        when(store.findById("pr1")).thenReturn(Optional.of(external));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return external;
    }

    private void taskAt(TaskPhase phase)
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task1");
        when(task.phase()).thenReturn(phase);
        when(task.status()).thenReturn(TaskStatus.IDLE);
        when(task.threadId()).thenReturn("thread1");
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task));
    }

    @Test
    void directTaskPrCreationIsRetiredBeforeStoreMutation()
    {
        assertThatThrownBy(() -> service.createForTask(
                "task1", "dev/x", "main", "T", ""))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exact V2 Local Development owner");

        verify(store, never()).save(any());
    }

    @Test
    void exactV2TaskCommandCreatesTheStablePrIdempotently()
    {
        when(taskStore.findWorkflowVersion("task1"))
                .thenReturn(Optional.of("V2"));
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PR created = commands.execute("task1", () ->
                service.createForTaskInCommand(
                        "task1", "dev/x", "main", "T", ""));

        assertThat(created.taskId()).isEqualTo("task1");
        assertThat(created.branchName()).isEqualTo("dev/x");
        verify(store).save(any(PR.class));
    }

    @Test
    void exactV2BrainAcceptanceOpensTheStablePrForLocalReview()
    {
        PR local = pr(PR.STATUS_LOCAL_DRAFTED);
        when(taskStore.findWorkflowVersion("task1"))
                .thenReturn(Optional.of("V2"));
        when(store.findByTaskId("task1")).thenReturn(Optional.of(local));

        PR opened = commands.execute("task1", () ->
                service.requestUserReviewInCommand(
                        "task1", "v2-local-runtime"));

        assertThat(opened.status()).isEqualTo(PR.STATUS_LOCAL_OPEN);
        verifyNoInteractions(devReports);
        ArgumentCaptor<PRTimelineEntry> event =
                ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType())
                .isEqualTo(PRTimelineEntry.TYPE_STATUS);
        assertThat(event.getValue().actor()).isEqualTo("v2-local-runtime");
    }

    @Test
    void repeatedV2BrainAcceptanceKeepsAnOpenLocalReviewStable()
    {
        PR local = pr(PR.STATUS_LOCAL_OPEN);
        when(taskStore.findWorkflowVersion("task1"))
                .thenReturn(Optional.of("V2"));
        when(store.findByTaskId("task1")).thenReturn(Optional.of(local));

        PR reopened = commands.execute("task1", () ->
                service.requestUserReviewInCommand(
                        "task1", "v2-local-runtime"));

        assertThat(reopened).isSameAs(local);
        verify(store, never()).save(any());
        verify(store, never()).addEvent(any());
    }

    @Test
    void v2LocalReviewOwnsTaskPrTitleAndBodyEdits()
    {
        PR local = pr(PR.STATUS_LOCAL_OPEN);
        V2LocalReviewControl typed = mock(V2LocalReviewControl.class);
        when(typed.handles(local)).thenReturn(true);
        ((PRServiceImpl) service).setV2LocalReview(typed);

        service.updateDetails("pr1", "New title", "New body");

        verify(typed).updateDetails(local, "New title", "New body");
        verify(store, never()).save(any());
    }

    @Test
    void legacyTaskPrTitleEditFailsBeforeStoreMutation()
    {
        pr(PR.STATUS_LOCAL_OPEN);
        when(taskStore.findWorkflowVersion("task1"))
                .thenReturn(Optional.of("LEGACY"));

        assertThatThrownBy(() -> service.updateDetails(
                "pr1", "New title", "New body"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        verify(store, never()).save(any());
    }

    @Test
    void legacyTaskPrCommentMutationFailsBeforeStoreMutation()
    {
        pr(PR.STATUS_LOCAL_OPEN);
        when(taskStore.findWorkflowVersion("task1"))
                .thenReturn(Optional.of("LEGACY"));

        assertThatThrownBy(() -> service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null,
                PRTimelineEntry.ACTOR_USER, "retired", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        verify(store, never()).saveComment(any());
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
    void aggregateRemoteCiTransitionWritesOneTimelineEvent()
    {
        pr(PR.STATUS_REMOTE_OPEN);

        service.recordRemoteCiState("pr1", PRCheck.STATUS_PASSED,
                PRCheck.STATUS_FAILED, "deadbeef", 12);

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_CI);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().payloadJson())
                .contains("\"status\":\"passed\"")
                .contains("\"previousStatus\":\"failed\"")
                .contains("\"headSha\":\"deadbeef\"")
                .contains("\"checkCount\":12");
    }

    @Test
    void aggregateRemoteCiTransitionIsIdempotentButKeepsRealStateChanges()
    {
        pr(PR.STATUS_REMOTE_OPEN);
        List<PRTimelineEntry> persisted = new ArrayList<>();
        when(store.timelineFor("pr1")).thenAnswer(ignored -> List.copyOf(persisted));
        when(store.addEvent(any())).thenAnswer(invocation -> {
            PRTimelineEntry event = invocation.getArgument(0);
            persisted.add(event);
            return event;
        });

        service.recordRemoteCiState("pr1", PRCheck.STATUS_PASSED, null, "deadbeef", 12);
        service.recordRemoteCiState("pr1", PRCheck.STATUS_PASSED, null, "deadbeef", 12);
        service.recordRemoteCiState("pr1", PRCheck.STATUS_FAILED, PRCheck.STATUS_PASSED, "deadbeef", 12);
        service.recordRemoteCiState("pr1", PRCheck.STATUS_PASSED, PRCheck.STATUS_FAILED, "deadbeef", 12);

        assertThat(persisted).hasSize(3);
        assertThat(persisted).extracting(PRTimelineEntry::payloadJson)
                .anySatisfy(payload -> assertThat(payload).contains("\"status\":\"failed\""));
    }

    @Test
    void remoteCiRerunRecordsItsTriggerAndHead()
    {
        pr(PR.STATUS_REMOTE_OPEN);

        service.recordRemoteCiRerun("pr1", "user", "deadbeef", 1);

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_CI);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_USER);
        assertThat(event.getValue().payloadJson())
                .contains("\"status\":\"rerun_requested\"")
                .contains("\"trigger\":\"user\"")
                .contains("\"headSha\":\"deadbeef\"");
    }

    @Test
    void commitRecordingTreatsShortAndFullShaAsTheSameCommit()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        PRCommit existing = new PRCommit(
                "commit-1", "pr1", "abcdef0", "first", 1, 0, NOW, null);
        when(store.commitsFor("pr1")).thenReturn(List.of(existing));

        PRCommit result = service.recordSyncedCommit(
                "pr1", "abcdef0123456789", "first", NOW, "@octocat");

        assertThat(result).isSameAs(existing);
        verify(store, never()).addCommit(any());
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
        externalPr();

        assertThatThrownBy(() -> service.addComment(
                "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                /* filePath */ null, /* lineNumber */ null, null, null, null, "you", "body", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filePath");
    }

    @Test
    void addCommentAnchorsToTheRemovedSideAndDefaultsBlankSideToRight()
    {
        externalPr();
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
        externalPr();
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
        externalPr();
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
        externalPr();
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
        externalPr();
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
        externalPr();
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
        externalPr();
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
        externalPr();
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
        externalPr();
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
    void reopeningAnAlreadyOpenRootDoesNotWriteAFalseReopenedEvent()
    {
        externalPr();
        PRComment comment = new PRComment(
                "cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR, null, null,
                "you", "note", NOW, null, null, null, null, null, "RIGHT", null, null);
        when(store.findCommentById("cm1")).thenReturn(Optional.of(comment));
        when(store.saveComment(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reopenComment("cm1");

        verify(store, never()).addEvent(any());
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
        UUID developmentStageId = UUID.randomUUID();
        StageInstance development = new StageInstance(
                developmentStageId, "task1", StageType.DEVELOPMENT_STAGE,
                StageState.CLOSED, NOW, NOW, null);
        when(stageStore.findStageByType("task1", StageType.DEVELOPMENT_STAGE))
                .thenReturn(Optional.of(development));
        when(store.commitsFor("pr1")).thenReturn(List.of(
                new PRCommit("c1", "pr1", "abc", "first", 7, 2, NOW, null)));
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
        verify(store, times(3)).addEvent(events.capture()); // stripped event + remote PR + status event
        assertThat(events.getAllValues().get(0).id()).isEqualTo("ev1");
        assertThat(events.getAllValues().get(0).strippedOnPushAt()).isEqualTo(NOW);
        // The local comment is stamped stripped too.
        ArgumentCaptor<PRComment> comments = ArgumentCaptor.forClass(PRComment.class);
        verify(store).saveComment(comments.capture());
        assertThat(comments.getValue().strippedOnPushAt()).isEqualTo(NOW);
        // The final status flip lands at remote-drafted, and a status event was written.
        assertThat(pushed.status()).isEqualTo(PR.STATUS_REMOTE_DRAFTED);
        PRTimelineEntry created = events.getAllValues().get(1);
        assertThat(created.eventType()).isEqualTo(PRTimelineEntry.TYPE_PULL_REQUEST_CREATED);
        assertThat(created.localOnly()).isFalse();
        assertThat(created.payloadJson()).contains(
                "\"phase\":\"created\"",
                "\"branch\":\"dev/x\"", "\"baseBranch\":\"main\"", "\"number\":145",
                "\"additions\":7", "\"deletions\":2");
        assertThat(events.getAllValues().get(2).eventType()).isEqualTo(PRTimelineEntry.TYPE_STATUS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> stagePayload = ArgumentCaptor.forClass(Map.class);
        verify(stageStore).recordEvent(
                eq(developmentStageId), eq("task1"), eq(StageEventType.PULL_REQUEST_CREATED), stagePayload.capture());
        assertThat(stagePayload.getValue()).containsEntry("branch", "dev/x")
                .containsEntry("phase", "created")
                .containsEntry("baseBranch", "main")
                .containsEntry("number", 145)
                .containsEntry("additions", 7)
                .containsEntry("deletions", 2);
    }

    @Test
    void durablePublishProjectsThePrWithoutWritingTheLegacyStage()
    {
        pr(PR.STATUS_LOCAL_OPEN);
        when(store.commitsFor("pr1")).thenReturn(List.of());

        PR published = commands.execute("task1", () ->
                service.recordPublishedInCommand(
                        "pr1", "o/r", 145,
                        "https://github.com/o/r/pull/145"));

        assertThat(published.status()).isEqualTo(PR.STATUS_REMOTE_DRAFTED);
        verify(stageStore, never()).findStageByType(any(), any());
        verify(stageStore, never()).recordEvent(any(), any(), any(), any());
    }

    @Test
    void recordProgressDualWritesOneLocalPrAndDevelopmentMilestone()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        UUID developmentStageId = UUID.randomUUID();
        StageInstance development = new StageInstance(
                developmentStageId, "task1", StageType.DEVELOPMENT_STAGE,
                StageState.OPEN, NOW, null, null);
        when(stageStore.findStageByType("task1", StageType.DEVELOPMENT_STAGE))
                .thenReturn(Optional.of(development));
        when(store.timelineFor("pr1")).thenReturn(List.of());

        service.recordProgress("pr1", PRTimelineEntry.PHASE_STARTING);

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_PULL_REQUEST_PROGRESS);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_AGENT);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().payloadJson()).contains(
                "\"phase\":\"starting\"", "\"branch\":\"dev/x\"", "\"baseBranch\":\"main\"");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> stagePayload = ArgumentCaptor.forClass(Map.class);
        verify(stageStore).recordEvent(
                eq(developmentStageId), eq("task1"), eq(StageEventType.PULL_REQUEST_PROGRESS),
                stagePayload.capture());
        assertThat(stagePayload.getValue()).containsEntry("phase", "starting")
                .containsEntry("branch", "dev/x")
                .containsEntry("baseBranch", "main");
    }

    @Test
    void recordProgressIsIdempotentPerPhase()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);
        when(store.timelineFor("pr1")).thenReturn(List.of(new PRTimelineEntry(
                "progress", "pr1", PRTimelineEntry.TYPE_PULL_REQUEST_PROGRESS,
                PRTimelineEntry.ACTOR_AGENT, true, null, NOW,
                "{\"phase\":\"starting\"}", null)));

        service.recordProgress("pr1", PRTimelineEntry.PHASE_STARTING);

        verify(store, never()).addEvent(any());
        verify(stageStore, never()).recordEvent(any(), any(), any(), any());
    }

    @Test
    void recordPushFailureDualWritesBoundedReasonAndFailedStep()
            throws Exception
    {
        pr(PR.STATUS_LOCAL_OPEN);
        UUID developmentStageId = UUID.randomUUID();
        StageInstance development = new StageInstance(
                developmentStageId, "task1", StageType.DEVELOPMENT_STAGE,
                StageState.CLOSED, NOW, NOW, null);
        when(stageStore.findStageByType("task1", StageType.DEVELOPMENT_STAGE))
                .thenReturn(Optional.of(development));
        String reason = "x".repeat(2_050);

        commands.executeVoid("task1", () -> service.recordPushFailureInCommand(
                "pr1", TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST, reason));

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_PULL_REQUEST_PROGRESS);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_AGENT);
        assertThat(event.getValue().localOnly()).isTrue();
        JsonNode timelinePayload = new ObjectMapper().readTree(event.getValue().payloadJson());
        assertThat(timelinePayload.path("phase").asText()).isEqualTo(PRTimelineEntry.PHASE_FAILED);
        assertThat(timelinePayload.path("failedStep").asText())
                .isEqualTo(TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST);
        assertThat(timelinePayload.path("reason").asText()).hasSize(2_000);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> stagePayload = ArgumentCaptor.forClass(Map.class);
        verify(stageStore).recordEvent(
                eq(developmentStageId), eq("task1"), eq(StageEventType.PULL_REQUEST_PROGRESS),
                stagePayload.capture());
        assertThat(stagePayload.getValue())
                .containsEntry("phase", PRTimelineEntry.PHASE_FAILED)
                .containsEntry("branch", "dev/x")
                .containsEntry("baseBranch", "main")
                .containsEntry("failedStep", TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST);
        assertThat((String) stagePayload.getValue().get("reason")).hasSize(2_000);
    }

    @Test
    void recordProgressRejectsUnknownPhases()
    {
        pr(PR.STATUS_LOCAL_DRAFTED);

        assertThatThrownBy(() -> service.recordProgress("pr1", "pushing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("starting or creating-draft");
        verify(store, never()).addEvent(any());
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
    void recordBrainReviewWritesATimelineEventWhenThePrExists()
    {
        PR existing = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        when(store.findByTaskId("task1")).thenReturn(Optional.of(existing));

        service.recordBrainReview("task1", "dev", "changes_requested", 2, "round-1");

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_REVIEW);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_BRAIN);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().payloadJson())
                .contains("\"reviewEvent\":\"finished\"")
                .contains("\"scope\":\"dev\"").contains("\"verdict\":\"changes_requested\"")
                .contains("\"iteration\":2").contains("\"roundId\":\"round-1\"")
                .doesNotContain("\"body\"");
    }

    @Test
    void recordBrainPlanReviewStartedWritesATimelineEventWhenThePrExists()
            throws Exception
    {
        PR existing = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        when(store.findByTaskId("task1")).thenReturn(Optional.of(existing));

        service.recordBrainReviewStarted("task1", "plan", 1, null);

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        PRTimelineEntry started = event.getValue();
        assertThat(started.prId()).isEqualTo("pr1");
        assertThat(started.eventType()).isEqualTo(PRTimelineEntry.TYPE_REVIEW);
        assertThat(started.actor()).isEqualTo(PRTimelineEntry.ACTOR_BRAIN);
        assertThat(started.localOnly()).isTrue();
        JsonNode payload = new ObjectMapper().readTree(started.payloadJson());
        assertThat(payload.path("reviewEvent").asText()).isEqualTo("started");
        assertThat(payload.path("scope").asText()).isEqualTo("plan");
        assertThat(payload.path("iteration").asInt()).isEqualTo(1);
    }

    @Test
    void recordBrainReviewNoOpsWhenTheTaskHasNoPrYet()
    {
        when(store.findByTaskId("task1")).thenReturn(Optional.empty());

        service.recordBrainReview("task1", "plan", "approved", 1, null);

        verify(store, never()).addEvent(any());
    }

    @Test
    void recordBrainReviewFailureWritesATerminalTimelineEvent()
    {
        PR existing = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        when(store.findByTaskId("task1")).thenReturn(Optional.of(existing));

        service.recordBrainReviewFailed(
                "task1", "dev", 1, "round-1", "brain_review_turn_failed", "run-2");

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(PRTimelineEntry.TYPE_REVIEW);
        assertThat(event.getValue().actor()).isEqualTo(PRTimelineEntry.ACTOR_BRAIN);
        assertThat(event.getValue().localOnly()).isTrue();
        assertThat(event.getValue().payloadJson())
                .contains("\"reviewEvent\":\"failed\"")
                .contains("\"scope\":\"dev\"")
                .contains("\"iteration\":1")
                .contains("\"roundId\":\"round-1\"")
                .contains("\"reason\":\"brain_review_turn_failed\"")
                .contains("\"attemptId\":\"run-2\"");
    }

    @Test
    void replacementRunFailureIsNotDeduplicatedAgainstThePreviousAttempt()
    {
        PR existing = PR.create("pr1", "task1", "dev/x", "main", "T", "", NOW);
        PRTimelineEntry previousAttempt = new PRTimelineEntry(
                "failure-1", "pr1", PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_BRAIN,
                true, null, NOW.minusSeconds(1),
                "{\"reviewEvent\":\"failed\",\"scope\":\"dev\",\"iteration\":1,"
                        + "\"roundId\":\"round-1\",\"attemptId\":\"run-1\"}",
                null);
        when(store.findByTaskId("task1")).thenReturn(Optional.of(existing));
        when(store.timelineFor("pr1")).thenReturn(List.of(previousAttempt));

        service.recordBrainReviewFailed(
                "task1", "dev", 1, "round-1", "brain_review_turn_failed", "run-2");

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        assertThat(event.getValue().payloadJson()).contains("\"attemptId\":\"run-2\"");
    }

    @Test
    void localReviewSubmissionIsAPrivateTimelineBatchAndDispatchSignal()
    {
        pr(PR.STATUS_LOCAL_OPEN);

        service.recordLocalReviewSubmission(
                "pr1", List.of("c1", "summary"), "Please fix both.",
                "REQUEST_CHANGES", "summary");

        ArgumentCaptor<PRTimelineEntry> event = ArgumentCaptor.forClass(PRTimelineEntry.class);
        verify(store).addEvent(event.capture());
        PRTimelineEntry submitted = event.getValue();
        assertThat(submitted.eventType()).isEqualTo(PRTimelineEntry.TYPE_REVIEW);
        assertThat(submitted.actor()).isEqualTo(PRTimelineEntry.ACTOR_USER);
        assertThat(submitted.localOnly()).isTrue();
        assertThat(submitted.payloadJson())
                .contains("\"reviewEvent\":\"submitted\"")
                .contains("\"verdict\":\"REQUEST_CHANGES\"")
                .contains("\"commentIds\":[\"c1\",\"summary\"]")
                .contains("\"findingCount\":2")
                .contains("\"body\":\"Please fix both.\"")
                .contains("\"bodyCommentId\":\"summary\"");
        verify(events).publishEvent(new LocalReviewSubmittedEvent("task1", "pr1"));

        when(store.timelineFor("pr1")).thenReturn(List.of(submitted));
        assertThat(service.localReviewSubmissions("pr1")).containsExactly(
                new PRService.LocalReviewSubmission(
                        NOW, List.of("c1", "summary"), "Please fix both.",
                        "REQUEST_CHANGES", "summary"));
    }

    @Test
    void localReviewSubmissionAlsoWritesTheDurableBatchRowAndBumpsTheEpoch()
    {
        pr(PR.STATUS_LOCAL_OPEN);
        when(submissions.nextSeq("task1")).thenReturn(3L);

        service.recordLocalReviewSubmission(
                "pr1", List.of("c1"), "", "COMMENT", null);

        ArgumentCaptor<LocalReviewSubmission> row =
                ArgumentCaptor.forClass(LocalReviewSubmission.class);
        verify(submissions).insert(row.capture());
        assertThat(row.getValue().taskId()).isEqualTo("task1");
        assertThat(row.getValue().prId()).isEqualTo("pr1");
        assertThat(row.getValue().submissionSeq()).isEqualTo(3L);
        assertThat(row.getValue().rootIdsJson()).isEqualTo("[\"c1\"]");
        assertThat(row.getValue().submittedThroughAt()).isEqualTo(NOW);
        assertThat(row.getValue().activatedAt()).isNull();
        assertThat(row.getValue().isOpen()).isTrue();
        verify(store).incrementLocalReviewEpoch("pr1");
    }

    @Test
    void localReviewSubmissionRevokesOnlyAnUnclaimedPushAuthorization()
    {
        pr(PR.STATUS_LOCAL_OPEN);
        TaskPushAuthorization authorization = mock(TaskPushAuthorization.class);
        when(authorization.token()).thenReturn("push-1");
        when(pushes.findActiveByTask("task1")).thenReturn(Optional.of(authorization));
        when(pushes.revokeIfUnclaimed(eq("push-1"), eq("review_submission_superseded"), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.recordLocalReviewSubmission(
                "pr1", List.of("c1"), "", "COMMENT", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Push already in progress");

        verify(store, never()).addEvent(any());
        verify(submissions, never()).insert(any());
    }

    @Test
    void localReviewSubmissionRevokesAnUnstartedPushBeforeAdmission()
    {
        pr(PR.STATUS_LOCAL_OPEN);
        TaskPushAuthorization authorization = mock(TaskPushAuthorization.class);
        when(authorization.token()).thenReturn("push-1");
        when(pushes.findActiveByTask("task1")).thenReturn(Optional.of(authorization));
        when(pushes.revokeIfUnclaimed(eq("push-1"), eq("review_submission_superseded"), any()))
                .thenReturn(true);

        service.recordLocalReviewSubmission(
                "pr1", List.of("c1"), "", "COMMENT", null);

        verify(pushes).revokeIfUnclaimed(
                eq("push-1"), eq("review_submission_superseded"), any());
        verify(store).addEvent(any());
    }

    @Test
    void localReviewSubmissionsKeepOnlyEachRootsLatestTransitionActive()
    {
        Instant firstAt = NOW.minusSeconds(40);
        Instant updatedAt = NOW.minusSeconds(30);
        Instant reopenedAt = NOW.minusSeconds(20);
        Instant resubmittedAt = NOW.minusSeconds(10);
        PRTimelineEntry first = new PRTimelineEntry(
                "review-1", "pr1", PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_USER,
                true, null, firstAt,
                "{\"reviewEvent\":\"submitted\",\"commentIds\":[\"a\",\"b\"],"
                        + "\"body\":\"first\",\"verdict\":\"REQUEST_CHANGES\"}", null);
        PRTimelineEntry updated = new PRTimelineEntry(
                "review-2", "pr1", PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_USER,
                true, null, updatedAt,
                "{\"reviewEvent\":\"updated\",\"commentId\":\"a\"}", null);
        PRTimelineEntry reopened = new PRTimelineEntry(
                "review-3", "pr1", PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_USER,
                true, null, reopenedAt,
                "{\"reviewEvent\":\"reopened\",\"commentId\":\"b\"}", null);
        PRTimelineEntry resubmitted = new PRTimelineEntry(
                "review-4", "pr1", PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_USER,
                true, null, resubmittedAt,
                "{\"reviewEvent\":\"submitted\",\"commentIds\":[\"a\"],"
                        + "\"body\":\"again\",\"verdict\":\"COMMENT\"}", null);
        when(store.timelineFor("pr1")).thenReturn(List.of(first, updated, reopened, resubmitted));

        assertThat(service.localReviewSubmissions("pr1")).containsExactly(
                new PRService.LocalReviewSubmission(
                        firstAt, List.of(), "first", "REQUEST_CHANGES", null),
                new PRService.LocalReviewSubmission(
                        resubmittedAt, List.of("a"), "again", "COMMENT", null));
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

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition)
        {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status)
        {
        }
    }
}
