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

import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Reads and durably writes the launch markers, identity handoffs, and gates of one runtime root. */
final class LaunchMarkerStore
{
    static final String RELEASE = "GO";
    static final String CANCEL = "STOP";

    private static final String MARKER_SUFFIX = ".json";
    private static final String GATE_SUFFIX = ".gate";
    private static final String IDENTITY_SUFFIX = ".pid";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path directory;

    LaunchMarkerStore(Path directory)
    {
        this.directory = requireNonNull(directory, "directory is null").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to create the process launch directory", e);
        }
    }

    Path gateFile(String launchId)
    {
        return directory.resolve(launchId + GATE_SUFFIX);
    }

    Path identityFile(String launchId)
    {
        return directory.resolve(launchId + IDENTITY_SUFFIX);
    }

    /** Writes and fsyncs the marker so the recorded phase survives an immediate crash. */
    void write(LaunchMarker marker)
    {
        try {
            replace(directory.resolve(marker.launchId() + MARKER_SUFFIX), objectMapper.writeValueAsBytes(marker));
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to record the process launch marker", e);
        }
    }

    /** Writes and fsyncs the gate decision that lets the launcher execute or exit. */
    void writeGate(String launchId, String decision)
    {
        try {
            replace(gateFile(launchId), (decision + "\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to write the process launch gate", e);
        }
    }

    /**
     * Returns whether a gate durably released its payload.
     *
     * <p>Only an absent gate, or one whose first line is an explicit cancellation, proves that the
     * payload was never executed. Every other gate, including an unreadable or malformed one, is
     * reported as released; the launcher reads only the first line, so recovery must never conclude
     * "unlaunched" from content the launcher might have accepted.
     *
     * @param launchId launch to inspect
     * @return false only for an absent or explicitly cancelled gate
     */
    boolean gateReleased(String launchId)
    {
        try {
            String decision = Files.readString(gateFile(launchId), StandardCharsets.UTF_8).lines()
                    .findFirst()
                    .orElse("")
                    .trim();
            return !decision.equals(CANCEL);
        }
        catch (NoSuchFileException e) {
            return false;
        }
        catch (IOException e) {
            return true;
        }
    }

    List<String> list()
    {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(MARKER_SUFFIX))
                    .map(name -> name.substring(0, name.length() - MARKER_SUFFIX.length()))
                    .collect(ImmutableList.toImmutableList());
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to list process launch markers", e);
        }
    }

    /**
     * Reads one marker.
     *
     * @param launchId launch to read
     * @return the marker, or empty when it is absent, truncated, or otherwise undecodable
     */
    Optional<LaunchMarker> read(String launchId)
    {
        try {
            return Optional.of(objectMapper.readValue(
                    Files.readAllBytes(directory.resolve(launchId + MARKER_SUFFIX)),
                    LaunchMarker.class));
        }
        catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Deletes the marker, gate, and identity handoff of one launch, ignoring absent files. */
    void delete(String launchId)
    {
        for (String suffix : ImmutableList.of(MARKER_SUFFIX, GATE_SUFFIX, IDENTITY_SUFFIX)) {
            try {
                Files.deleteIfExists(directory.resolve(launchId + suffix));
            }
            catch (IOException e) {
                throw new AgentUnavailableException("Unable to delete the process launch marker", e);
            }
        }
        try {
            syncDirectory();
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to flush the process launch directory", e);
        }
    }

    private void replace(Path target, byte[] content)
            throws IOException
    {
        Path staged = Path.of(target + ".part");
        try (FileChannel channel = FileChannel.open(
                staged,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                checkState(channel.write(buffer) > 0, "launch marker write made no progress");
            }
            channel.force(true);
        }
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory();
    }

    /**
     * Flushes the directory entry so a rename survives a crash.
     *
     * <p>Recovery classifies a launch by whether its marker and gate files exist, so a directory
     * that cannot be flushed breaks the durability contract and must fail rather than be ignored.
     */
    private void syncDirectory()
            throws IOException
    {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
