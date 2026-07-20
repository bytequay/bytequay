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

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAbstractCliThreadAgentRecovery
{
    private static final String MCP_FAILURE =
            "MCP tool mcp__bytequay__approval_prompt (passed via --permission-prompt-tool) "
                    + "not found. Available MCP tools: none";

    @TempDir
    Path tempDir;

    @Test
    void retriesRecoverableStartupFailureOnceWithoutEchoingThePromptAgain()
            throws Exception
    {
        try (Harness harness = harness(List.of(MCP_FAILURE))) {
            harness.agent.send("inspect the image").get(5, TimeUnit.SECONDS);

            assertThat(harness.agent.attempts).isEqualTo(2);
            assertThat(harness.agent.cleanups).isEqualTo(1);
            assertThat(harness.agent.status()).isEqualTo(ThreadStatus.IDLE);
            verify(harness.store, times(1)).appendMessage(
                    argThat(message -> "user".equals(message.role())));
        }
    }

    @Test
    void stopsAfterOneAutomaticRecoveryAttemptAndPersistsTheError()
            throws Exception
    {
        try (Harness harness = harness(List.of(MCP_FAILURE, MCP_FAILURE))) {
            harness.agent.send("inspect the image").get(5, TimeUnit.SECONDS);

            assertThat(harness.agent.attempts).isEqualTo(2);
            assertThat(harness.agent.status()).isEqualTo(ThreadStatus.ERRORED);
            assertThat(harness.agent.lastErrorDetail()).contains(MCP_FAILURE);
            verify(harness.store, atLeastOnce()).saveThread(
                    argThat(thread ->
                            thread.status() == ThreadStatus.ERRORED
                                    && thread.errorMessage() != null
                                    && thread.errorMessage().contains(MCP_FAILURE)));
        }
    }

    @Test
    void doesNotRetryAnUnrelatedCliFailure()
            throws Exception
    {
        try (Harness harness = harness(List.of("authentication failed"))) {
            harness.agent.send("inspect the image").get(5, TimeUnit.SECONDS);

            assertThat(harness.agent.attempts).isEqualTo(1);
            assertThat(harness.agent.cleanups).isZero();
            assertThat(harness.agent.status()).isEqualTo(ThreadStatus.ERRORED);
        }
    }

    private Harness harness(List<String> failures)
    {
        Thread thread = new Thread(
                "thread-recovery", ThreadKind.CLI_AGENT, "claude-code", null,
                "Recovery test", ThreadStatus.IDLE, "claude-opus-4-8",
                0L, 0L, 0L,
                Instant.parse("2026-07-20T07:00:00Z"), Instant.parse("2026-07-20T07:00:00Z"),
                null, null, ThreadFlow.BUILD, "ws-test", null, null);
        ThreadStore store = mock(ThreadStore.class);
        when(store.listMessages(anyString())).thenReturn(List.of());
        when(store.findThreadById(anyString())).thenReturn(Optional.of(thread));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecoveringAgent agent = new RecoveringAgent(
                thread, store, mock(TaskStore.class), mock(CliStreamParser.class),
                new ObjectMapper(), new McpPermissionGate(), executor, tempDir, failures);
        return new Harness(agent, store, executor);
    }

    private record Harness(RecoveringAgent agent, ThreadStore store, ExecutorService executor)
            implements AutoCloseable
    {
        @Override
        public void close()
        {
            executor.shutdownNow();
        }
    }

    private static final class RecoveringAgent
            extends AbstractCliThreadAgent
    {
        private final List<String> failures;
        private int attempts;
        private int cleanups;

        private RecoveringAgent(
                Thread thread,
                ThreadStore store,
                TaskStore taskStore,
                CliStreamParser parser,
                ObjectMapper mapper,
                McpPermissionGate gate,
                ExecutorService executor,
                Path cwd,
                List<String> failures)
        {
            super(thread, store, taskStore, parser, mapper, gate, executor,
                    CheckpointTrigger.NOOP, "/bin/sh", cwd.toString(), null, null);
            this.failures = failures;
        }

        @Override
        protected ProcessBuilder buildCommand(String userInput)
        {
            String failure = attempts < failures.size() ? failures.get(attempts) : null;
            attempts++;
            ProcessBuilder command = new ProcessBuilder(
                    binary,
                    "-c",
                    failure == null
                            ? "cat >/dev/null; exit 0"
                            : "cat >/dev/null; printf '%s\\n' \"$BYTEQUAY_RECOVERY_TEST_ERROR\" >&2; exit 1");
            command.directory(Path.of(workingDir).toFile());
            if (failure != null) {
                command.environment().put("BYTEQUAY_RECOVERY_TEST_ERROR", failure);
            }
            return command;
        }

        @Override
        protected boolean shouldAutomaticallyRecover(String errorDetail)
        {
            return errorDetail.contains(MCP_FAILURE);
        }

        @Override
        protected void cleanupProviderResources()
        {
            cleanups++;
        }
    }
}
