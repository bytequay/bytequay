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
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessIdentity;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.agent.AgentProperties;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.review.ReviewConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Owns one lazily started extension host per task, independently of the agent's mutation claim. */
@Component
public class IdeRuntimeService
{
    private static final Logger log = LoggerFactory.getLogger(IdeRuntimeService.class);
    private static final Pattern LISTENING = Pattern.compile("Extension host agent listening on (\\d+)\\r?\\n");
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(90);
    // The oldest release the bundled Java extension accepts for its language server.
    private static final int MINIMUM_JAVA_SERVER_RELEASE = 21;
    private final ProcessSupervisor supervisor;
    private final LaunchIntentRecorder intents;
    private final LoginShellEnvironment loginShell;
    private final Path profiles;
    private final String bundleHome;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public IdeRuntimeService(ProcessSupervisor supervisor, LaunchIntentRecorder intents, LoginShellEnvironment loginShell, AgentProperties properties,
            @Value("${bytequay.ide.home:}") String bundleHome, ObjectMapper mapper, Clock clock)
    {
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.intents = requireNonNull(intents, "intents is null");
        this.loginShell = requireNonNull(loginShell, "loginShell is null");
        this.profiles = properties.runtimeRoot().resolve("ide");
        this.bundleHome = requireNonNull(bundleHome, "bundleHome is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Finds a ready server without starting or restarting any processes. */
    public synchronized Optional<IdeConnection> existing(TaskWorktree worktree)
    {
        Live live = sessions.get(worktree.taskId().toString());
        if (closing || live == null || live.process.isEmpty() || live.process.orElseThrow().exit().toCompletableFuture().isDone()
                || !live.connection.isDone() || live.connection.isCompletedExceptionally()) {
            return Optional.empty();
        }
        checkState(live.root.equals(worktree.canonicalWorktree()), "The IDE workspace identity changed");
        live.lastSeen = clock.instant();
        return Optional.of(live.connection.join());
    }

    public synchronized IdeConnection open(TaskWorktree worktree)
    {
        return WorkspaceOperationLocks.withLock(worktree.workspaceId(), () -> {
            checkState(!closing, "The IDE is shutting down");
            String taskId = worktree.taskId().toString();
            Live live = sessions.compute(taskId, (ignored, current) -> {
                if (current != null) {
                    checkState(current.root.equals(worktree.canonicalWorktree()), "The IDE workspace identity changed");
                    if (current.process.isPresent() && current.process.orElseThrow().exit().toCompletableFuture().isDone()) {
                        stop(current);
                        return start(worktree);
                    }
                    current.lastSeen = clock.instant();
                    return current;
                }
                return start(worktree);
            });
            try {
                return live.connection.get(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException | ExecutionException | TimeoutException failure) {
                // Keep the session registered until cleanup succeeds, including a failed gated launch.
                try {
                    stop(taskId, live.sessionId);
                }
                catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new ReviewConflictException("The IDE server did not become ready", failure);
            }
        });
    }

    public void touch(String taskId, String sessionId)
    {
        sessions.compute(taskId, (ignored, live) -> {
            checkState(live != null && live.sessionId.equals(sessionId), "The IDE session has ended");
            checkState(live.process.isPresent() && !live.process.orElseThrow().exit().toCompletableFuture().isDone(), "The IDE process stopped unexpectedly");
            live.lastSeen = clock.instant();
            return live;
        });
    }

    /** Takes the process baseline before a claimed execution is allowed to start. */
    public Execution trackExecution(String taskId, String sessionId)
    {
        touch(taskId, sessionId);
        Live live = sessions.get(taskId);
        checkState(live != null && live.sessionId.equals(sessionId), "The IDE session has ended");
        ManagedProcess process = live.process.orElseThrow();
        List<ProcessIdentity> baseline = process.observedDescendants()
                .orElseThrow(() -> new ReviewConflictException("IDE process ownership is uncertain; close the editor before running code"));
        return new Execution(process, baseline);
    }

    /** Backend-only execution evidence. A protocol completion still needs this process check before releasing its claim. */
    public record Execution(ManagedProcess process, List<ProcessIdentity> baseline)
    {
        public Execution
        {
            requireNonNull(process, "process is null");
            baseline = ImmutableList.copyOf(baseline);
        }

        public boolean quiescent()
        {
            if (process.exit().toCompletableFuture().isDone()) {
                try {
                    return process.exit().toCompletableFuture().join().treeTermination() == ProcessExit.TreeTermination.VERIFIED_STOPPED;
                }
                catch (RuntimeException failure) {
                    return false;
                }
            }
            return process.observedDescendants()
                    .map(known -> known.stream().filter(identity -> !baseline.contains(identity)).allMatch(identity -> identity.liveness() == ProcessIdentity.Liveness.GONE))
                    .orElse(false);
        }
    }

    public void stop(String taskId, String sessionId)
    {
        sessions.computeIfPresent(taskId, (ignored, live) -> {
            // A late close from an old renderer cannot stop its replacement.
            if (!live.sessionId.equals(sessionId)) {
                return live;
            }
            stop(live);
            return null;
        });
    }

    /** Called before removing a worktree, including when its editor is still visible. */
    public void stopWorktree(TaskWorktree worktree)
    {
        sessions.computeIfPresent(worktree.taskId().toString(), (ignored, live) -> {
            checkState(live.root.equals(worktree.canonicalWorktree()), "The IDE workspace identity changed");
            stop(live);
            return null;
        });
    }

    @Scheduled(fixedDelay = 30_000)
    public void stopAbandoned()
    {
        sessions.forEach((id, live) -> {
            if (live.lastSeen.plus(CLIENT_TIMEOUT).isBefore(clock.instant())) {
                try {
                    sessions.computeIfPresent(id, (ignored, current) -> {
                        if (current.lastSeen.plus(CLIENT_TIMEOUT).isBefore(clock.instant())) {
                            stop(current);
                            return null;
                        }
                        return current;
                    });
                }
                catch (RuntimeException failure) {
                    log.warn("IDE cleanup remains pending for task {}", id, failure);
                }
            }
        });
    }

    @PreDestroy
    public synchronized void close()
    {
        closing = true;
        sessions.forEach((id, live) -> {
            try {
                stop(id, live.sessionId);
            }
            catch (RuntimeException failure) {
                log.warn("IDE processes for task {} require recovery on restart", id, failure);
            }
        });
    }

    private Live start(TaskWorktree worktree)
    {
        checkState(!bundleHome.isBlank(), "The ByteQuay IDE bundle is missing; assemble it with scripts/ide-bundle.mjs");
        try {
            Path bundle = Path.of(bundleHome).toRealPath();
            JsonNode manifest = mapper.readTree(bundle.resolve("runtimeManifest.json").toFile());
            String commit = manifest.required("serverCommit").asText();
            checkState(commit.matches("[0-9a-f]{40}"), "Invalid IDE bundle identity");
            JsonNode product = mapper.readTree(bundle.resolve("server/product.json").toFile());
            checkState(commit.equals(product.required("commit").asText()), "The IDE client and server do not match");
            Path root = worktree.canonicalWorktree();
            checkState(root.equals(root.toRealPath()), "The IDE working copy moved");
            Path profile = profiles.resolve(worktree.taskId().toString()).resolve(commit);
            Files.createDirectories(profile);
            Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------"));
            Path extensions = profile.resolve("extensions");
            Files.createDirectories(extensions);
            for (JsonNode extension : manifest.required("extensions")) {
                String name = extension.required("id").asText() + "-" + extension.required("version").asText();
                checkArgument(name.matches("[A-Za-z0-9_.-]+"), "Invalid IDE extension identity");
                Path target = bundle.resolve("extensions").resolve(name).toRealPath();
                Path link = extensions.resolve(name);
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    checkState(Files.isSymbolicLink(link) && link.toRealPath().equals(target), "The IDE extension profile changed");
                }
                else {
                    Files.createSymbolicLink(link, target);
                }
            }
            configureJavaRuntime(profile, loginShell.javaHomeAtLeast(MINIMUM_JAVA_SERVER_RELEASE));
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            String token = HexFormat.of().formatHex(secret);
            Path tokenFile = profile.resolve("connection-token");
            Files.deleteIfExists(tokenFile);
            Files.createFile(tokenFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tokenFile, token);
            var argv = ImmutableList.of(bundle.resolve("server/node").toString(), bundle.resolve("server/out/server-main.js").toString(),
                    "--host", "127.0.0.1", "--port", "0", "--connection-token-file", tokenFile.toString(),
                    "--server-data-dir", profile.resolve("data").toString(), "--extensions-dir", extensions.toString(),
                    "--telemetry-level", "off", "--log", "warn", "--reconnection-grace-time", "0");
            Map<String, String> environment = ImmutableMap.<String, String>builder().putAll(loginShell.get())
                    .put("GOTELEMETRY", "off").buildKeepingLast();
            var prepared = supervisor.prepare(new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.IDE, worktree.taskId().toString()),
                    argv, root, environment, OptionalInt.empty(), 64 * 1024, Duration.ofDays(7), Duration.ofSeconds(5)));
            CompletableFuture<Integer> port = new CompletableFuture<>();
            StringBuilder output = new StringBuilder();
            String sessionId = UUID.randomUUID().toString();
            CompletableFuture<IdeConnection> connection = port.thenApply(listening -> new IdeConnection(worktree.taskId().toString(), sessionId,
                    "127.0.0.1:" + listening, token, root.toString(), bundle.toString(), profile.toString(), commit));
            Live live = new Live(sessionId, root, prepared.launchId(), connection, clock.instant());
            try {
                ManagedProcess process = prepared.start((stream, bytes) -> {
                    synchronized (output) {
                        if (port.isDone()) {
                            return;
                        }
                        output.append(UTF_8.decode(bytes));
                        Matcher match = LISTENING.matcher(output);
                        if (match.find()) {
                            int number = Integer.parseInt(match.group(1));
                            if (number > 0 && number <= 65535) {
                                port.complete(number);
                            }
                        }
                        if (output.length() > 32_768) {
                            output.delete(0, output.length() - 16_384);
                        }
                    }
                });
                live.process = Optional.of(process);
                process.exit().whenComplete((exit, failure) -> port.completeExceptionally(new ReviewConflictException("The IDE server stopped during startup")));
            }
            catch (RuntimeException failure) {
                port.completeExceptionally(failure);
            }
            return live;
        }
        catch (IOException failure) {
            throw new ReviewConflictException("Cannot prepare the IDE runtime: " + failure.getMessage(), failure);
        }
    }

