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
package com.bytequay.agent.cli.process;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fixed local build caches that agent turns may populate without broadening the writable home directory.
 *
 * <p>Settings files and worktree scripts cannot choose these roots. Linked cache roots are omitted rather than
 * granting writes to an unexpected location.
 */
public final class BuildCacheRoots
{
    private static final List<String> ROOTS = ImmutableList.of(".m2", ".gradle", ".npm");

    private BuildCacheRoots() {}

    /**
     * @param environment the driver environment, whose {@code HOME} is used
     * @return existing or newly created fixed cache directories beneath the resolved home
     */
    public static List<Path> writableRoots(Map<String, String> environment)
    {
        try {
            Path home = Path.of(environment.getOrDefault("HOME", ""));
            if (!home.isAbsolute()) {
                return ImmutableList.of();
            }
            Path realHome = home.toRealPath();
            ImmutableList.Builder<Path> roots = ImmutableList.builder();
            for (String relative : ROOTS) {
                directory(realHome.resolve(relative)).ifPresent(roots::add);
            }
            return roots.build();
        }
        catch (IOException | InvalidPathException e) {
            return ImmutableList.of();
        }
    }

    private static Optional<Path> directory(Path path)
    {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(path);
                }
                catch (FileAlreadyExistsException concurrentCreation) {
                    // Another task may have created this fixed cache while this one was starting.
                }
            }
            return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path)
                    ? Optional.of(path) : Optional.empty();
        }
        catch (IOException failure) {
            return Optional.empty();
        }
    }
}
