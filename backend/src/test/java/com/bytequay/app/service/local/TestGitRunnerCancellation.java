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
package com.bytequay.app.service.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class TestGitRunnerCancellation
{
    @TempDir
    private Path tempDir;

    @Test
    void interruptionStopsGitAndItsMutationChildProcess()
            throws Exception
    {
        Path repository = tempDir.resolve("repo");
        Path script = tempDir.resolve("blocking-ssh.sh");
        Path processIds = tempDir.resolve("process-ids");
        Files.createDirectories(repository);
        Files.writeString(script, """
                sleep 300 &
                child=$!
                printf '%s %s %s' "$PPID" "$$" "$child" > "$1"
                wait "$child"
                """);
        run("git", "init", repository.toString());
        run("git", "-C", repository.toString(), "remote", "add", "origin",
                "ssh://example.invalid/acme/widget.git");
        run("git", "-C", repository.toString(), "config", "ssh.variant", "ssh");
        run("git", "-C", repository.toString(), "config", "core.sshCommand",
                "/bin/sh " + quote(script) + " " + quote(processIds));

        GitRunner git = new GitRunner();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                git.fetchRemote(repository, "origin");
            }
            catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        List<Long> spawned = List.of();
        boolean canInspectDescendants = canInspectDescendants();
        try {
            await(() -> Files.exists(processIds), Duration.ofSeconds(5));
            spawned = Arrays.stream(Files.readString(processIds).trim().split(" "))
                    .map(Long::parseLong)
                    .toList();
            assertThat(spawned).hasSize(3);
            assertThat(spawned).allSatisfy(pid -> assertThat(isAlive(pid)).isTrue());

            worker.interrupt();
            worker.join(Duration.ofSeconds(5));

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            long gitProcess = spawned.getFirst();
            await(() -> !isAlive(gitProcess), Duration.ofSeconds(5));
            if (canInspectDescendants) {
                List<Long> processTree = spawned;
                await(
                        () -> processTree.stream()
                                .noneMatch(TestGitRunnerCancellation::isAlive),
                        Duration.ofSeconds(5));
            }
        }
        finally {
            worker.interrupt();
            for (int index = 1; index < spawned.size(); index++) {
                ProcessHandle.of(spawned.get(index))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private static void run(String... command)
            throws Exception
    {
        Process process = new ProcessBuilder(command).start();
        assertThat(process.waitFor()).isZero();
    }

    private static String quote(Path path)
    {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    private static boolean isAlive(long pid)
    {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean canInspectDescendants()
    {
        try {
            List<ProcessHandle> ignored = ProcessHandle.current().descendants().toList();
            return true;
        }
        catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout)
            throws Exception
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not met before timeout");
            }
            Thread.sleep(10);
        }
    }
}
