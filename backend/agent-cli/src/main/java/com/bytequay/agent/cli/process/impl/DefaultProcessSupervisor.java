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
package com.bytequay.agent.cli.process.impl;

import com.bytequay.agent.cli.process.LaunchIntentRecorder;
import com.bytequay.agent.cli.process.LaunchPhase;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessIdentity;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Default JDK process implementation used by the Codex and Claude CLI lanes.
 *
 * <p>Every payload is spawned behind a gated launcher so a crash between the durable launch intent
 * and the operating-system process can never leave an unrecorded writer running. The launcher makes
 * the payload its own process-group leader, which is the containment primitive used to prove that a
 * whole tree stopped after the root exited or after a backend restart.
 */
public final class DefaultProcessSupervisor
        implements ProcessSupervisor
{
    private static final Logger log = LoggerFactory.getLogger(DefaultProcessSupervisor.class);

    /** Gated launcher script; see {@code launcher.sh} for what it does and why. */
    private static final String LAUNCHER_SCRIPT = readLauncherScript();
    /**
     * Bash, not {@code /bin/sh}: the launcher needs {@code set -m} to put the payload in its own
     * process group, and dash, which is {@code /bin/sh} on Debian and Ubuntu, turns job control
     * off when it has no controlling terminal. Bash creates the group without one.
     */
    private static final String LAUNCHER_SHELL = "/bin/bash";
    /** Exit status the launcher script uses when the gate closed and the payload never ran. */
    private static final int GATE_CLOSED_EXIT_CODE = 70;
    private static final Duration IDENTITY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LAUNCHER_EXIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TREE_VERIFICATION_TIMEOUT = Duration.ofSeconds(2);

    private final LaunchMarkerStore store;
    private final LaunchIntentRecorder intents;
    private final String backendId = UUID.randomUUID().toString();

    public DefaultProcessSupervisor(Path launchDirectory, LaunchIntentRecorder intents)
    {
        this.store = new LaunchMarkerStore(launchDirectory);
        this.intents = requireNonNull(intents, "intents is null");
    }

    @Override
    public PreparedProcess prepare(ProcessSpec spec)
    {
        requireNonNull(spec, "spec is null");
        String executable = resolveExecutable(spec);
        LaunchMarker marker = LaunchMarker.prepared(
                UUID.randomUUID().toString(),
                backendId,
                spec.owner(),
                executable,
                argvDigest(spec.argv()));
        store.write(marker);
        intents.prepared(marker.launchId(), spec.owner(), executable, marker.argvDigest());
        return new GatedLaunch(spec, executable, marker);
    }

    @Override
    public List<OwnedProcessRecovery> recoverOwnedProcesses()
    {
        Map<String, Boolean> committed = intents.committedLaunches();
        Set<String> launches = ImmutableSet.<String>builder()
                .addAll(store.list())
                .addAll(committed.keySet())
                .build();
        return launches.stream()
                .sorted()
                .map(launchId -> recover(launchId, Optional.ofNullable(committed.get(launchId))))
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .collect(toImmutableList());
    }

    @Override
    public void acknowledge(String launchId)
    {
        requireNonNull(launchId, "launchId is null");
        intents.forget(launchId);
        store.delete(launchId);
    }

    private Optional<OwnedProcessRecovery> recover(String launchId, Optional<Boolean> attempted)
    {
        Optional<LaunchMarker> found = store.read(launchId);
        if (found.isEmpty()) {
            // A committed launch that never reached the spawn is provably unlaunched. Any attempted
            // launch without its marker is an unexplained loss and must block rather than be
            // assumed harmless.
            boolean unlaunched = attempted.filter(value -> !value).isPresent();
            return Optional.of(new OwnedProcessRecovery(
                    launchId,
                    Optional.empty(),
                    Optional.empty(),
                    unlaunched
                            ? OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED
                            : OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                    unlaunched
                            ? "Committed launch was never attempted and has no marker"
                            : "Launch marker is missing or undecodable"));
        }
        LaunchMarker marker = found.orElseThrow();
        if (marker.backendId().equals(backendId)) {
            return Optional.empty();
        }
        return Optional.of(reconcile(marker, attempted));
    }

    /**
     * Rejects a marker whose recorded process facts cannot have followed its committed intent.
     *
     * <p>The owner always commits {@code ATTEMPTING} before a launcher can exist, so a marker that
     * observed a launcher under a merely prepared or absent intent is an impossible ordering.
     */
    private static boolean impossibleOrdering(LaunchMarker marker, Optional<Boolean> attempted)
    {
        if (marker.phase() == LaunchPhase.PREPARED) {
            return false;
        }
        return !attempted.orElse(false);
    }

    private OwnedProcessRecovery reconcile(LaunchMarker marker, Optional<Boolean> attempted)
    {
        String launchId = marker.launchId();
        boolean released = store.gateReleased(launchId);
        LaunchPhase phase = marker.phase();
        if (phase != LaunchPhase.TERMINAL && impossibleOrdering(marker, attempted)) {
            return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                    "Recorded process facts do not follow the committed launch intent");
        }
        if (phase == LaunchPhase.TERMINAL) {
            return result(marker, marker.treeTermination()
                            .map(termination -> termination == ProcessExit.TreeTermination.VERIFIED_STOPPED
                                    ? OwnedProcessRecovery.Outcome.VERIFIED_STOPPED
                                    : OwnedProcessRecovery.Outcome.UNVERIFIABLE)
                            .orElse(OwnedProcessRecovery.Outcome.UNVERIFIABLE),
                    "Replayed a durable terminal receipt");
        }
        if (phase == LaunchPhase.PREPARED && !released) {
            // The gate file is the only way a launcher can reach the payload, and it was never
            // written. Cancelling the gate also stops a launcher that spawned before the crash but
            // never recorded its identity.
            store.writeGate(launchId, LaunchMarkerStore.CANCEL);
            return result(marker, OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED, "Gate was never released");
        }
        if (phase == LaunchPhase.GATED && !released) {
            return closeGate(marker);
        }
        return stopRecordedTree(marker);
    }

    private OwnedProcessRecovery closeGate(LaunchMarker marker)
    {
        Optional<ProcessIdentity> identity = marker.identity();
        if (identity.isEmpty()) {
            return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Gated marker has no usable identity");
        }
        store.writeGate(marker.launchId(), LaunchMarkerStore.CANCEL);
        ProcessIdentity gated = identity.orElseThrow();
        switch (gated.liveness()) {
            case GONE -> {
                return result(marker, OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED, "Gated launcher already exited");
            }
            case UNKNOWN -> {
                return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                        "Gated launcher exists but its start token cannot be read");
            }
            case MATCHES -> ProcessHandle.of(gated.pid()).ifPresent(ProcessHandle::destroy);
        }
        if (awaitDead(gated, LAUNCHER_EXIT_TIMEOUT)) {
            return result(marker, OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED, "Gated launcher was stopped");
        }
        return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Gated launcher did not stop");
    }

    private OwnedProcessRecovery stopRecordedTree(LaunchMarker marker)
    {
        Optional<ProcessIdentity> identity = marker.identity();
        Optional<Long> group = marker.processGroup();
        if (identity.isEmpty() || group.isEmpty()) {
            return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Released marker has no process receipt");
        }
        ProcessIdentity leader = identity.orElseThrow();
        Optional<ProcessHandle> live = ProcessHandle.of(leader.pid());
        if (live.isPresent()) {
            switch (leader.liveness()) {
                case UNKNOWN -> {
                    return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                            "Recorded PID exists but its start token cannot be read");
                }
                case GONE -> {
                    // The recorded group leader exited and its identifier was reused, which a POSIX
                    // system only allows once the whole group is empty.
                    return result(marker, OwnedProcessRecovery.Outcome.VERIFIED_STOPPED, "Recorded process tree already exited");
                }
                case MATCHES -> {
                    if (!matchesRecordedExecutable(marker, live.orElseThrow().info())) {
                        return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Recorded PID runs an unrecognized executable");
                    }
                }
            }
        }
        return result(marker, terminateGroup(group.orElseThrow(), Duration.ofSeconds(1))
                        == ProcessExit.TreeTermination.VERIFIED_STOPPED
                        ? OwnedProcessRecovery.Outcome.VERIFIED_STOPPED
                        : OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                "Reconciled the recorded process group");
    }

    private static boolean matchesRecordedExecutable(LaunchMarker marker, ProcessHandle.Info info)
    {
        if (info.command().filter(marker.executable()::equals).isPresent()) {
            return true;
        }
        // An interpreted provider CLI reports its interpreter as the command, so the resolved
        // executable path is only visible in the full command line.
        if (info.commandLine().filter(line -> line.contains(marker.executable())).isPresent()) {
            return true;
        }
        // Until the gate is released the recorded PID is still the gated launcher shell.
        return info.command()
                .filter(observed -> marker.launcherCommand().filter(observed::equals).isPresent())
                .isPresent();
    }

    private static OwnedProcessRecovery result(LaunchMarker marker, OwnedProcessRecovery.Outcome outcome, String detail)
    {
        return new OwnedProcessRecovery(
                marker.launchId(),
                Optional.of(marker.owner()),
                Optional.of(marker.phase()),
                outcome,
                detail);
    }

    /**
     * Terminates every live member of one process group and reports whether the group emptied.
     *
     * <p>A group identifier is never reused while the group still has a member, so an empty scan is
     * a proof rather than an inference. A scan that cannot be read is unverifiable.
     */
    private static ProcessExit.TreeTermination terminateGroup(long processGroup, Duration grace)
    {
        Optional<List<Long>> initial = ProcessGroups.members(processGroup);
        if (initial.isEmpty()) {
            return ProcessExit.TreeTermination.UNVERIFIABLE;
        }
        initial.orElseThrow().forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy));
        if (!awaitGroupEmpty(processGroup, grace)) {
            // A member spawned during the grace period is still owned work, so the forced pass
            // uses current membership rather than the first snapshot.
            ProcessGroups.members(processGroup)
                    .ifPresent(current -> current.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly)));
        }
        return awaitGroupEmpty(processGroup, TREE_VERIFICATION_TIMEOUT)
                ? ProcessExit.TreeTermination.VERIFIED_STOPPED
                : ProcessExit.TreeTermination.UNVERIFIABLE;
    }

    private static boolean awaitGroupEmpty(long processGroup, Duration timeout)
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<List<Long>> members = ProcessGroups.members(processGroup);
            if (members.isEmpty()) {
                return false;
            }
            if (members.orElseThrow().isEmpty()) {
                return true;
            }
            if (System.nanoTime() >= deadline || !sleep(50)) {
                return false;
            }
        }
    }

    /** Waits for a recorded identity to be observably gone; an uninspectable process is never dead. */
    private static boolean awaitDead(ProcessIdentity identity, Duration timeout)
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (identity.liveness() != ProcessIdentity.Liveness.GONE) {
            if (System.nanoTime() >= deadline || !sleep(20)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sleep(long millis)
    {
        try {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String readLauncherScript()
    {
        try (InputStream script = DefaultProcessSupervisor.class.getResourceAsStream("launcher.sh")) {
            requireNonNull(script, "launcher.sh is missing from the agent-cli resources");
            return new String(script.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to read the process launcher script", e);
        }
    }

    private static String resolveExecutable(ProcessSpec spec)
    {
        String command = spec.argv().getFirst();
        Optional<Path> resolved = command.contains("/")
                ? Optional.of(Path.of(command))
                : searchPath(command, spec.environment().get("PATH"));
        return resolved
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isExecutable)
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .orElseThrow(() -> new AgentUnavailableException(
                        "Unable to start process: " + command,
                        new NoSuchFileException(command)));
    }

    private static Optional<Path> searchPath(String command, String path)
    {
        if (path == null) {
            return Optional.empty();
        }
        return Arrays.stream(path.split(":"))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).resolve(command))
                .filter(Files::isExecutable)
                .findFirst();
    }

    private static String argvDigest(List<String> argv)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", argv).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new AgentUnavailableException("SHA-256 is unavailable", e);
        }
    }

    private static Optional<Long> readIdentity(Path identity)
    {
        try {
            return Optional.of(Long.parseLong(Files.readString(identity, StandardCharsets.UTF_8).trim()));
        }
        catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private final class GatedLaunch
            implements PreparedProcess
    {
        private final ProcessSpec spec;
        private final String executable;
        private final LaunchMarker prepared;
        private final AtomicBoolean used = new AtomicBoolean();

        private GatedLaunch(ProcessSpec spec, String executable, LaunchMarker prepared)
        {
            this.spec = spec;
            this.executable = executable;
            this.prepared = prepared;
        }

        @Override
        public String launchId()
        {
            return prepared.launchId();
        }

        @Override
        public ManagedProcess start(ProcessOutputSink output)
        {
            requireNonNull(output, "output is null");
            checkState(used.compareAndSet(false, true), "Prepared launch was already used");
            intents.attempting(launchId());
            Process launcher = spawnLauncher();
            LaunchMarker recorded = prepared;
            boolean released = false;
            try {
                ProcessIdentity identity = awaitIdentity(launcher);
                ProcessHandle.Info info = ProcessHandle.of(identity.pid())
                        .orElseThrow(() -> new AgentUnavailableException("Gated launcher exited before it could be recorded"))
                        .info();
                long group = ProcessGroups.groupOf(identity.pid())
                        .orElseThrow(() -> new AgentUnavailableException("Unable to read the launcher process group"));
                checkState(group == identity.pid(), "Gated launcher is not its own process-group leader");
                recorded = prepared.gated(identity, info.command().orElse(LAUNCHER_SHELL), group);
                store.write(recorded);
                recorded = recorded.withPhase(LaunchPhase.RELEASE_PENDING);
                store.write(recorded);
                released = true;
                store.writeGate(launchId(), LaunchMarkerStore.RELEASE);
                recorded = recorded.withPhase(LaunchPhase.RUNNING);
                store.write(recorded);
                return new DefaultManagedProcess(recorded, launcher, identity.pid(), group, spec, output);
            }
            catch (RuntimeException failure) {
                abort(launcher, recorded, released);
                throw failure instanceof AgentUnavailableException unavailable
                        ? unavailable
                        : new AgentUnavailableException("Unable to start process: " + executable, failure);
            }
        }

        @Override
        public void abandon()
        {
            if (used.compareAndSet(false, true)) {
                intents.forget(launchId());
                store.delete(launchId());
            }
        }

        private void abort(Process launcher, LaunchMarker recorded, boolean released)
        {
            if (released) {
                // The gate may already have executed the payload, so the launch keeps a durable
                // terminal receipt instead of being deleted as known unsent.
                ProcessExit.TreeTermination termination = recorded.processGroup()
                        .map(group -> terminateGroup(group, spec.cancellationGrace()))
                        .orElse(ProcessExit.TreeTermination.UNVERIFIABLE);
                launcher.destroyForcibly();
                store.write(recorded.terminal(
                        new ProcessExit(-1, ProcessExit.Reason.CANCELLED, termination, Duration.ZERO, false, false)));
                return;
            }
            store.writeGate(launchId(), LaunchMarkerStore.CANCEL);
            try {
                if (!launcher.waitFor(LAUNCHER_EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    launcher.destroyForcibly();
                }
                else if (launcher.exitValue() != GATE_CLOSED_EXIT_CODE) {
                    log.warn("Cancelled launcher {} exited with {} instead of the gate-closed status",
                            launchId(), launcher.exitValue());
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                launcher.destroyForcibly();
            }
            intents.forget(launchId());
            store.delete(launchId());
        }

        private Process spawnLauncher()
        {
            ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                    .add(
                            LAUNCHER_SHELL,
                            "-c",
                            LAUNCHER_SCRIPT,
                            "bytequay-launch",
                            store.gateFile(launchId()).toString(),
                            store.identityFile(launchId()).toString(),
                            Long.toString(ProcessHandle.current().pid()),
                            executable);
            spec.argv().stream().skip(1).forEach(argv::add);
            ProcessBuilder builder = new ProcessBuilder(argv.build())
                    .directory(spec.workingDirectory().toFile())
                    .redirectErrorStream(false);
            builder.environment().clear();
            builder.environment().putAll(spec.environment());
            try {
                return builder.start();
            }
            catch (IOException e) {
                store.delete(launchId());
                throw new AgentUnavailableException("Unable to start process: " + executable, e);
            }
        }

        private ProcessIdentity awaitIdentity(Process launcher)
        {
            Path identity = store.identityFile(launchId());
            long deadline = System.nanoTime() + IDENTITY_TIMEOUT.toNanos();
            while (true) {
                Optional<Long> reported = readIdentity(identity);
                if (reported.isPresent()) {
                    long pid = reported.orElseThrow();
                    Instant startToken = ProcessHandle.of(pid)
                            .flatMap(handle -> handle.info().startInstant())
                            .orElseThrow(() -> new AgentUnavailableException(
                                    "Gated launcher exited before its start token could be read"));
                    return new ProcessIdentity(pid, startToken);
                }
                if (!launcher.isAlive()) {
                    throw new AgentUnavailableException(
                            "Gated launcher exited with code " + launcher.exitValue() + " before reporting its identity");
                }
                if (System.nanoTime() >= deadline || !sleep(5)) {
                    throw new AgentUnavailableException("Gated launcher did not report its identity");
                }
            }
        }
    }

    private final class DefaultManagedProcess
            implements ManagedProcess
    {
        private static final int BUFFER_BYTES = 8 * 1024;
        private static final int OUTPUT_QUEUE_CAPACITY = 256;

        private final LaunchMarker marker;
        private final Process launcher;
        private final long payloadPid;
        private final long processGroup;
        private final ProcessSpec spec;
        private final ProcessOutputSink output;
        private final Instant startedAt = Instant.now();
        private final Object inputLock = new Object();
        private final Object outputLock = new Object();
        private final ExecutorService inputExecutor;
        private final BlockingQueue<OutputChunk> outputQueue =
                new LinkedBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final CompletableFuture<Void> stdoutDone = new CompletableFuture<>();
        private final CompletableFuture<Void> stderrDone = new CompletableFuture<>();
        private final AtomicInteger stdoutBytes = new AtomicInteger();
        private final AtomicInteger stderrBytes = new AtomicInteger();
        private final AtomicBoolean stdoutTruncated = new AtomicBoolean();
        private final AtomicBoolean stderrTruncated = new AtomicBoolean();
        private final AtomicBoolean stdoutTruncationReported = new AtomicBoolean();
        private final AtomicBoolean stderrTruncationReported = new AtomicBoolean();
        private final AtomicBoolean inputClosed = new AtomicBoolean();
        private final AtomicBoolean terminating = new AtomicBoolean();
        private final AtomicBoolean verifiedStopped = new AtomicBoolean();
        private final AtomicReference<ProcessExit.Reason> reason =
                new AtomicReference<>(ProcessExit.Reason.EXITED);
        private final Thread outputDispatcher;

        private DefaultManagedProcess(
                LaunchMarker marker,
                Process launcher,
                long payloadPid,
                long processGroup,
                ProcessSpec spec,
                ProcessOutputSink output)
        {
            this.marker = marker;
            this.launcher = launcher;
            this.payloadPid = payloadPid;
            this.processGroup = processGroup;
            this.spec = spec;
            this.output = output;
            this.inputExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("bytequay-process-stdin-" + payloadPid).factory());
            this.outputDispatcher = Thread.ofVirtual()
                    .name("bytequay-process-output-" + payloadPid)
                    .start(this::dispatchOutput);
            Thread.ofVirtual().name("bytequay-process-stdout-" + payloadPid).start(() ->
                    drain(launcher.getInputStream(), ProcessStream.STDOUT, stdoutDone));
            Thread.ofVirtual().name("bytequay-process-stderr-" + payloadPid).start(() ->
                    drain(launcher.getErrorStream(), ProcessStream.STDERR, stderrDone));
            launcher.onExit().whenComplete((ignored, failure) -> finish());
            CompletableFuture.delayedExecutor(spec.maximumRuntime().toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        // The launcher can exit while a payload descendant keeps running, so the
                        // runtime bound follows the owned process group rather than the root.
                        if (!groupEmpty()
                                && reason.compareAndSet(ProcessExit.Reason.EXITED, ProcessExit.Reason.TIMED_OUT)) {
                            terminate();
                        }
                    });
        }

        @Override
        public String launchId()
        {
            return marker.launchId();
        }

        @Override
        public long pid()
        {
            return payloadPid;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            requireNonNull(bytes, "bytes is null");
            byte[] copy = new byte[bytes.remaining()];
            bytes.duplicate().get(copy);
            return CompletableFuture.runAsync(() -> {
                synchronized (inputLock) {
                    if (inputClosed.get() || !launcher.isAlive()) {
                        throw new AgentUnavailableException("Process input is closed");
                    }
                    try {
                        OutputStream input = launcher.getOutputStream();
                        input.write(copy);
                        input.flush();
                    }
                    catch (IOException e) {
                        throw new CompletionException(new AgentUnavailableException("Unable to write process input", e));
                    }
                }
            }, inputExecutor);
        }

        @Override
        public void closeInput()
        {
            if (!inputClosed.compareAndSet(false, true)) {
                return;
            }
            synchronized (inputLock) {
                try {
                    launcher.getOutputStream().close();
                }
                catch (IOException ignored) {
                    // Closing an already-exited process is complete for the caller.
                }
            }
        }

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            // Root exit is not quiescence: a caller may still need to stop group members that
            // outlived the launcher, so cancellation stays available after the root is reaped.
            if (verifiedStopped.get()) {
                return;
            }
            reason.compareAndSet(ProcessExit.Reason.EXITED, ProcessExit.Reason.CANCELLED);
            terminate();
        }

        @Override
        public void close()
        {
            cancel();
            closeInput();
        }

        private void drain(InputStream stream, ProcessStream source, CompletableFuture<Void> done)
        {
            try (stream) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    offer(source, buffer, count);
                }
                done.complete(null);
            }
            catch (IOException e) {
                done.completeExceptionally(e);
            }
        }

        private void offer(ProcessStream source, byte[] buffer, int count)
        {
            AtomicBoolean truncated = truncated(source);
            if (truncated.get()) {
                return;
            }
            AtomicInteger observed = observed(source);
            int accepted = Math.min(count, limit(source) - observed.get());
            if (accepted <= 0) {
                truncated.set(true);
                return;
            }
            byte[] copy = new byte[accepted];
            System.arraycopy(buffer, 0, copy, 0, accepted);
            synchronized (outputLock) {
                if (!outputQueue.offer(new OutputChunk(source, copy))) {
                    truncated.set(true);
                    return;
                }
                observed.addAndGet(accepted);
            }
            if (accepted < count) {
                truncated.set(true);
            }
        }

        private void dispatchOutput()
        {
            try {
                while (!drainsFinished() || !outputQueue.isEmpty() || hasUnreportedTruncation()) {
                    OutputChunk chunk = outputQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        output.onOutput(chunk.stream(), ByteBuffer.wrap(chunk.bytes()).asReadOnlyBuffer());
                    }
                    if (outputQueue.isEmpty()) {
                        reportTruncation(ProcessStream.STDOUT);
                        reportTruncation(ProcessStream.STDERR);
                    }
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (RuntimeException ignored) {
                stdoutTruncated.set(true);
                stderrTruncated.set(true);
            }
        }

        private void reportTruncation(ProcessStream stream)
        {
            AtomicBoolean truncated = truncated(stream);
            AtomicBoolean reported = reported(stream);
            if (truncated.get() && reported.compareAndSet(false, true)) {
                output.onTruncated(stream);
            }
        }

        private void finish()
        {
            CompletableFuture.allOf(stdoutDone, stderrDone).whenComplete((ignored, failure) -> {
                try {
                    outputDispatcher.join();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closeInput();
                inputExecutor.shutdownNow();
                exit.complete(record(new ProcessExit(
                        launcher.exitValue(),
                        reason.get(),
                        verifyTreeStopped(),
                        Duration.between(startedAt, Instant.now()),
                        stdoutTruncated.get(),
                        stderrTruncated.get())));
            });
        }

        /**
         * Fsyncs the terminal receipt so an owner that crashes before acknowledging replays it.
         *
         * <p>A receipt that cannot be written must not hide the observed result from the caller.
         */
        private ProcessExit record(ProcessExit result)
        {
            try {
                store.write(marker.terminal(result));
            }
            catch (RuntimeException ignored) {
                // The in-process result still stands; recovery treats a missing marker as
                // unverifiable, which is the conservative outcome.
            }
            return result;
        }

        private ProcessExit.TreeTermination verifyTreeStopped()
        {
            if (!awaitGroupEmpty(processGroup, TREE_VERIFICATION_TIMEOUT)) {
                return ProcessExit.TreeTermination.UNVERIFIABLE;
            }
            verifiedStopped.set(true);
            return ProcessExit.TreeTermination.VERIFIED_STOPPED;
        }

        private boolean groupEmpty()
        {
            return ProcessGroups.members(processGroup).filter(List::isEmpty).isPresent();
        }

        /** Requests idempotent tree termination without blocking the caller for the grace period. */
        private void terminate()
        {
            closeInput();
            if (!terminating.compareAndSet(false, true)) {
                return;
            }
            Thread.ofVirtual().name("bytequay-process-stop-" + payloadPid).start(() -> {
                terminateGroup(processGroup, spec.cancellationGrace());
                launcher.descendants().forEach(ProcessHandle::destroyForcibly);
                launcher.destroyForcibly();
                closeOutputStreams();
            });
        }

        private void closeOutputStreams()
        {
            try {
                launcher.getInputStream().close();
            }
            catch (IOException ignored) {
                // The process already closed stdout.
            }
            try {
                launcher.getErrorStream().close();
            }
            catch (IOException ignored) {
                // The process already closed stderr.
            }
        }

        private boolean drainsFinished()
        {
            return stdoutDone.isDone() && stderrDone.isDone();
        }

        private boolean hasUnreportedTruncation()
        {
            return stdoutTruncated.get() && !stdoutTruncationReported.get()
                    || stderrTruncated.get() && !stderrTruncationReported.get();
        }

        private int limit(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? spec.maximumStdoutBytes() : spec.maximumStderrBytes();
        }

        private AtomicInteger observed(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? stdoutBytes : stderrBytes;
        }

        private AtomicBoolean truncated(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? stdoutTruncated : stderrTruncated;
        }

        private AtomicBoolean reported(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? stdoutTruncationReported : stderrTruncationReported;
        }
    }

    private record OutputChunk(ProcessStream stream, byte[] bytes)
    {
        private OutputChunk
        {
            requireNonNull(stream, "stream is null");
            requireNonNull(bytes, "bytes is null");
        }
    }
}
