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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexAppServerClient
{
    @TempDir
    private Path worktree;

    @ParameterizedTest
    @ValueSource(strings = {"Tool call", "Command"})
    void expectedReviewChildDenialsAreDebugDiagnostics(String call)
    {
        String line = "2026-10-05T01:39:19.940429Z ERROR codex_core::tools::router: error=" + call
                + " blocked by PreToolUse hook: ByteQuay review child scope: use context, CodeGraph, memory SEARCH/READ, sandboxed source searches, or messages to the main agent.";
        assertThat(CodexAppServerClient.diagnosticLevel(line)).isEqualTo(Level.DEBUG);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "codex_core::tools::router: error=Tool call blocked by PreToolUse hook: ByteQuay could not read this tool call, so it was refused.",
            "codex_core::tools::router: error=PreToolUse hook failed: exit 1",
            "codex_core::tools::router: error=PreToolUse hook timed out",
            "codex_core::tools::router: error=Tool call blocked by PreToolUse hook: Some other policy denial",
            "ByteQuay review child scope: use context, CodeGraph, memory SEARCH/READ",
            "codex_core::tools::router: error=provider connection failed"})
    void unexpectedDiagnosticsRemainWarnings(String line)
    {
        assertThat(CodexAppServerClient.diagnosticLevel(line)).isEqualTo(Level.WARN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closeRetainsVerifiedRetryWhenTheOriginalExitArrivesBeforeOrAfterStop(boolean exitBeforeStop)
    {
        ObjectMapper objectMapper = new ObjectMapper();
        RetryingProcess process = new RetryingProcess(objectMapper);
        List<String> acknowledged = new CopyOnWriteArrayList<>();
        ProcessSupervisor supervisor = new ProcessSupervisor()
        {
            @Override
            public PreparedProcess prepare(ProcessSpec spec)
            {
                return new PreparedProcess()
                {
                    @Override
                    public String launchId()
                    {
                        return process.launchId();
                    }

                    @Override
                    public ManagedProcess start(ProcessOutputSink output)
                    {
                        process.output(output);
                        return process;
                    }
                };
            }

            @Override
            public List<OwnedProcessRecovery> recoverOwnedProcesses()
            {
                return ImmutableList.of();
            }

            @Override
            public void acknowledge(String launchId)
            {
                acknowledged.add(launchId);
            }
        };
        ProcessSpec spec = new ProcessSpec(
                new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "task-1"),
                ImmutableList.of("/fake/codex", "app-server"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.of(1024),
                1024,
                Duration.ofMinutes(1),
                Duration.ZERO);
        CodexAppServerClient client = new CodexAppServerClient(supervisor, spec, objectMapper, SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty());
        client.start();
        assertThat(acknowledged).isEmpty();
        if (exitBeforeStop) {
            process.cancel();
            assertThat(client.processTreeVerifiedStopped()).contains(false);
        }

        client.close();

        assertThat(process.stopAttempts()).isOne();
        assertThat(client.processTreeVerifiedStopped()).contains(true);
        assertThat(acknowledged).containsExactly("retrying");
    }

    private static final class RetryingProcess
            implements ManagedProcess
    {
        private final ObjectMapper objectMapper;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final AtomicInteger stopAttempts = new AtomicInteger();
        private ProcessOutputSink output;

        private RetryingProcess(ObjectMapper objectMapper)
        {
            this.objectMapper = objectMapper;
        }

        private void output(ProcessOutputSink output)
        {
            this.output = output;
        }

        private int stopAttempts()
        {
            return stopAttempts.get();
        }

        @Override
        public String launchId()
        {
            return "retrying";
        }

        @Override
        public long pid()
        {
            return 42;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            try {
                JsonNode message = objectMapper.readTree(StandardCharsets.UTF_8.decode(bytes.duplicate()).toString());
                if (message.path("method").asText().equals("initialize")) {
                    long id = message.path("id").asLong();
                    output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode("{\"id\":" + id + ",\"result\":{\"codexHome\":\"/codex-home\"}}\n"));
                }
                return CompletableFuture.completedFuture(null);
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            exit.complete(new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.UNVERIFIABLE, Duration.ZERO, false, false));
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            stopAttempts.incrementAndGet();
            return CompletableFuture.completedFuture(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }

        @Override
        public void close()
        {
            cancel();
        }
    }
}
