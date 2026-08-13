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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real local remote, because the claim is that a push mechanically
 * cannot succeed — and a test that never proves a push <em>can</em> succeed
 * without containment proves nothing about the containment.
 */
final class TestCliWriterContainment
{
    @Test
    void aPushSucceedsWithoutContainment(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Fixture fixture = Fixture.create(root);

        // The control. If this ever stops passing, every assertion below is
        // vacuous and we would not otherwise find out.
        assertThat(fixture.push(Map.of()).exitCode()).isZero();
        assertThat(CliWriterContainment.remoteHead(
                fixture.worktree, "origin", "main")).isNotNull();
    }

    @Test
    void aPushFailsOnceTheDestinationRefuses(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Fixture fixture = Fixture.create(root);
        CliWriterContainment.Applied applied = CliWriterContainment.apply(
                fixture.worktree, root.resolve("scratch"), "origin");

        Result result = fixture.push(applied.environment());

        assertThat(result.exitCode())
                .as("a contained turn must not be able to publish")
                .isNotZero();
        // Whatever form the command took, there was nowhere to send it.
        assertThat(result.output()).contains("containment-refused");
    }

    @Test
    void aPushStillFailsWhenTheAgentRewritesTheUrlItself(@TempDir Path root)
            throws IOException, InterruptedException
    {
        // The obvious defeat: point the remote back at somewhere real. It has to
        // fail anyway, because the credential half of the containment is what
        // actually stops it — this is the assertion that the design does not
        // rest on the poisoned URL alone.
        Fixture fixture = Fixture.create(root);
        Path scratch = root.resolve("scratch");
        CliWriterContainment.Applied applied = CliWriterContainment.apply(
                fixture.worktree, scratch, "origin");
        Files.deleteIfExists(scratch.resolve("hooks").resolve("pre-push"));

        Result result = fixture.pushTo(
                applied.environment(), "https://github.com/denied/denied.git");

        assertThat(result.exitCode())
                .as("with no credential and no prompt, an HTTPS push cannot"
                        + " authenticate")
                .isNotZero();
    }

    @Test
    void theProgramCanPublishOnceContainmentIsLifted(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Fixture fixture = Fixture.create(root);
        CliWriterContainment.Applied applied = CliWriterContainment.apply(
                fixture.worktree, root.resolve("scratch"), "origin");
        assertThat(fixture.push(applied.environment()).exitCode()).isNotZero();

        CliWriterContainment.lift(fixture.worktree, "origin", applied);

        // The program's own push must work afterwards, or containment would be a
        // one-way door and the run could never publish.
        assertThat(fixture.push(Map.of()).exitCode()).isZero();
    }

    @Test
    void liftIsSafeToCallWhenATurnAlreadyCrashed(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Fixture fixture = Fixture.create(root);
        CliWriterContainment.Applied applied = CliWriterContainment.apply(
                fixture.worktree, root.resolve("scratch"), "origin");

        // A crashed turn leaves the refusing URL behind, so the program lifts
        // defensively before its own push rather than assuming a clean exit.
        CliWriterContainment.lift(fixture.worktree, "origin", applied);
        CliWriterContainment.lift(fixture.worktree, "origin", applied);

        assertThat(fixture.push(Map.of()).exitCode()).isZero();
    }

    @Test
    void aMovedRemoteHeadIsQuarantinedRatherThanReconciled(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Fixture fixture = Fixture.create(root);
        String before = CliWriterContainment.remoteHead(
                fixture.worktree, "origin", "main");

        // Prevention cannot prove a negative, so the program measures. Something
        // published during a turn that is not allowed to: that is not a state to
        // reconcile with, it is a state to stop on.
        assertThat(fixture.push(Map.of()).exitCode()).isZero();
        String after = CliWriterContainment.remoteHead(
                fixture.worktree, "origin", "main");

        assertThat(after).isNotEqualTo(before);
        assertThatThrownBy(() -> CliWriterContainment.assertRemoteUnmoved(before, after))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quarantined");
    }

