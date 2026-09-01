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
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.cli.process.Utf8LineBuffer;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Owns one official {@code codex exec-server} process and registered environment per task.
 *
 * <p>App Server runs the model loop for every task in one shared process. Without a task-owned
 * environment its built-in tools execute in the default environment, so two tasks would share one
 * filesystem boundary. Each task therefore gets its own executor process, its own environment
 * identifier, and its own workspace roots.
 *
 * <p>The v0.151.0 reference protocol has no unregister request, so correctness must not depend on
 * one: closing a task stops its verified executor and leaves the unique identifier as a
 * disconnected registry entry until the shared App Server exits. Identifiers are derived from the
 * owning task and are therefore never reassigned to another task.
 */
final class TaskExecServerManager
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(TaskExecServerManager.class);

    private static final String ENVIRONMENT_PREFIX = "bytequay-task-";
    private static final String LOOPBACK_LISTEN = "ws://127.0.0.1:0";
    private static final Pattern LISTEN_URL = Pattern.compile("^ws://127\\.0\\.0\\.1:\\d{1,5}$");
    private static final Duration LISTEN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXECUTOR_RUNTIME = Duration.ofHours(12);
    private static final Duration CANCELLATION_GRACE = Duration.ofSeconds(5);
    private static final int MAX_LISTEN_LINE_BYTES = 4 * 1024;
    private static final int MAX_DIAGNOSTIC_BYTES = 64 * 1024;

    private final ProcessSupervisor supervisor;
    private final CodexAppServerClient client;
    private final String executable;
    private final Map<String, String> environment;
    private final SecretRedactor redactor;
    private final Map<String, ManagedProcess> executors = new ConcurrentHashMap<>();

    TaskExecServerManager(
            ProcessSupervisor supervisor,
            CodexAppServerClient client,
            String executable,
            Map<String, String> environment,
            SecretRedactor redactor)
    {
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.client = requireNonNull(client, "client is null");
        this.executable = requireNonNull(executable, "executable is null");
        this.environment = requireNonNull(environment, "environment is null");
        this.redactor = requireNonNull(redactor, "redactor is null");
    }

    /**
     * Launches, registers, and verifies the environment owned by one task.
     *
     * <p>A resumed task re-launches its executor and re-registers the same identifier, because the
     * previous listener died with the earlier backend. Verification happens before the caller may
     * use the environment, so a task never silently falls back to the default one.
     *
     * @param taskId opaque owning task identifier
     * @param worktree canonical task worktree, used as the executor working directory
     * @return the verified environment selection to send with thread and turn requests
     */
    CodexProtocol.TurnEnvironmentParams open(String taskId, Path worktree)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(worktree, "worktree is null");
        String environmentId = ENVIRONMENT_PREFIX + taskId;
        close(taskId);

        Path canonical = canonical(worktree);
        StartedExecutor started = launch(environmentId, canonical);
        executors.put(taskId, started.process());
        try {
            client.request(
                    "environment/add",
                    new CodexProtocol.EnvironmentAddParams(
                            environmentId,
                            started.awaitListenUrl(),
                            CONNECT_TIMEOUT.toMillis()),
                    CodexProtocol.EmptyResponse.class,
                    REQUEST_TIMEOUT);
            verify(environmentId, canonical);
        }
        catch (RuntimeException failure) {
            close(taskId);
            throw failure instanceof AgentUnavailableException unavailable
                    ? unavailable
                    : new AgentUnavailableException("Unable to register the Codex task environment", failure);
        }
        return new CodexProtocol.TurnEnvironmentParams(
                environmentId,
                canonical.toString(),
                ImmutableList.of(canonical.toString()));
    }

    /** Stops the executor owned by one task; the registry entry stays disconnected by design. */
    void close(String taskId)
    {
        ManagedProcess executor = executors.remove(taskId);
        if (executor != null) {
            executor.close();
        }
    }

    @Override
    public void close()
    {
        ImmutableList.copyOf(executors.keySet()).forEach(this::close);
    }

    /**
     * Forces the separate exec-server handshake and checks what it reports back.
     *
     * <p>{@code environment/add} only upserts the registry entry, so a bad executor would stay
     * undetected until a model tool call. The reported working directory is a {@code file://} URI
     * and is canonicalized by the executor, so it is compared as a real path rather than as text.
     */
    private void verify(String environmentId, Path canonical)
    {
        CodexProtocol.EnvironmentInfoResponse info = client.request(
                "environment/info",
                new CodexProtocol.EnvironmentInfoParams(environmentId),
                CodexProtocol.EnvironmentInfoResponse.class,
                REQUEST_TIMEOUT);
        Path reported = info.cwd()
                .map(TaskExecServerManager::toPath)
                .orElseThrow(() -> new AgentUnavailableException(
                        "Codex task environment reported no working directory: " + environmentId));
        if (!canonical(reported).equals(canonical)) {
            throw new AgentUnavailableException(
                    "Codex task environment resolved a different working directory: " + environmentId);
        }
        log.debug("Codex task environment {} verified with shell {}", environmentId, info.shell().name());
    }

    private StartedExecutor launch(String environmentId, Path canonical)
    {
        CompletableFuture<String> listenUrl = new CompletableFuture<>();
        Utf8LineBuffer stdout = new Utf8LineBuffer(MAX_LISTEN_LINE_BYTES, false, line -> {
            if (LISTEN_URL.matcher(line.trim()).matches()) {
                listenUrl.complete(line.trim());
            }
        });
        Utf8LineBuffer stderr = new Utf8LineBuffer(MAX_DIAGNOSTIC_BYTES, false,
                line -> log.warn("Codex exec-server {}: {}", environmentId, redactor.redact(line)));
        ProcessSpec spec = new ProcessSpec(
                new ProcessOwner(ProcessOwner.Kind.TASK_EXEC_SERVER, environmentId),
                ImmutableList.of(executable, "exec-server", "--listen", LOOPBACK_LISTEN),
                canonical,
                environment,
                MAX_LISTEN_LINE_BYTES,
                MAX_DIAGNOSTIC_BYTES,
                EXECUTOR_RUNTIME,
                CANCELLATION_GRACE);
        ManagedProcess executor = supervisor.prepare(spec).start((stream, bytes) -> {
            if (stream == ProcessStream.STDOUT) {
                stdout.accept(bytes);
            }
            else {
                stderr.accept(bytes);
            }
        });
        executor.exit().whenComplete((exit, failure) -> listenUrl.completeExceptionally(
                new AgentUnavailableException("Codex exec-server exited before it reported a listener")));
        return new StartedExecutor(executor, listenUrl);
    }

    /** One launched executor and the loopback listener it is expected to announce on stdout. */
    private record StartedExecutor(ManagedProcess process, CompletableFuture<String> listenUrl)
    {
        private String awaitListenUrl()
        {
            try {
                return listenUrl.get(LISTEN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentUnavailableException("Interrupted while waiting for the Codex exec-server listener", e);
            }
            catch (ExecutionException | TimeoutException e) {
                process.close();
                throw new AgentUnavailableException("Codex exec-server did not report a loopback listener", e);
            }
        }
    }

    private static Path toPath(String cwd)
    {
        try {
            return cwd.startsWith("file:") ? Path.of(URI.create(cwd)) : Path.of(cwd);
        }
        catch (RuntimeException e) {
            throw new AgentUnavailableException("Codex task environment reported an unusable working directory", e);
        }
    }

    private static Path canonical(Path path)
    {
        try {
            return path.toRealPath();
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to resolve the Codex task worktree", e);
        }
    }
}
