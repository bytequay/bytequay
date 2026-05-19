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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of {@link SqliteTaskStore} against the real
 * Flyway-migrated SQLite schema. Catches schema/entity drift, JPA
 * mapping bugs, and converter issues that the smoke test wouldn't.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteTaskStore
{
    @Autowired
    private TaskStore store;

    @Test
    void roundtripsACliAgentTask()
    {
        Task task = newTask(TaskKind.CLI_AGENT, TaskStatus.RUNNING, 1234, "/tmp/log.jsonl");
        store.saveTask(task);

        Optional<Task> loaded = store.findTaskById(task.id());
        assertThat(loaded).isPresent();
        Task got = loaded.get();
        assertThat(got.id()).isEqualTo(task.id());
        assertThat(got.kind()).isEqualTo(TaskKind.CLI_AGENT);
        assertThat(got.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(got.processPid()).isEqualTo(1234);
        assertThat(got.logPath()).isEqualTo("/tmp/log.jsonl");
        assertThat(got.metadataJson()).isEqualTo("{\"originPr\":\"trinodb/trino#42\"}");
    }

    @Test
    void roundtripsALogicLoopTaskWithNullCliFields()
    {
        Task task = newTask(TaskKind.LOGIC_LOOP, TaskStatus.PENDING, null, null);
        store.saveTask(task);

        Task got = store.findTaskById(task.id()).orElseThrow();
        assertThat(got.kind()).isEqualTo(TaskKind.LOGIC_LOOP);
        assertThat(got.processPid()).isNull();
        assertThat(got.logPath()).isNull();
        assertThat(got.agentSessionId()).isNull();
    }

    @Test
    void saveTaskUpdatesInPlaceWhenIdMatches()
    {
        Task initial = newTask(TaskKind.CLI_AGENT, TaskStatus.RUNNING, 99, "/tmp/x.jsonl");
        store.saveTask(initial);

        Task updated = new Task(
                initial.id(), initial.kind(), initial.provider(), "agent-session-abc",
                initial.title(), TaskStatus.AWAITING, initial.workingDir(),
                initial.branchName(), initial.model(),
                /* costUsdMilli */ 12_345L, /* tokensIn */ 1_000L, /* tokensOut */ 2_000L,
                initial.processPid(), initial.logPath(),
                initial.createdAt(), Instant.parse("2026-05-15T13:00:00Z"),
                /* endedAt */ null, /* errorMessage */ null,
                initial.metadataJson(),
                initial.taskType(), initial.linkedPrNumber(), initial.linkedIssueNumber());
        store.saveTask(updated);

        Task got = store.findTaskById(initial.id()).orElseThrow();
        assertThat(got.status()).isEqualTo(TaskStatus.AWAITING);
        assertThat(got.agentSessionId()).isEqualTo("agent-session-abc");
        assertThat(got.costUsdMilli()).isEqualTo(12_345L);
        assertThat(got.tokensIn()).isEqualTo(1_000L);
        assertThat(got.updatedAt()).isEqualTo(Instant.parse("2026-05-15T13:00:00Z"));
    }

    @Test
    void listTasksByStatusOrdersNewestFirstAndCapsToLimit()
    {
        Instant base = Instant.parse("2026-05-15T12:00:00Z");
        for (int i = 0; i < 5; i++) {
            Task t = withTimestamps(
                    newTask(TaskKind.CLI_AGENT, TaskStatus.IDLE, 7000 + i, "/tmp/" + i + ".jsonl"),
                    base, base.plusSeconds(i));
            store.saveTask(t);
        }

        List<Task> page = store.listTasksByStatus(TaskStatus.IDLE, 3);
        assertThat(page).hasSize(3);
        // Newest updated_at_ms first.
        assertThat(page.get(0).updatedAt()).isAfterOrEqualTo(page.get(1).updatedAt());
        assertThat(page.get(1).updatedAt()).isAfterOrEqualTo(page.get(2).updatedAt());
        // Status filter actually filtered.
        assertThat(page).allSatisfy(t -> assertThat(t.status()).isEqualTo(TaskStatus.IDLE));
    }

    @Test
    void listTasksByStatusRejectsNonPositiveLimit()
    {
        assertThatThrownBy(() -> store.listTasksByStatus(TaskStatus.IDLE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
    }

    @Test
    void appendsAndListsMessagesInSeqOrder()
    {
        Task task = newTask(TaskKind.CLI_AGENT, TaskStatus.RUNNING, 4242, "/tmp/m.jsonl");
        store.saveTask(task);

        store.appendMessage(message(task.id(), 0, "user", "text", "{\"text\":\"fix the bug\"}"));
        store.appendMessage(message(task.id(), 1, "assistant", "thinking", "{\"summary\":\"reading the file\"}"));
        store.appendMessage(message(task.id(), 2, "tool", "tool_call",
                "{\"toolName\":\"Read\",\"input\":{\"path\":\"src/main.ts\"}}"));

        List<TaskMessage> all = store.listMessages(task.id());
        assertThat(all).extracting(TaskMessage::seq).containsExactly(0L, 1L, 2L);
        assertThat(all).extracting(TaskMessage::type)
                .containsExactly("text", "thinking", "tool_call");
    }

    @Test
    void recordFileUpsertsByCompositeKey()
    {
        Task task = newTask(TaskKind.LOGIC_LOOP, TaskStatus.RUNNING, null, null);
        store.saveTask(task);

        Instant first = Instant.parse("2026-05-15T12:00:00Z");
        Instant second = Instant.parse("2026-05-15T12:05:00Z");
        store.recordFile(new TaskFile(task.id(), "src/foo.ts", "read", 1, 0, 0, first));
        // Same (taskId, path) — should overwrite, not duplicate.
        store.recordFile(new TaskFile(task.id(), "src/foo.ts", "edit", 3, 12, 4, second));
        // Different path — separate row.
        store.recordFile(new TaskFile(task.id(), "src/bar.ts", "write", 1, 50, 0, first));

        List<TaskFile> got = store.listFiles(task.id());
        assertThat(got).hasSize(2);
        TaskFile foo = got.stream().filter(f -> f.path().equals("src/foo.ts")).findFirst().orElseThrow();
        assertThat(foo.operation()).isEqualTo("edit");
        assertThat(foo.count()).isEqualTo(3);
        assertThat(foo.linesAdded()).isEqualTo(12);
        assertThat(foo.lastTouchedAt()).isEqualTo(second);
    }

    private static Task newTask(TaskKind kind, TaskStatus status, Integer pid, String logPath)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Task(
                UUID.randomUUID().toString(),
                kind,
                "claude-code",
                /* agentSessionId */ null,
                "Build daily standup feature",
                status,
                "/Users/jack.chen/IdeaProjects/bytequay",
                /* branchName */ "main",
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                pid,
                logPath,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                "{\"originPr\":\"trinodb/trino#42\"}",
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null);
    }

    private static Task withTimestamps(Task source, Instant created, Instant updated)
    {
        return new Task(
                source.id(), source.kind(), source.provider(), source.agentSessionId(),
                source.title(), source.status(), source.workingDir(), source.branchName(),
                source.model(), source.costUsdMilli(), source.tokensIn(), source.tokensOut(),
                source.processPid(), source.logPath(), created, updated,
                source.endedAt(), source.errorMessage(), source.metadataJson(),
                source.taskType(), source.linkedPrNumber(), source.linkedIssueNumber());
    }

    private static TaskMessage message(String taskId, long seq, String role, String type, String contentJson)
    {
        return new TaskMessage(
                UUID.randomUUID().toString(),
                taskId,
                seq,
                role,
                type,
                contentJson,
                /* durationMs */ null,
                /* tokensIn */ null,
                /* tokensOut */ null,
                /* costUsdMilli */ null,
                Instant.parse("2026-05-15T12:00:00Z").plusSeconds(seq));
    }
}
