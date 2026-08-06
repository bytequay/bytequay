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
package com.bytequay.app.service.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * How a worktree agent tells the program what it decided.
 *
 * <p>It writes a small JSON file; the program reads it. That replaces parsing a
 * marker line out of the turn's prose, which could not tell "the agent finished
 * and said so" apart from "the agent said something else", "the agent added a
 * closing pleasantry after the marker", or "the binary never ran". A file is
 * there or it is not.
 *
 * <p>The path is inside the worktree because that is the only place a
 * {@code workspace-write} sandbox can write. The directory is removed before and
 * after every turn, so a stale verdict can never be read as this turn's, and
 * nothing is left for a later pick to commit by accident.
 */
public final class AgentVerdictFile
{
    private static final Logger log = LoggerFactory.getLogger(AgentVerdictFile.class);
    private static final String DIR = ".bytequay";
    private static final String FILE = "verdict.json";
    private static final int MAX_SUMMARY = 500;

    private final ObjectMapper mapper;

    public AgentVerdictFile(ObjectMapper mapper)
    {
        this.mapper = mapper;
    }

    /** The path the agent is told to write, relative to the worktree. */
    public static String relativePath()
    {
        return DIR + "/" + FILE;
    }

    /**
     * Removes any verdict left over from an earlier turn. Called before a turn
     * so what is read afterwards can only be what this turn wrote.
     */
    public void clear(Path worktree)
    {
        Path dir = worktree.resolve(DIR);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException e) {
                    log.warn("could not clear {}: {}", path, e.getMessage());
                }
            });
        }
        catch (IOException e) {
            log.warn("could not clear the verdict directory: {}", e.getMessage());
        }
    }

    /** Empty when the agent wrote nothing usable — which is what a retry is for. */
    public Optional<Verdict> read(Path worktree)
    {
        Path file = worktree.resolve(DIR).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode json = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String status = json.path("status").asText("").strip().toLowerCase(Locale.ROOT);
            if (status.isEmpty()) {
                return Optional.empty();
            }
            String summary = json.path("summary").asText("").strip();
            return Optional.of(new Verdict(
                    status,
                    summary.length() <= MAX_SUMMARY
                            ? summary : summary.substring(0, MAX_SUMMARY) + "…"));
        }
        catch (IOException | RuntimeException unreadable) {
            // Half-written or not JSON at all. Treated the same as absent: the
            // agent is asked again rather than guessed at.
            log.info("agent verdict was unreadable: {}", unreadable.getMessage());
            return Optional.empty();
        }
    }

    public record Verdict(String status, String summary) {}
}
