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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one bounded system command and reads its output.
 *
 * <p>Every failure returns an absent result so callers fail closed instead of trusting partial output.
 */
final class ShortCommand
{
    private ShortCommand() {}

    static Optional<String> run(List<String> command, Duration timeout)
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            InputStream stream = process.getInputStream();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try (stream) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }, task -> Thread.ofVirtual().start(task));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(output.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        }
        catch (IOException | ExecutionException | TimeoutException failure) {
            return Optional.empty();
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