    /**
     * The Java extension otherwise runs its language server on a bundled JRE 21. Maven import happens inside that
     * process, so a build plugin compiled for a newer release fails to load there and its modules lose Java support.
     */
    private void configureJavaRuntime(Path profile, Optional<Path> javaHome)
            throws IOException
    {
        Path userData = profile.resolve("data").resolve("data");
        Path marker = profile.resolve("java-home");
        String selected = javaHome.map(Path::toString).orElse("");
        String previous = Files.exists(marker) ? Files.readString(marker) : "";
        if (!previous.equals(selected)) {
            // Project state imported under another runtime keeps its missing modules, so it is rebuilt.
            Path storage = userData.resolve("User").resolve("workspaceStorage");
            if (Files.isDirectory(storage)) {
                try (Stream<Path> workspaces = Files.list(storage)) {
                    for (Path workspace : workspaces.collect(toImmutableList())) {
                        Path javaState = workspace.resolve("redhat.java");
                        if (Files.exists(javaState, LinkOption.NOFOLLOW_LINKS)) {
                            // macOS has no secure directory stream, so the insecure walk is the only option here.
                            MoreFiles.deleteRecursively(javaState, RecursiveDeleteOption.ALLOW_INSECURE);
                        }
                    }
                }
            }
            Files.writeString(marker, selected);
        }
        // Machine-scoped settings are read from the server's own profile; the renderer's user settings cannot set them.
        Path settings = Files.createDirectories(userData.resolve("Machine")).resolve("settings.json");
        mapper.writeValue(settings.toFile(), javaHome.map(home -> ImmutableMap.of("java.jdt.ls.java.home", home.toString())).orElse(ImmutableMap.of()));
    }

