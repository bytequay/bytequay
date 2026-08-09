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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.IterationStore;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brain agent's context digest: a placeholder when there are no
 * summaries, newest-first stage-labelled bullets when there are, and
 * tail-trimming (oldest dropped first) when over the token cap.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestAgentContextDigest
{
    @Autowired
    private AgentContextDigest digest;
    @Autowired
    private IterationStore iterationStore;
    @Autowired
    private SqliteStageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void placeholderWhenNoSummaries()
    {
        String taskId = seedTask();
        assertThat(digest.build(taskId, AgentContextDigest.DEFAULT_CAP_TOKENS))
                .contains("no iteration summaries yet");
    }

    @Test
    void rendersStageLabelledBulletsNewestFirst()
    {
        String taskId = seedTask();
        UUID stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null).id();
        summary(taskId, stage, 1, "fix #1: bumped retry default", Instant.parse("2026-06-21T10:00:00Z"));
        summary(taskId, stage, 2, "fix #2: widened timeout", Instant.parse("2026-06-21T10:05:00Z"));

        String out = digest.build(taskId, AgentContextDigest.DEFAULT_CAP_TOKENS);

        assertThat(out).contains("CiFixingStage #2").contains("CiFixingStage #1");
        // Newest first: #2 appears before #1.
        assertThat(out.indexOf("#2")).isLessThan(out.indexOf("#1"));
        assertThat(out).contains("## How to answer");
    }

    @Test
    void trimsOldestWhenOverTokenCap()
    {
        String taskId = seedTask();
        UUID stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null).id();
        summary(taskId, stage, 1, "OLDEST summary line", Instant.parse("2026-06-21T10:00:00Z"));
        summary(taskId, stage, 2, "NEWEST summary line", Instant.parse("2026-06-21T10:05:00Z"));

        // Cap so tight only the newest survives (~length/4 token estimate).
        String out = digest.build(taskId, 30);

        assertThat(out).contains("NEWEST summary line");
        assertThat(out).doesNotContain("OLDEST summary line");
    }

    @Test
    void estimateTokensIsZeroForBlankAndPositiveOtherwise()
    {
        assertThat(digest.estimateTokens("")).isZero();
        assertThat(digest.estimateTokens("   ")).isZero();
        String text = "the quick brown fox jumps over the lazy dog";
        assertThat(digest.estimateTokens(text)).isPositive();
        // The blended heuristic never under-reads the char/4 floor.
        assertThat(digest.estimateTokens(text)).isGreaterThanOrEqualTo(text.length() / 4);
    }

    private void summary(String taskId, UUID stageId, int n, String text, Instant at)
    {
        UUID id = UUID.randomUUID();
        iterationStore.save(TaskStageIteration
                .opened(id, stageId, taskId, "turn-" + n, n, "red_ci", at)
                .withSummary(text, at));
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-21T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Digest test", ThreadStatus.RUNNING, "claude-sonnet-4-6",
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
