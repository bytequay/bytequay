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

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TestDefaultProcessSupervisor
{
    private final DefaultProcessSupervisor supervisor = new DefaultProcessSupervisor();

    @TempDir
    private Path worktree;

    @Test
    void writesStdinAndKeepsStdoutAndStderrSeparate()
            throws Exception
    {
        Map<ProcessStream, StringBuilder> output = new EnumMap<>(ProcessStream.class);
        output.put(ProcessStream.STDOUT, new StringBuilder());
        output.put(ProcessStream.STDERR, new StringBuilder());
        ManagedProcess process = supervisor.start(
                spec("read value; printf 'out:%s' \"$value\"; printf 'err:%s' \"$value\" >&2"),
                (stream, bytes) -> output.get(stream).append(StandardCharsets.UTF_8.decode(bytes)));

        process.write(ByteBuffer.wrap("hello\n".getBytes(StandardCharsets.UTF_8)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ProcessExit exit = process.exit().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(exit.reason()).isEqualTo(ProcessExit.Reason.EXITED);
        assertThat(exit.stdoutTruncated()).isFalse();
        assertThat(exit.stderrTruncated()).isFalse();
        assertThat(output.get(ProcessStream.STDOUT)).hasToString("out:hello");
        assertThat(output.get(ProcessStream.STDERR)).hasToString("err:hello");
    }

    @Test
    void truncatesBoundedOutputWithoutBlockingTheChild()
            throws Exception
    {
        StringBuilder output = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean();
        ProcessSpec spec = new ProcessSpec(
                ImmutableList.of("/bin/sh", "-c", "i=0; while [ $i -lt 1000 ]; do printf 1234567890; i=$((i+1)); done"),
                worktree,
                ImmutableMap.of(),
                64,
                64,
                Duration.ofSeconds(5),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.start(spec, new ProcessOutputSink()
        {
            @Override
            public void onOutput(ProcessStream stream, ByteBuffer bytes)
            {
                output.append(StandardCharsets.UTF_8.decode(bytes));
            }

            @Override
            public void onTruncated(ProcessStream stream)
            {
                truncated.set(true);
            }
        });

        ProcessExit exit = process.exit().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(exit.stdoutTruncated()).isTrue();
        assertThat(output).hasSize(64);
        assertThat(truncated).isTrue();
    }

    @Test
    void drainsTheChildWhenTheOutputConsumerIsSlow()
            throws Exception
    {
        CountDownLatch enteredSink = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        ProcessSpec spec = new ProcessSpec(
                ImmutableList.of("/bin/sh", "-c", "dd if=/dev/zero bs=4096 count=1024 2>/dev/null"),
                worktree,
                ImmutableMap.of(),
                4 * 1024 * 1024,
                64,
                Duration.ofSeconds(10),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.start(spec, (stream, bytes) -> {
            enteredSink.countDown();
            try {
                releaseSink.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(enteredSink.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitDead(process.pid(), Duration.ofSeconds(5))).isTrue();
        releaseSink.countDown();
        ProcessExit exit = process.exit().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(exit.stdoutTruncated()).isTrue();
    }

    @Test
    void cancelsTheProcessTreeAndTimesOutLongRuns()
            throws Exception
    {
        StringBuilder stdout = new StringBuilder();
        CountDownLatch childReported = new CountDownLatch(1);
        ManagedProcess cancelled = supervisor.start(
                spec("sleep 30 & child=$!; printf '%s\\n' \"$child\"; wait"),
                (stream, bytes) -> {
                    stdout.append(StandardCharsets.UTF_8.decode(bytes));
                    if (stdout.indexOf("\n") >= 0) {
                        childReported.countDown();
                    }
                });
        assertThat(childReported.await(5, TimeUnit.SECONDS)).isTrue();
        long childPid = Long.parseLong(stdout.toString().trim());

        cancelled.cancel();
        ProcessExit cancelledExit = cancelled.exit().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(cancelledExit.reason()).isEqualTo(ProcessExit.Reason.CANCELLED);
        assertThat(awaitDead(cancelled.pid(), Duration.ofSeconds(2))).isTrue();
        if (canInspectDescendants()) {
            assertThat(awaitDead(childPid, Duration.ofSeconds(2))).isTrue();
        }
        ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);

        ProcessSpec timeoutSpec = new ProcessSpec(
                ImmutableList.of("/bin/sh", "-c", "sleep 30"),
                worktree,
                ImmutableMap.of(),
                64,
                64,
                Duration.ofMillis(100),
                Duration.ofMillis(100));
        ManagedProcess timedOut = supervisor.start(timeoutSpec, (stream, bytes) -> {});
        ProcessExit timedOutExit = timedOut.exit().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(timedOutExit.reason()).isEqualTo(ProcessExit.Reason.TIMED_OUT);
        assertThat(awaitDead(timedOut.pid(), Duration.ofSeconds(2))).isTrue();
    }

    private ProcessSpec spec(String script)
    {
        return new ProcessSpec(
                ImmutableList.of("/bin/sh", "-c", script),
                worktree,
                ImmutableMap.of(),
                1024,
                1024,
                Duration.ofSeconds(10),
                Duration.ofMillis(100));
    }

    private static boolean awaitDead(long pid, Duration timeout)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(10);
            }
            else {
                return true;
            }
        }
        return false;
    }

    private static boolean canInspectDescendants()
    {
        try {
            ProcessHandle.current().descendants().close();
            return true;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }
}
