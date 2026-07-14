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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.skills.CavemanPrompt;
import com.bytequay.app.service.skills.ManagedSkill;
import com.bytequay.app.service.skills.ManagedSkillBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestClaudeCodeCliThreadAgentPonytailSkill
{
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final String CWD = "/tmp/wt-claude";

    @Test
    void activeManagedPonytailMaterializesHiddenSkill()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        ClaudeCodeCliThreadAgent agent = new ClaudeCodeCliThreadAgent(
                thread(), new EmptyThreadStore(), new EmptyTaskStore(),
                new StreamJsonParser(mapper), mapper, new McpPermissionGate(),
                sameThreadExecutor(), CheckpointTrigger.NOOP,
                () -> "", null, "TASK ROLE", CWD, ClaudeCodeCliThreadAgent.TrunkMode.ENABLED);
        agent.setManagedSkillBundle(new ManagedSkillBundle(
                "test", "test", Map.of("ponytail",
                        new ManagedSkill("ponytail", "name: ponytail\n\nsmallest working change"))));
        agent.setActiveManagedSkillNames(List.of("ponytail"));

        try {
            ProcessBuilder command = agent.buildCommand("implement");
            Path skill = Path.of(command.environment().get("BYTEQUAY_SKILLS_DIR"))
                    .resolve("ponytail")
                    .resolve("SKILL.md");

            assertThat(Files.readString(skill))
                    .contains("name: ponytail")
                    .contains("smallest working change");
            assertThat(command.command())
                    .containsSubsequence("--append-system-prompt", "TASK ROLE");
        }
        finally {
            agent.cleanupProviderResources();
        }
    }

    @Test
    void activeCavemanIsInjectedAsAClaudeSystemPrompt()
    {
        ObjectMapper mapper = new ObjectMapper();
        ClaudeCodeCliThreadAgent agent = new ClaudeCodeCliThreadAgent(
                thread(), new EmptyThreadStore(), new EmptyTaskStore(),
                new StreamJsonParser(mapper), mapper, new McpPermissionGate(),
                sameThreadExecutor(), CheckpointTrigger.NOOP,
                () -> "", null, "TASK ROLE", CWD, ClaudeCodeCliThreadAgent.TrunkMode.ENABLED);
        agent.setManagedSkillBundle(new ManagedSkillBundle(
                "test", "test", Map.of(CavemanPrompt.NAME,
                        new ManagedSkill(CavemanPrompt.NAME, "CAVEMAN BODY"))));
        agent.setActiveManagedSkillNames(List.of(CavemanPrompt.NAME));

        try {
            assertThat(agent.buildCommand("implement").command())
                    .containsSubsequence("--append-system-prompt",
                            "# ByteQuay managed runtime skills\n\n## caveman\n\nCAVEMAN BODY");
        }
        finally {
            agent.cleanupProviderResources();
        }
    }

    private static Thread thread()
    {
        return new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Claude stage test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }

    private static ExecutorService sameThreadExecutor()
    {
        return new AbstractExecutorService()
        {
            private volatile boolean shutdown;

            @Override public void shutdown() { shutdown = true; }
            @Override
            public List<Runnable> shutdownNow()
            {
                shutdown = true;
                return List.of();
            }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    private static final class EmptyThreadStore
            implements ThreadStore
    {
        @Override public void saveThread(Thread thread) { throw new UnsupportedOperationException(); }
        @Override public Optional<Thread> findThreadById(String id) { throw new UnsupportedOperationException(); }
        @Override public void deleteThread(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Thread> listTasksByStatus(ThreadStatus status, int limit) { return List.of(); }
        @Override public List<Thread> listTasksByIds(Collection<String> ids) { return List.of(); }
        @Override public List<Thread> listThreadsUpdatedSince(Instant since) { return List.of(); }
        @Override public void appendMessage(ThreadMessage message) { throw new UnsupportedOperationException(); }
        @Override public List<ThreadMessage> listMessages(String threadId) { return List.of(); }
        @Override public void recordFile(ThreadFile file) { throw new UnsupportedOperationException(); }
        @Override public List<ThreadFile> listFiles(String threadId) { return List.of(); }
    }

    private static final class EmptyTaskStore
            implements TaskStore
    {
        @Override public void saveTask(Task task) { throw new UnsupportedOperationException(); }
        @Override public Optional<Task> findTaskById(String id) { return Optional.empty(); }
        @Override public void deleteTask(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Task> listTasksByThread(String threadId) { return List.of(); }
        @Override public List<Task> activeTasksForThread(String threadId) { return List.of(); }
        @Override public boolean hasActiveTask(String threadId) { return false; }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) { return Optional.empty(); }
        @Override public Optional<Long> maxSeqForThread(String threadId) { return Optional.empty(); }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) { throw new UnsupportedOperationException(); }
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }
}
