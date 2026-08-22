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
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Default JDK process implementation used by the Codex and Claude CLI lanes. */
public final class DefaultProcessSupervisor
        implements ProcessSupervisor
{
    @Override
    public ManagedProcess start(ProcessSpec spec, ProcessOutputSink output)
    {
        requireNonNull(spec, "spec is null");
        requireNonNull(output, "output is null");
        ProcessBuilder builder = new ProcessBuilder(spec.argv())
                .directory(spec.workingDirectory().toFile())
                .redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(spec.environment());
        try {
            return new DefaultManagedProcess(builder.start(), spec, output);
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to start process: " + spec.argv().getFirst(), e);
        }
    }

    private static final class DefaultManagedProcess
            implements ManagedProcess
    {
        private static final int BUFFER_BYTES = 8 * 1024;
        private static final int OUTPUT_QUEUE_CAPACITY = 256;

        private final Process process;
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
        private final AtomicReference<ProcessExit.Reason> reason =
                new AtomicReference<>(ProcessExit.Reason.EXITED);
        private final Thread outputDispatcher;

        private DefaultManagedProcess(Process process, ProcessSpec spec, ProcessOutputSink output)
        {
            this.process = requireNonNull(process, "process is null");
            this.spec = requireNonNull(spec, "spec is null");
            this.output = requireNonNull(output, "output is null");
            this.inputExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("bytequay-process-stdin-" + process.pid()).factory());
            this.outputDispatcher = Thread.ofVirtual()
                    .name("bytequay-process-output-" + process.pid())
                    .start(this::dispatchOutput);
            Thread.ofVirtual().name("bytequay-process-stdout-" + process.pid()).start(() ->
                    drain(process.getInputStream(), ProcessStream.STDOUT, stdoutDone));
            Thread.ofVirtual().name("bytequay-process-stderr-" + process.pid()).start(() ->
                    drain(process.getErrorStream(), ProcessStream.STDERR, stderrDone));
            process.onExit().whenComplete((ignored, failure) -> finish());
            CompletableFuture.delayedExecutor(spec.maximumRuntime().toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (process.isAlive()
                                && reason.compareAndSet(ProcessExit.Reason.EXITED, ProcessExit.Reason.TIMED_OUT)) {
                            terminate();
                        }
                    });
        }

        @Override
        public long pid()
        {
            return process.pid();
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            requireNonNull(bytes, "bytes is null");
            byte[] copy = new byte[bytes.remaining()];
            bytes.duplicate().get(copy);
            return CompletableFuture.runAsync(() -> {
                synchronized (inputLock) {
                    if (inputClosed.get() || !process.isAlive()) {
                        throw new AgentUnavailableException("Process input is closed");
                    }
                    try {
                        OutputStream input = process.getOutputStream();
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
                    process.getOutputStream().close();
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
            if (exit.isDone() || !process.isAlive()) {
                return;
            }
            if (reason.compareAndSet(ProcessExit.Reason.EXITED, ProcessExit.Reason.CANCELLED)) {
                terminate();
            }
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
                exit.complete(new ProcessExit(
                        process.exitValue(),
                        reason.get(),
                        Duration.between(startedAt, Instant.now()),
                        stdoutTruncated.get(),
                        stderrTruncated.get()));
            });
        }

        private void terminate()
        {
            closeInput();
            List<ProcessHandle> tree = processTree();
            tree.forEach(handle -> destroy(handle, false));
            CompletableFuture.delayedExecutor(spec.cancellationGrace().toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        tree.stream()
                                .filter(ProcessHandle::isAlive)
                                .forEach(handle -> destroy(handle, true));
                        closeOutputStreams();
                    });
        }

        private void closeOutputStreams()
        {
            try {
                process.getInputStream().close();
            }
            catch (IOException ignored) {
                // The process already closed stdout.
            }
            try {
                process.getErrorStream().close();
            }
            catch (IOException ignored) {
                // The process already closed stderr.
            }
        }

        private List<ProcessHandle> processTree()
        {
            try {
                return ImmutableList.<ProcessHandle>builder()
                        .addAll(process.descendants().collect(toImmutableList()))
                        .add(process.toHandle())
                        .build();
            }
            catch (RuntimeException ignored) {
                return ImmutableList.of(process.toHandle());
            }
        }

        private static void destroy(ProcessHandle handle, boolean forcibly)
        {
            try {
                if (forcibly) {
                    handle.destroyForcibly();
                }
                else {
                    handle.destroy();
                }
            }
            catch (RuntimeException ignored) {
                // The process already exited or the host denied process inspection.
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
