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

import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of {@link SqliteStageStore} against the real
 * Flyway-migrated SQLite schema — open/close lifecycle, the matching
 * stage events, the active-stage query, and the unified review comments.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteStageStore
{
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void openStageWritesRowAndOpenedEvent()
    {
        String taskId = seedTask();

        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);

        assertThat(stage.type()).isEqualTo(StageType.DEVELOPMENT_STAGE);
        assertThat(stage.state()).isEqualTo(StageState.OPEN);
        assertThat(stage.closedAt()).isEmpty();
        assertThat(stage.callerStageId()).isEmpty();

        StageInstance reloaded = stageStore.findStageById(stage.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(StageState.OPEN);

        List<StageEvent> events = stageStore.findEventsByStage(stage.id());
        assertThat(events).extracting(StageEvent::eventType).containsExactly(StageEventType.OPENED);
        assertThat(events.get(0).payloadJson()).contains("DEVELOPMENT_STAGE");
    }

    @Test
    void closeStageFlipsStateAndWritesClosedEvent()
    {
        String taskId = seedTask();
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);

        stageStore.closeStage(stage.id(), "done");

        StageInstance closed = stageStore.findStageById(stage.id()).orElseThrow();
        assertThat(closed.state()).isEqualTo(StageState.CLOSED);
        assertThat(closed.closedAt()).isPresent();

        assertThat(stageStore.findEventsByStage(stage.id()))
                .extracting(StageEvent::eventType)
                .containsExactly(StageEventType.OPENED, StageEventType.CLOSED);
    }

    @Test
    void closeStageMergesExtraPayloadIntoTheSingleClosedEvent()
    {
        String taskId = seedTask();
        StageInstance stage = stageStore.openStage(taskId, StageType.REVIEW_STAGE, null);

        stageStore.closeStage(stage.id(), "review_pass_terminated",
                Map.of("findingCount", 3, "agreedCount", 2));

        List<StageEvent> events = stageStore.findEventsByStage(stage.id());
        assertThat(events).extracting(StageEvent::eventType)
                .containsExactly(StageEventType.OPENED, StageEventType.CLOSED);
        // One CLOSED row carrying both the reason and the merged summary.
        String closedPayload = events.get(1).payloadJson();
        assertThat(closedPayload)
                .contains("review_pass_terminated")
                .contains("findingCount")
                .contains("agreedCount");
    }

    @Test
    void reopenStageFlipsClosedBackToOpenAndWritesReopenedEvent()
    {
        String taskId = seedTask();
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        stageStore.closeStage(stage.id(), "ci_green");

        StageInstance reopened = stageStore.reopenStage(stage.id());

        assertThat(reopened.id()).isEqualTo(stage.id());
        assertThat(reopened.state()).isEqualTo(StageState.OPEN);
        assertThat(reopened.closedAt()).isEmpty();
        StageInstance reloaded = stageStore.findStageById(stage.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(StageState.OPEN);
        assertThat(reloaded.closedAt()).isEmpty();
        assertThat(stageStore.findEventsByStage(stage.id()))
                .extracting(StageEvent::eventType)
                .containsExactly(StageEventType.OPENED, StageEventType.CLOSED, StageEventType.REOPENED);
    }

    @Test
    void reopenStageNoOpsWhenTheStageIsNotClosed()
    {
        String taskId = seedTask();
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);

        StageInstance result = stageStore.reopenStage(stage.id());

        assertThat(result.state()).isEqualTo(StageState.OPEN);
        assertThat(stageStore.findEventsByStage(stage.id()))
                .extracting(StageEvent::eventType)
                .containsExactly(StageEventType.OPENED);
    }

    @Test
    void findStageByTypeFindsAStageRegardlessOfState()
    {
        String taskId = seedTask();
        assertThat(stageStore.findStageByType(taskId, StageType.CI_FIXING_STAGE)).isEmpty();

        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        assertThat(stageStore.findStageByType(taskId, StageType.CI_FIXING_STAGE).map(StageInstance::id))
                .hasValue(stage.id());

        stageStore.closeStage(stage.id(), "ci_green");
        // Unlike findLiveStageByType, a closed stage still comes back.
        assertThat(stageStore.findLiveStageByType(taskId, StageType.CI_FIXING_STAGE)).isEmpty();
        assertThat(stageStore.findStageByType(taskId, StageType.CI_FIXING_STAGE).map(StageInstance::id))
                .hasValue(stage.id());
    }

    @Test
    void findActiveStageTracksTheOpenStage()
    {
        String taskId = seedTask();
        assertThat(stageStore.findActiveStage(taskId)).isEmpty();

        StageInstance dev = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        assertThat(stageStore.findActiveStage(taskId).map(StageInstance::id)).hasValue(dev.id());

        stageStore.closeStage(dev.id(), "advance");
        assertThat(stageStore.findActiveStage(taskId)).isEmpty();

        StageInstance cleanup = stageStore.openStage(taskId, StageType.CLEANUP_STAGE, null);
        assertThat(stageStore.findActiveStage(taskId).map(StageInstance::type))
                .hasValue(StageType.CLEANUP_STAGE);
        assertThat(stageStore.findStagesByTask(taskId)).hasSize(2);
        // closeStage is idempotent — closing an already-closed stage no-ops.
        stageStore.closeStage(cleanup.id(), "done");
        stageStore.closeStage(cleanup.id(), "done-again");
        assertThat(stageStore.findEventsByStage(cleanup.id()))
                .extracting(StageEvent::eventType)
                .containsExactly(StageEventType.OPENED, StageEventType.CLOSED);
    }

    @Test
    void metadataUpdatesPreserveClosedLifecycleColumns()
    {
        String taskId = seedTask();
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant closedAt = Instant.parse("2026-06-20T10:30:00Z");
        assertThat(stageStore.updateStateIf(stage.id(), StageState.OPEN, StageState.CLOSED, closedAt))
                .isTrue();

        WorkModel workModel = new WorkModel(
                WorkModelKind.API, "openai", "gpt-5", "team");
        stageStore.updateMetricsJson(stage.id(), "{\"turns\":7}");
        stageStore.updateWorkModel(stage.id(), workModel);

        StageInstance reloaded = stageStore.findStageById(stage.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(StageState.CLOSED);
        assertThat(reloaded.closedAt()).contains(closedAt);
        assertThat(reloaded.workModel()).isEqualTo(workModel);
        assertThat(stageStore.findMetricsJson(stage.id())).contains("{\"turns\":7}");
    }

    @Test
    void lifecycleCompareAndSetPreservesMetadataColumns()
    {
        String taskId = seedTask();
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        WorkModel workModel = new WorkModel(
                WorkModelKind.CLI, "codex", "gpt-5.6-sol", null);
        stageStore.updateMetricsJson(stage.id(), "{\"costUsd\":1.25}");
        stageStore.updateWorkModel(stage.id(), workModel);

        Instant closedAt = Instant.parse("2026-06-20T10:45:00Z");
        assertThat(stageStore.updateStateIf(stage.id(), StageState.OPEN, StageState.CLOSED, closedAt))
                .isTrue();

        StageInstance reloaded = stageStore.findStageById(stage.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(StageState.CLOSED);
        assertThat(reloaded.closedAt()).contains(closedAt);
        assertThat(reloaded.workModel()).isEqualTo(workModel);
        assertThat(stageStore.findMetricsJson(stage.id())).contains("{\"costUsd\":1.25}");
    }

    @Test
    void reviewCommentRoundTripsAndQueries()
    {
        String taskId = seedTask();
        Instant now = Instant.parse("2026-06-20T10:00:00Z");
        Instant postedAt = Instant.parse("2026-06-20T10:01:00Z");

        ReviewComment unresolved = stageStore.saveReviewComment(new ReviewComment(
                null, taskId, "src/Foo.java", 12, "nit: rename", now,
                ReviewCommentSource.LOCAL_USER, null, false, 123L, null, "draft", now, postedAt,
                "RIGHT", null, null));
        stageStore.saveReviewComment(new ReviewComment(
                null, taskId, "src/Bar.java", 3, "addressed", now,
                ReviewCommentSource.LOCAL_USER, null, true, null, null, null, null, "RIGHT", null, null));

        assertThat(stageStore.findReviewCommentById(unresolved.id()))
                .get().extracting(ReviewComment::draftReplyPostedAt).isEqualTo(postedAt);
        assertThat(stageStore.findUnresolvedComments(taskId))
                .extracting(ReviewComment::file)
                .containsExactly("src/Foo.java");
        assertThat(stageStore.findCommentsBySource(taskId, ReviewCommentSource.LOCAL_USER)).hasSize(2);
        assertThat(stageStore.findCommentsBySource(taskId, ReviewCommentSource.REMOTE_REVIEWER)).isEmpty();
    }

    @Test
    void persistsGeneralRemoteCommentsAndTheirResolutionNotification()
    {
        String taskId = seedTask();
        Instant now = Instant.parse("2026-06-20T10:00:00Z");
        ReviewComment openGeneral = stageStore.saveReviewComment(new ReviewComment(
                null, taskId, null, 0, "question", now,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/acme/widgets/pull/42#issuecomment-1",
                false, 1L, null, null, null, "RIGHT", null, null));
        stageStore.saveReviewComment(new ReviewComment(
                null, taskId, "src/Foo.java", 12, "already resolved", now,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/acme/widgets/pull/42#discussion_r2",
                true, 2L, null, null, null, "RIGHT", null, null));

        assertThat(stageStore.findReviewCommentByRemoteLink(openGeneral.remoteLink()))
                .get().extracting(ReviewComment::file).isNull();

        stageStore.markRemoteThreadResolutionPosted(openGeneral.id(), now);
        assertThat(stageStore.isRemoteThreadResolutionPosted(openGeneral.id())).isTrue();
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Stage store test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
