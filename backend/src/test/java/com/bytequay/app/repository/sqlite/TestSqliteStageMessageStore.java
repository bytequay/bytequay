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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of {@link SqliteStageMessageStore} against the real
 * Flyway-migrated {@code stage_messages} table. The key invariant: each stage
 * owns its own {@code seq} space, so two stages can both write {@code seq=0}
 * without colliding on the thread-global key the old shared table enforced.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteStageMessageStore
{
    private static final Instant NOW = Instant.parse("2026-06-29T09:00:00Z");

    @Autowired
    private SqliteStageMessageStore store;
    @Autowired
    private SqliteStageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void roundtripsAStageTranscriptInSeqOrder()
    {
        Seed s = seedStage();
        store.appendMessage(msg(s, 0, "user", "go", 3L, 0L));
        store.appendMessage(msg(s, 1, "assistant", "on it", 0L, 7L));
        store.appendMessage(msg(s, 2, "assistant", "done", 0L, 4L));

        List<ThreadMessage> got = store.listMessages(s.stageId);
        assertThat(got).extracting(ThreadMessage::seq).containsExactly(0L, 1L, 2L);
        assertThat(got).extracting(ThreadMessage::contentJson).containsExactly("go", "on it", "done");
        assertThat(got).allSatisfy(m -> {
            assertThat(m.scope()).isEqualTo(ThreadScope.STAGE);
            assertThat(m.stageId()).isEqualTo(s.stageId);
            assertThat(m.taskId()).isEqualTo(s.taskId);
            assertThat(m.threadId()).isEqualTo(s.threadId);
        });
    }

    @Test
    void twoStagesShareNoSeqSpaceSoSeqZeroNeverCollides()
    {
        Seed a = seedStage();
        // A second stage on the SAME task + thread — the case that used to
        // collide on the thread-global (thread_id, seq).
        StageInstance second = stageStore.openStage(a.taskId, StageType.CI_FIXING_STAGE, null);
        Seed b = new Seed(a.threadId, a.taskId, second.id().toString());

        store.appendMessage(msg(a, 0, "assistant", "stage-a-0", 0L, 1L));
        store.appendMessage(msg(b, 0, "assistant", "stage-b-0", 0L, 1L));   // same seq, different stage — OK

        assertThat(store.listMessages(a.stageId)).extracting(ThreadMessage::contentJson)
                .containsExactly("stage-a-0");
        assertThat(store.listMessages(b.stageId)).extracting(ThreadMessage::contentJson)
                .containsExactly("stage-b-0");
        // Per-task aggregation unions both stages.
        assertThat(store.listMessagesByTask(a.taskId)).hasSize(2);
    }

    @Test
    void reportsMaxSeqAndTokenSumForTheNextSeqSeedAndMetrics()
    {
        Seed s = seedStage();
        assertThat(store.maxMessageSeq(s.stageId)).isEmpty();

        store.appendMessage(msg(s, 0, "user", "a", 5L, 0L));
        store.appendMessage(msg(s, 1, "assistant", "b", 0L, 11L));

        assertThat(store.maxMessageSeq(s.stageId)).hasValue(1L);
        assertThat(store.sumTokensBetween(s.stageId, 0, 1)).isEqualTo(16L);
        assertThat(store.listMessagesBetween(s.stageId, 1, 1)).extracting(ThreadMessage::contentJson)
                .containsExactly("b");
    }

    @Test
    void deleteByStageDropsOnlyThatStagesTranscript()
    {
        Seed a = seedStage();
        StageInstance second = stageStore.openStage(a.taskId, StageType.CI_FIXING_STAGE, null);
        Seed b = new Seed(a.threadId, a.taskId, second.id().toString());
        store.appendMessage(msg(a, 0, "assistant", "keep", 0L, 1L));
        store.appendMessage(msg(b, 0, "assistant", "drop", 0L, 1L));

        store.deleteByStage(b.stageId);

        assertThat(store.listMessages(a.stageId)).hasSize(1);
        assertThat(store.listMessages(b.stageId)).isEmpty();
    }

    private ThreadMessage msg(Seed s, long seq, String role, String text, Long tokensIn, Long tokensOut)
    {
        return new ThreadMessage(
                UUID.randomUUID().toString(), s.threadId, s.taskId, seq, role, "text", text,
                /* durationMs */ null, tokensIn, tokensOut, /* costUsdMilli */ 0L,
                NOW, s.stageId, ThreadScope.STAGE);
    }

    private Seed seedStage()
    {
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Stage message test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        return new Seed(thread.id(), taskId, stage.id().toString());
    }

    private record Seed(String threadId, String taskId, String stageId) {}
}
