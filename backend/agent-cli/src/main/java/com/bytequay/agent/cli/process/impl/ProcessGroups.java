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

import com.google.common.collect.ImmutableList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Reads operating-system process-group membership.
 *
 * <p>The JDK exposes no process-group API, so this reads one bounded {@code ps} snapshot. Every
 * failure returns an absent result so callers fail closed instead of assuming an empty group.
 */
final class ProcessGroups
{
    private static final List<String> SNAPSHOT_COMMAND = ImmutableList.of("/bin/ps", "-A", "-o", "pid=,pgid=");
    private static final long SNAPSHOT_TIMEOUT_MILLIS = 5_000;

    private ProcessGroups() {}

    /**
     * Returns the process-group identifier of a live process.
     *
     * @param pid process to inspect
     * @return the group identifier, or empty when the process is gone or cannot be read
     */
    static OptionalLong groupOf(long pid)
    {
        Optional<List<long[]>> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return OptionalLong.empty();
        }
        return snapshot.orElseThrow().stream()
                .filter(row -> row[0] == pid)
                .mapToLong(row -> row[1])
                .findFirst();
    }

    /**
     * Returns every live member of one process group.
     *
     * @param processGroup group identifier to inspect
     * @return member process identifiers, or empty when the snapshot could not be read
     */
    static Optional<List<Long>> members(long processGroup)
    {
        return snapshot().map(rows -> rows.stream()
                .filter(row -> row[1] == processGroup)
                .map(row -> row[0])
                .collect(ImmutableList.toImmutableList()));
    }

    private static Optional<List<long[]>> snapshot()
    {
        Process process = null;
        try {
            process = new ProcessBuilder(SNAPSHOT_COMMAND).redirectErrorStream(false).start();
            process.getOutputStream().close();
            ImmutableList.Builder<long[]> rows = ImmutableList.builder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parse(line).ifPresent(rows::add);
                }
            }
            if (!process.waitFor(SNAPSHOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(rows.build());
        }
        catch (IOException e) {
            return Optional.empty();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static Optional<long[]> parse(String line)
    {
        String[] columns = line.trim().split("\\s+");
        if (columns.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new long[] {Long.parseLong(columns[0]), Long.parseLong(columns[1])});
        }
        catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
