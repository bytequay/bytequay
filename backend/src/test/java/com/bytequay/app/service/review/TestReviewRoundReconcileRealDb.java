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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs {@link ReviewRoundServiceImpl#reconcile} against the real
 * repositories rather than mocks. {@code review_comment.round_id} carries a
 * genuine foreign key onto {@code review_round}; a mocked {@link StageStore}
 * can't enforce it, which is exactly how a statement-ordering regression
 * (assigning comments to a round before that round row exists) shipped
 * undetected — every reconcile in production threw {@code
 * SQLITE_CONSTRAINT_FOREIGNKEY} and no round ever opened.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestReviewRoundReconcileRealDb
{
    @Autowired
    private ReviewRoundService service;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private ReviewRoundStore roundStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void reconcileOpensARoundWithoutViolatingTheCommentRoundIdForeignKey()
    {
        Instant now = Instant.parse("2026-07-06T09:00:00Z");
        String threadId = UUID.randomUUID().toString();
        threadStore.saveThread(new Thread(
                threadId, ThreadKind.CLI_AGENT, "claude-code", null, "Round reconcile test",
                ThreadStatus.IDLE, "claude-sonnet-4.6", 0L, 0L, 0L, now, now,
                null, null, ThreadFlow.BUILD, "ws-default", null, null));

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, threadId, 1L, TaskStatus.IN_REVIEW, "dev/x", "/tmp/wt", "main", "/tmp/clone",
                null, null, null, null, null, "DEVELOP", 42, null,
                0L, 0L, 0L, null, now, null, null, null, null, null,
                null, TaskPhase.AWAITING_REMOTE_REVIEW, null, 0, "acme/widgets#42"));
        // linkedPrRef is entity-managed (not written by saveTask) — the
        // dedicated method is the only way to actually persist it.
        taskStore.linkTaskToPr(taskId, "acme/widgets#42");

        // The service's real bean runs on the system clock (not the fixed
        // clock the mock-based unit tests inject), so the debounce window
        // must be measured against the real Instant.now(), not the fixture
        // "now" above.
        stageStore.saveReviewComment(new ReviewComment(
                UUID.randomUUID(), taskId, "Foo.java", 10, "please fix",
                Instant.now().minus(Duration.ofMinutes(15)),
                ReviewCommentSource.REMOTE_REVIEWER, "https://github.com/acme/widgets/pull/42#discussion_r1",
                false, 1L, null, null, null));

        Task task = taskStore.findTaskById(taskId).orElseThrow();
        assertThatCode(() -> service.reconcile(task)).doesNotThrowAnyException();

        assertThat(stageStore.findUnroundedRemoteComments(taskId)).isEmpty();
        assertThat(roundStore.findLiveByTask(taskId)).isPresent();
    }
}