    private void stop(Live live)
    {
        if (live.process.isPresent()) {
            stopProcess(live.process.orElseThrow());
            return;
        }
        OwnedProcessRecovery recovery = supervisor.recoverOwnedProcess(live.launchId);
        boolean unlaunched = recovery.owner().isEmpty() && recovery.phase().isEmpty() && !intents.committedLaunches().containsKey(live.launchId);
        checkState(unlaunched || recovery.outcome() != OwnedProcessRecovery.Outcome.UNVERIFIABLE, "The IDE launch could not be verified stopped; retry closing the IDE");
        supervisor.acknowledge(live.launchId);
    }

    private void stopProcess(ManagedProcess process)
    {
        try {
            ProcessExit.TreeTermination result = process.stopAndVerify().toCompletableFuture().get(30, TimeUnit.SECONDS);
            checkState(result == ProcessExit.TreeTermination.VERIFIED_STOPPED, "The IDE processes could not be verified stopped; retry closing the IDE");
            supervisor.acknowledge(process.launchId());
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ReviewConflictException("IDE cleanup was interrupted", failure);
        }
        catch (ExecutionException | TimeoutException failure) {
            throw new ReviewConflictException("IDE cleanup did not finish; retry closing the IDE", failure);
        }
    }

    private static final class Live
    {
        private final String sessionId;
        private final Path root;
        private final String launchId;
        private final CompletableFuture<IdeConnection> connection;
        private Optional<ManagedProcess> process = Optional.empty();
        private volatile Instant lastSeen;

        private Live(String sessionId, Path root, String launchId, CompletableFuture<IdeConnection> connection, Instant lastSeen)
        {
            this.sessionId = sessionId;
            this.root = root;
            this.launchId = launchId;
            this.connection = connection;
            this.lastSeen = lastSeen;
        }
    }
}
