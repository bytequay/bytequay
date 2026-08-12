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
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Makes a CLI writer turn mechanically unable to publish.
 *
 * <p>The flow's contract is that a writer agent <em>cannot</em> push — not that it
 * was asked not to. Today's conflict-repair prompt only asks, in prose, and that
 * is worth nothing: the agent has a shell.
 *
 * <p>This does not police the command. Matching shell text for {@code git push}
 * is defeatable in a dozen ways ({@code g=push; git $g}, a script file, a base64
 * blob) and pretending otherwise would be worse than doing nothing, because it
 * would read as a guarantee. Instead the capability is removed:
 *
 * <ul>
 *   <li>the worktree has no push destination — its push URL refuses;</li>
 *   <li>the turn has no credential — no helper, no agent socket, no prompt; and
 *   <li>a {@code pre-push} hook refuses, as a third layer that {@code --no-verify}
 *       can bypass and which is therefore never the argument.</li>
 * </ul>
 *
 * <p>The credential half is the one that matters most, and it is easy to miss:
 * {@code GitRunner} shells out to the system {@code git} <em>deliberately</em>, so
 * the user's own gitconfig, keychain helper and SSH keys apply. An unscrubbed
 * environment therefore hands a writer agent working push credentials.
 *
 * <p><b>What this does not do.</b> It does not stop writes outside the worktree.
 * Nothing short of a real sandbox does, so that belongs to the engine's own
 * containment (Codex {@code workspace-write}, Claude Code's permission
 * configuration) and is not claimed here.
 *
 * <p>Prevention cannot prove a negative, so the program also measures: see
 * {@link #remoteHead} and {@link #assertRemoteUnmoved}. A remote that moved under
 * a writer turn is quarantined, never reconciled.
 */
final class CliWriterContainment
{
    /** A scheme no transport implements, so the failure is immediate and clear. */
    private static final String REFUSING_PUSH_URL =
            "containment-refused://writer-turns-cannot-push";
    private static final String HOOK = """
            #!/bin/sh
            echo 'pre-push refused: writer turns cannot publish' >&2
            exit 1
            """;

    private CliWriterContainment() {}

    /**
     * What was changed, so it can be lifted when the program itself needs to
     * push.
     *
     * @param originalPushUrl null when the remote had no separate push URL, which
     *         is the normal case — lifting then removes the override rather than
     *         restoring one that never existed.
     */
    record Applied(Map<String, String> environment, String originalPushUrl) {}

    /**
     * Removes the ability to publish from {@code worktree} for the duration of a
     * writer turn.
     *
     * @param scratch a directory this turn owns; the empty git config files live
     *         here, so nothing in the user's home is touched
     */
    static Applied apply(Path worktree, Path scratch, String remote)
            throws IOException, InterruptedException
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(scratch, "scratch is null");
        requireNonNull(remote, "remote is null");
        String original = pushUrl(worktree, remote);
        git(worktree, "remote", "set-url", "--push", remote, REFUSING_PUSH_URL);
        installHook(worktree);
        return new Applied(environment(scratch), original);
    }

    /**
     * Restores the push destination. Idempotent, and the program should call it
     * defensively before its own push rather than assuming a turn cleaned up — a
     * crashed turn leaves the refusing URL in place, and that must fail loudly at
     * the program's push rather than silently at a later one.
     */
    static void lift(Path worktree, String remote, Applied applied)
            throws IOException, InterruptedException
    {
        requireNonNull(applied, "applied is null");
        if (applied.originalPushUrl() == null) {
            // No separate push URL before, so the fetch URL governs again.
            git(worktree, "remote", "set-url", "--delete", "--push", remote,
                    REFUSING_PUSH_URL);
        }
        else {
            git(worktree, "remote", "set-url", "--push", remote,
                    applied.originalPushUrl());
        }
        Files.deleteIfExists(hookPath(worktree));
    }

    /**
     * The environment a writer turn runs in. Every entry removes a way to
     * authenticate; none of them removes a way to build, which is why the
     * network itself is left alone — a Maven or npm build needs it.
     */
    static Map<String, String> environment(Path scratch)
            throws IOException
    {
        Files.createDirectories(scratch);
        Path emptyConfig = scratch.resolve("empty.gitconfig");
        if (!Files.exists(emptyConfig)) {
            Files.writeString(emptyConfig, "", StandardCharsets.UTF_8);
        }
        Map<String, String> environment = new LinkedHashMap<>();
        // No credential helper can be configured if there is no config to read.
        // This is the load-bearing one: the user's keychain helper lives in the
        // global config, and GitRunner shells out precisely so it applies.
        environment.put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
        environment.put("GIT_CONFIG_SYSTEM", emptyConfig.toString());
        // No agent to answer a key challenge, and no way to ask a human.
        environment.put("SSH_AUTH_SOCK", "");
        environment.put("GIT_ASKPASS", "/bin/false");
        environment.put("SSH_ASKPASS", "/bin/false");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        return Map.copyOf(environment);
    }

    /** The remote's tip before a turn, so afterwards it can be proven unmoved. */
    static String remoteHead(Path worktree, String remote, String branch)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(
                "git", "ls-remote", "--exit-code", remote, "refs/heads/" + branch)
                .directory(worktree.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Exit 2 is "ref not found", which is a legitimate state before the first
        // publish and must not read as a failure to observe.
        int exit = process.waitFor();
        if (exit == 2) {
            return null;
        }
        if (exit != 0) {
            throw new IOException("could not read the remote head: " + output);
        }
        int tab = output.indexOf('\t');
        return tab < 0 ? null : output.substring(0, tab).strip();
    }

    /**
     * @throws IllegalStateException when the remote moved, which means something
     *         published during a turn that is not allowed to. The caller
     *         quarantines; it never reconciles.
     */
    static void assertRemoteUnmoved(String before, String after)
    {
        if (!Objects.equals(before, after)) {
            throw new IllegalStateException(
                    "the remote head moved during a writer turn: "
                            + before + " -> " + after
                            + "; the turn is quarantined rather than reconciled");
        }
    }

    private static String pushUrl(Path worktree, String remote)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(
                "git", "remote", "get-url", "--push", remote)
                .directory(worktree.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .strip();
        // git reports the fetch URL when no push URL is set, so the two being
        // equal means there was no override to restore.
        return process.waitFor() == 0 && !output.isBlank() ? output : null;
    }

    private static void installHook(Path worktree)
            throws IOException
    {
        Path hook = hookPath(worktree);
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, HOOK, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(hook, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
        catch (UnsupportedOperationException notPosix) {
            // A non-POSIX filesystem cannot mark it executable, so git will skip
            // it. The other two layers do not depend on this one.
        }
    }

    private static Path hookPath(Path worktree)
    {
        return worktree.resolve(".git").resolve("hooks").resolve("pre-push");
    }

    private static void git(Path worktree, String... args)
            throws IOException, InterruptedException
    {
        String[] argv = new String[args.length + 1];
        argv[0] = "git";
        System.arraycopy(args, 0, argv, 1, args.length);
        Process process = new ProcessBuilder(argv)
                .directory(worktree.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException(
                    "git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
