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
    void reviewCommentRoundTripsAndQueries()
    {
        String taskId = seedTask();
        Instant now = Instant.parse("2026-06-20T10:00:00Z");

        ReviewComment unresolved = stageStore.saveReviewComment(new ReviewComment(
                null, taskId, "src/Foo.java", 12, "nit: rename", now,
                ReviewCommentSource.LOCAL_USER, null, false, null, null, null, null));
        stageStore.saveReviewComment(new ReviewComment(
                null, taskId, "src/Bar.java", 3, "addressed", now,
                ReviewCommentSource.LOCAL_USER, null, true, null, null, null, null));

        assertThat(stageStore.findReviewCommentById(unresolved.id())).isPresent();
        assertThat(stageStore.findUnresolvedComments(taskId))
                .extracting(ReviewComment::file)
                .containsExactly("src/Foo.java");
        assertThat(stageStore.findCommentsBySource(taskId, ReviewCommentSource.LOCAL_USER)).hasSize(2);
        assertThat(stageStore.findCommentsBySource(taskId, ReviewCommentSource.REMOTE_REVIEWER)).isEmpty();
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
