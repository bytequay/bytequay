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
package com.bytequay.app.review;

import com.bytequay.agent.cli.process.LaunchIntentRecorder;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.agent.runtime.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIdeRuntimeService
{
    @TempDir
    Path directory;
    private final ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
    private final ManagedProcess process = mock(ManagedProcess.class);
    private final PreparedProcess prepared = mock(PreparedProcess.class);
    private final Clock clock = mock(Clock.class);
    private final LoginShellEnvironment login = mock(LoginShellEnvironment.class);
    private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
    private IdeRuntimeService runtime;
    private TaskWorktree worktree;

    @BeforeEach
    void setUp()
            throws IOException
    {
        Path bundle = Files.createDirectories(directory.resolve("bundle"));
        Files.createDirectories(bundle.resolve("server"));
        Files.createDirectories(bundle.resolve("extensions/redhat.java-1.56.0"));
        String commit = "a".repeat(40);
        Files.writeString(bundle.resolve("runtimeManifest.json"), "{\"serverCommit\":\"" + commit + "\",\"extensions\":[{\"id\":\"redhat.java\",\"version\":\"1.56.0\"}]}");
        Files.writeString(bundle.resolve("server/product.json"), "{\"commit\":\"" + commit + "\"}");
        TaskId taskId = TaskId.random();
        Path root = Files.createDirectory(directory.resolve(taskId.toString())).toRealPath();
        worktree = new TaskWorktree(taskId, "workspace", RepositoryIdentity.valueOf("acme/project"), root, "bytequay/task/" + taskId, commit);
        AgentProperties properties = new AgentProperties(directory.resolve("runtime"), Duration.ofHours(1), "test-session", "codex", "0.159.3",
                directory.resolve("codex"), "claude", directory.resolve("claude"));
        when(login.get()).thenReturn(ImmutableMap.of("PATH", "/usr/bin"));
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(supervisor.prepare(any())).thenReturn(prepared);
        when(prepared.launchId()).thenReturn("launch");
        when(prepared.start(any())).thenAnswer(call -> {
            ProcessOutputSink sink = call.getArgument(0);
            sink.onOutput(ProcessStream.STDOUT, UTF_8.encode("Extension host agent listening on 54"));
            sink.onOutput(ProcessStream.STDOUT, UTF_8.encode("321\n"));
            return process;
        });
        when(process.launchId()).thenReturn("launch");
        when(process.exit()).thenReturn(exit);
        when(process.stopAndVerify()).thenReturn(CompletableFuture.completedFuture(ProcessExit.TreeTermination.VERIFIED_STOPPED));
        runtime = new IdeRuntimeService(supervisor, mock(LaunchIntentRecorder.class), login, properties, bundle.toString(), new ObjectMapper(), clock);
    }

    @Test
    void findsReadyRuntimeWithoutStartingOrRestartingProcesses()
    {
        assertThat(runtime.existing(worktree)).isEmpty();
        verify(supervisor, never()).prepare(any());
        IdeConnection connection = runtime.open(worktree);
        assertThat(runtime.existing(worktree)).containsSame(connection);
        exit.completeExceptionally(new IllegalStateException("server exited"));
        assertThat(runtime.existing(worktree)).isEmpty();
        verify(supervisor).prepare(any());
        verify(process, never()).stopAndVerify();
    }

    @Test
    void reusesOneRuntimeWithPrivateCredentialsAndSeparateWritableProfile()
            throws IOException
    {
        IdeConnection connection = runtime.open(worktree);
        assertThat(runtime.open(worktree)).isSameAs(connection);
        assertThat(connection.authority()).isEqualTo("127.0.0.1:54321");
        assertThat(connection.connectionToken()).hasSize(64);
        ArgumentCaptor<ProcessSpec> spec = ArgumentCaptor.forClass(ProcessSpec.class);
        verify(supervisor).prepare(spec.capture());
        assertThat(spec.getValue().owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.IDE, worktree.taskId().toString()));
        assertThat(spec.getValue().workingDirectory()).isEqualTo(worktree.canonicalWorktree());
        assertThat(spec.getValue().argv()).containsSubsequence("--port", "0").doesNotContain(connection.connectionToken());
        Path token = Path.of(connection.cacheRoot(), "connection-token");
        assertThat(Files.readString(token)).isEqualTo(connection.connectionToken());
        assertThat(Files.getPosixFilePermissions(token)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Path.of(connection.cacheRoot(), "extensions/redhat.java-1.56.0").toRealPath())
                .isEqualTo(Path.of(connection.bundleRoot(), "extensions/redhat.java-1.56.0"));
        runtime.stop(connection.taskId(), "old-renderer");
        verify(process, never()).stopAndVerify();
        runtime.stopWorktree(worktree);
        verify(supervisor).acknowledge("launch");
    }

    @Test
    void relinksExtensionsFromAnotherBundle()
            throws IOException
    {
        Path extensions = Files.createDirectories(directory.resolve("runtime/ide").resolve(worktree.taskId().toString()).resolve("a".repeat(40)).resolve("extensions"));
        Path link = extensions.resolve("redhat.java-1.56.0");
        Files.createSymbolicLink(link, directory.resolve("moved-bundle/extensions/redhat.java-1.56.0"));
        IdeConnection connection = runtime.open(worktree);
        assertThat(link.toRealPath()).isEqualTo(Path.of(connection.bundleRoot(), "extensions/redhat.java-1.56.0"));
        runtime.stopWorktree(worktree);

        Files.delete(link);
        Files.createDirectory(link);
        assertThatThrownBy(() -> runtime.open(worktree)).hasMessageContaining("extension profile changed");
    }

    @Test
    void restartsOnThePreviousPortWhileItIsFreeSoRestoredEditorsStayInTheTask()
            throws IOException
    {
        IdeConnection first = runtime.open(worktree);
        Path port = Path.of(first.cacheRoot(), "port");
        assertThat(port).hasContent("54321");
        runtime.stopWorktree(worktree);

        int free;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            free = socket.getLocalPort();
        }
        Files.writeString(port, Integer.toString(free));
        runtime.open(worktree);
        runtime.stopWorktree(worktree);
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            Files.writeString(port, Integer.toString(taken.getLocalPort()));
            runtime.open(worktree);
        }
        ArgumentCaptor<ProcessSpec> spec = ArgumentCaptor.forClass(ProcessSpec.class);
        verify(supervisor, times(3)).prepare(spec.capture());
        assertThat(spec.getAllValues().get(0).argv()).containsSubsequence("--port", "0");
        assertThat(spec.getAllValues().get(1).argv()).containsSubsequence("--port", Integer.toString(free));
        assertThat(spec.getAllValues().get(2).argv()).containsSubsequence("--port", "0");
        runtime.stopWorktree(worktree);
    }

    @Test
    void runsTheJavaServerOnTheNewestJdkAndRebuildsStateFromAnotherRuntime()
            throws IOException
    {
        Path profile = directory.resolve("runtime/ide").resolve(worktree.taskId().toString()).resolve("a".repeat(40));
        Path settings = profile.resolve("data/data/Machine/settings.json");
        Path storage = profile.resolve("data/data/User/workspaceStorage").resolve(worktree.taskId().toString());
        Path javaState = storage.resolve("redhat.java/jdt_ws/index");
        Path otherState = Files.createDirectories(storage.resolve("vadimcn.vscode-lldb")).resolve("state");
        Files.writeString(otherState, "kept");

        // The old implicit extension runtime has no marker; switching to the shared runtime rebuilds only Java state.
        Files.createDirectories(javaState.getParent());
        Files.writeString(javaState, "bundled");
        runtime.open(worktree);
        assertThat(new ObjectMapper().readTree(settings.toFile()).required("java.jdt.ls.java.home").asText()).isEqualTo(System.getProperty("java.home"));
        // The extension's 2 GB default runs out building a large project.
        assertThat(new ObjectMapper().readTree(settings.toFile()).required("java.jdt.ls.vmargs").asText()).contains("-Xmx6g").doesNotContain("MaxRAMPercentage");
        assertThat(storage.resolve("redhat.java")).doesNotExist();
        assertThat(otherState).hasContent("kept");
        runtime.stopWorktree(worktree);

        Files.createDirectories(javaState.getParent());
        Files.writeString(javaState, "shared");
        Path jdk = directory.resolve("jdk-26");
        when(login.javaHomeAtLeast(21)).thenReturn(Optional.of(jdk));
        runtime.open(worktree);
        assertThat(new ObjectMapper().readTree(settings.toFile()).required("java.jdt.ls.java.home").asText()).isEqualTo(jdk.toString());
        assertThat(storage.resolve("redhat.java")).doesNotExist();
        assertThat(otherState).hasContent("kept");
        runtime.stopWorktree(worktree);

        // The same JDK again keeps what it imported.
        Files.createDirectories(javaState.getParent());
        Files.writeString(javaState, "current");
        runtime.open(worktree);
        assertThat(javaState).hasContent("current");
    }

    @Test
    void retainsUnverifiedCleanupForRetry()
    {
        IdeConnection connection = runtime.open(worktree);
        when(process.stopAndVerify()).thenReturn(CompletableFuture.completedFuture(ProcessExit.TreeTermination.UNVERIFIABLE));
        assertThatThrownBy(() -> runtime.stopWorktree(worktree)).hasMessageContaining("could not be verified stopped");
        verify(supervisor, never()).acknowledge(any());
        assertThat(runtime.open(worktree)).isSameAs(connection);
        verify(supervisor).prepare(any());
        when(process.stopAndVerify()).thenReturn(CompletableFuture.completedFuture(ProcessExit.TreeTermination.VERIFIED_STOPPED));
        runtime.stopWorktree(worktree);
        verify(process, times(2)).stopAndVerify();
        verify(supervisor).acknowledge("launch");
    }

    @Test
    void heartbeatRetainsOnlyRecentlyUsedRuntime()
    {
        IdeConnection connection = runtime.open(worktree);
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(80));
        runtime.touch(connection.taskId(), connection.sessionId());
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(100));
        runtime.stopAbandoned();
        verify(process, never()).stopAndVerify();
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(171));
        runtime.stopAbandoned();
        verify(process).stopAndVerify();
        assertThatThrownBy(() -> runtime.touch(connection.taskId(), connection.sessionId())).hasMessageContaining("session has ended");
    }

    @Test
    void failedStartupKeepsUnverifiedProcessOwned()
    {
        doReturn(process).when(prepared).start(any());
        exit.completeExceptionally(new IllegalStateException("fixture startup failure"));
        when(process.stopAndVerify()).thenReturn(CompletableFuture.completedFuture(ProcessExit.TreeTermination.UNVERIFIABLE));
        assertThatThrownBy(() -> runtime.open(worktree)).hasMessageContaining("did not become ready");
        assertThatThrownBy(() -> runtime.stopWorktree(worktree)).hasMessageContaining("could not be verified stopped");
        verify(supervisor, never()).acknowledge(any());
        when(process.stopAndVerify()).thenReturn(CompletableFuture.completedFuture(ProcessExit.TreeTermination.VERIFIED_STOPPED));
        runtime.stopWorktree(worktree);
        verify(supervisor).acknowledge("launch");
    }
}