    @Test
    void anUnmovedRemoteHeadPasses(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Fixture fixture = Fixture.create(root);
        assertThat(fixture.push(Map.of()).exitCode()).isZero();
        String head = CliWriterContainment.remoteHead(
                fixture.worktree, "origin", "main");

        // The mirror: a turn that behaved must not be quarantined, or the check
        // would stop every run instead of the wrong ones.
        CliWriterContainment.assertRemoteUnmoved(head, head);
    }

    @Test
    void theScrubbedEnvironmentRemovesEveryWayToAuthenticate(@TempDir Path root)
            throws IOException
    {
        Map<String, String> environment =
                CliWriterContainment.environment(root.resolve("scratch"));

        // Named individually because each one closes a distinct door, and a
        // future edit that drops one should fail here rather than in the wild.
        assertThat(environment).containsKeys(
                "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM",
                "SSH_AUTH_SOCK", "GIT_ASKPASS", "SSH_ASKPASS",
                "GIT_TERMINAL_PROMPT");
        assertThat(environment.get("GIT_TERMINAL_PROMPT")).isEqualTo("0");
        assertThat(environment.get("SSH_AUTH_SOCK")).isEmpty();
        // The config the agent reads must genuinely be empty: a global config
        // with a credential helper in it is the whole hole.
        assertThat(Files.readString(
                Path.of(environment.get("GIT_CONFIG_GLOBAL")))).isEmpty();
    }

    @Test
    void containmentWorksFromALinkedWorktree(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Fixture fixture = Fixture.create(root);
        Path linked = root.resolve("linked");
        run(fixture.worktree, Map.of(), "git", "worktree", "add", "-b",
                "linked", linked.toString());
        assertThat(Files.isRegularFile(linked.resolve(".git"))).isTrue();

        CliWriterContainment.Applied applied = CliWriterContainment.apply(
                linked, root.resolve("linked-scratch"), "origin");

        assertThat(attempt(linked, applied.environment(), "git", "push",
                "origin", "HEAD:refs/heads/linked").exitCode()).isNotZero();
        assertThat(CliWriterContainment.gitDirectory(linked)).isDirectory();
        CliWriterContainment.lift(linked, "origin", applied);
        assertThat(attempt(linked, Map.of(), "git", "push", "origin",
                "HEAD:refs/heads/linked").exitCode()).isZero();
    }

    private record Result(int exitCode, String output) {}

    /** A bare remote and a clone with one unpushed commit, ready to push. */
    private record Fixture(Path remote, Path worktree)
    {
        static Fixture create(Path root)
                throws IOException, InterruptedException
        {
            Path remote = root.resolve("remote.git");
            Path worktree = root.resolve("clone");
            Files.createDirectories(remote);
            run(root, Map.of(), "git", "init", "--bare", "-b", "main",
                    remote.toString());
            run(root, Map.of(), "git", "clone", remote.toString(),
                    worktree.toString());
            run(worktree, Map.of(), "git", "config", "user.email", "t@example.com");
            run(worktree, Map.of(), "git", "config", "user.name", "Test");
            Files.writeString(worktree.resolve("a.txt"), "one\n",
                    StandardCharsets.UTF_8);
            run(worktree, Map.of(), "git", "add", "a.txt");
            run(worktree, Map.of(), "git", "commit", "-m", "first");
            return new Fixture(remote, worktree);
        }

        Result push(Map<String, String> environment)
                throws IOException, InterruptedException
        {
            return attempt(worktree, environment,
                    "git", "push", "origin", "HEAD:refs/heads/main");
        }

        Result pushTo(Map<String, String> environment, String url)
                throws IOException, InterruptedException
        {
            return attempt(worktree, environment,
                    "git", "push", url, "HEAD:refs/heads/main");
        }
    }

    private static Result attempt(
            Path directory, Map<String, String> environment, String... argv)
            throws IOException, InterruptedException
    {
        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private static void run(
            Path directory, Map<String, String> environment, String... argv)
            throws IOException, InterruptedException
    {
        Result result = attempt(directory, environment, argv);
        if (result.exitCode() != 0) {
            List<String> command = new ArrayList<>(List.of(argv));
            throw new IOException(command + " failed: " + result.output());
        }
    }
}
