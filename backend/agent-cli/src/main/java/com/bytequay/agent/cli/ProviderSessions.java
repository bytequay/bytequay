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
package com.bytequay.agent.cli;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The sweep that bounds a CLI provider's session transcripts.
 *
 * <p>Both lanes keep one transcript per thread forever and neither prunes it: Codex partitions them
 * by day, Claude by the path a session ran in. Only the shape of the tree differs, so the rule is
 * shared — a directory goes once every file in it predates the cutoff. File times decide and
 * directory names never do, because a thread resumed long after it started still appends to the
 * transcript it opened on its first day, and a thread ByteQuay may still resume has to survive.
 */
public final class ProviderSessions
{
    private ProviderSessions() {}

    /**
     * Deletes the directories under a transcript tree that nothing has written to since a cutoff.
     *
     * @param root the tree holding the transcripts, which need not exist yet
     * @param depth how far below {@code root} the deletable directories sit
     * @param deleteBefore the instant every file in a directory must predate for it to be deleted
     * @return how many directories were deleted
     * @throws IOException when the tree cannot be read or a directory cannot be deleted
     */
    public static int prune(Path root, int depth, Instant deleteBefore)
            throws IOException
    {
        requireNonNull(root, "root is null");
        checkArgument(depth >= 1, "depth must be positive");
        requireNonNull(deleteBefore, "deleteBefore is null");
        if (Files.notExists(root)) {
            return 0;
        }
        List<Path> candidates;
        // Collected before anything is deleted: the walk must not observe the tree it is changing.
        try (Stream<Path> tree = Files.walk(root, depth)) {
            candidates = tree.filter(Files::isDirectory)
                    .filter(path -> root.relativize(path).getNameCount() == depth)
                    .toList();
        }
        int deleted = 0;
        for (Path candidate : candidates) {
            if (expired(candidate, deleteBefore)) {
                // macOS has no secure directory stream, so the insecure walk is the only option here.
                MoreFiles.deleteRecursively(candidate, RecursiveDeleteOption.ALLOW_INSECURE);
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean expired(Path directory, Instant deleteBefore)
            throws IOException
    {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.allMatch(entry -> modifiedBefore(entry, deleteBefore));
        }
    }

    private static boolean modifiedBefore(Path entry, Instant deleteBefore)
    {
        try {
            return Files.getLastModifiedTime(entry).toInstant().isBefore(deleteBefore);
        }
        catch (IOException unreadable) {
            // Its age cannot be established, so the directory it sits in stays.
            return false;
        }
    }
}
