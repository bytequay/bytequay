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
package com.bytequay.app.flow.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Runs a CLI agent as the leader of its own process group, and proves the whole
 * group dead afterwards.
 *
 * <p>This is the mechanical death receipt {@code workflow-runtime.md} requires
 * before an OS-process transport may be admitted: until the previous agent is
 * provably gone, a successor writer must not touch its worktree.
 *
 * <p><b>Why a group and not a tree.</b> {@link ProcessTree} walks descendants,
 * which a process defeats simply by outliving its parent — reparenting changes
 * {@code PPID}, and a snapshot taken before that is already wrong. A process
 * group does not have that hole: leaving one requires a deliberate
 * {@code setpgid}, so signalling and probing the group covers every descendant
 * that did not explicitly escape. That is the difference between diagnostics and
 * a receipt.
 *
 * <p><b>No native code and no bundled binary.</b> Java cannot put a child in a
 * new process group, and macOS ships no {@code setsid}. But a shell in job-control
 * mode ({@code set -m}) puts each background job in its own group, with the
 * group id equal to the job's pid — so a three-line {@code /bin/sh} wrapper gets
 * there, and the pid it reports is the group id. The alternative was an FFM
 * {@code setsid()} call or shipping a helper executable; both are more code and
 * more platform risk for the same property.
 *
 * <p>The group id is written to a file rather than stdout, so the agent's own
 * output stays exactly what the CLI produced. The program reads it before
 * delivering the prompt, which is what makes the group recoverable across a
 * restart: an id learned only after the turn would be lost by the crash that
 * makes it matter.
 */
final class ProcessGroup
{
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REAP = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(50);

    private ProcessGroup() {}

    /**
     * @param pgid the group every process of this turn belongs to, and the only
     *         handle needed to signal or bury it later
     */
    record Spawned(Process process, long pgid) {}

    /**
     * Starts {@code argv} as its own process group.
     *
     * @param groupIdFile where the wrapper records the group id; the program owns
     *         this path and must persist what it reads <em>before</em> sending the
     *         prompt, so a crash mid-turn still leaves a buryable group
     * @throws IOException when the wrapper never reported a group, which is a
     *         failure to launch rather than a failure of the agent
     */
    static Spawned start(
            List<String> argv,
            Path workingDirectory,
            Map<String, String> environment,
            Path groupIdFile)
            throws IOException, InterruptedException
    {
        requireNonNull(argv, "argv is null");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv is empty");
        }
        Files.createDirectories(groupIdFile.toAbsolutePath().getParent());
        Files.deleteIfExists(groupIdFile);
        // set -m puts the background job in its own group; $! is both its pid and
        // its group id. `wait` then makes this shell's exit status the agent's,
        // so the caller's Process still means what it usually means.
        String script = "set -m; \"$@\" & printf %s \"$!\" > \"$BQ_PGID_FILE\";"
                + " wait $!";
        List<String> command = new ArrayList<>(
                List.of("/bin/sh", "-c", script, "sh"));
        command.addAll(argv);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        builder.environment().put(
                "BQ_PGID_FILE", groupIdFile.toAbsolutePath().toString());
        Process process = builder.start();
        long pgid = awaitGroupId(groupIdFile, process);
        return new Spawned(process, pgid);
    }

    /** Whether any process in the group is still alive. */
    static boolean isAlive(long pgid)
            throws InterruptedException
    {
        // Signal 0 tests deliverability without delivering; a negative target is
        // the group. Java's ProcessHandle cannot address a group at all, which is
        // why this shells out.
        return signal(pgid, "0") == 0;
    }

    /**
     * Asks the group to exit, then kills it, then proves it gone.
     *
     * @return empty when the group no longer exists — the receipt. A present
     *         value is the group that outlived the kill, and the caller must
     *         refuse a successor writer rather than continue.
     */
    static Optional<Long> bury(long pgid)
            throws InterruptedException
    {
        return bury(pgid, DEFAULT_GRACE, DEFAULT_REAP);
    }

    static Optional<Long> bury(long pgid, Duration grace, Duration reap)
            throws InterruptedException
    {
        signal(pgid, "TERM");
        if (awaitDeath(pgid, grace)) {
            return Optional.empty();
        }
        signal(pgid, "KILL");
        return awaitDeath(pgid, reap) ? Optional.empty() : Optional.of(pgid);
    }

    /** A durable one-liner for the attempt's {@code stop_proof_ref}. */
    static String proof(long pgid, Optional<Long> alive)
    {
        return alive.isPresent()
                ? "process-group:pgid=" + pgid + ";ALIVE"
                : "process-group:pgid=" + pgid + ";gone";
    }

    private static boolean awaitDeath(long pgid, Duration timeout)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (!isAlive(pgid)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(POLL);
        }
    }

    private static int signal(long pgid, String signal)
            throws InterruptedException
    {
        if (pgid <= 1) {
            // Never address group 0 (the caller's own group) or 1. Getting this
            // wrong would signal this JVM, or init.
            throw new IllegalArgumentException("refusing to signal group " + pgid);
        }
        try {
            Process kill = new ProcessBuilder(
                    "/bin/kill", "-" + signal, "-" + pgid)
                    .redirectErrorStream(true)
                    .start();
            kill.getInputStream().readAllBytes();
            return kill.waitFor();
        }
        catch (IOException unavailable) {
            // Treated as "cannot prove anything", which the callers read as still
            // alive — never as a successful burial.
            return -1;
        }
    }

    private static long awaitGroupId(Path groupIdFile, Process process)
            throws IOException, InterruptedException
    {
        long deadline = System.nanoTime() + HANDSHAKE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(groupIdFile)) {
                String text = Files.readString(groupIdFile, StandardCharsets.UTF_8)
                        .strip();
                if (!text.isBlank()) {
                    try {
                        long pgid = Long.parseLong(text);
                        if (pgid > 1) {
                            return pgid;
                        }
                    }
                    catch (NumberFormatException stillWriting) {
                        // A partial write; the next poll sees the whole number.
                    }
                }
            }
            if (!process.isAlive() && !Files.exists(groupIdFile)) {
                throw new IOException(
                        "the agent wrapper exited without reporting a process"
                                + " group; nothing can be buried");
            }
            Thread.sleep(POLL);
        }
        process.destroyForcibly();
        throw new IOException("the agent wrapper never reported a process group");
    }
}
