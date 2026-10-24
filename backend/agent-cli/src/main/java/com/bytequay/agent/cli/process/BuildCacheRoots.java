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

import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Fixed local build caches that agent turns may populate without broadening the writable home directory.
 *
 * <p>Settings files and worktree scripts cannot choose these roots. Linked cache roots are omitted rather than
 * granting writes to an unexpected location.
 */
public final class BuildCacheRoots
{
    private static final List<String> ROOTS = ImmutableList.of(".m2", ".gradle", ".npm");
    private static final Map<String, List<String>> BUILD_CONFIGURATION = ImmutableMap.of(
            ".m2", ImmutableList.of("settings.xml", "settings-security.xml", "toolchains.xml", "extensions.xml"),
            ".gradle", ImmutableList.of("init.gradle", "init.gradle.kts", "init.d", "gradle.properties"));

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
            return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path) ? Optional.of(path) : Optional.empty();
        }
        catch (IOException failure) {
            return Optional.empty();
        }
    }
    // Restricted sessions cannot seed caches or temporary scripts that later builds might execute.
    public static List<Path> buildCaches(AgentPermissionScope scope, Map<String, String> environment)
    {
        return scope.access().restricted() ? ImmutableList.of() : writableRoots(environment);
    }

    /**
     * Files in the caches that make later builds run code or download from elsewhere. A cherry-pick session builds with the
     * caches but is otherwise contained, and later development builds run Maven outside the sandbox.
     *
     * <p>ponytail: artifacts in the shared repository stay writable, since an offline build must install them; a per-session
     * repository would close that too.
     */
    public static List<Path> protectedConfiguration(AgentPermissionScope scope, Map<String, String> environment)
    {
        if (scope.policy() != AgentPermissionPolicy.CHERRY_PICK) {
            return ImmutableList.of();
        }
        return buildCaches(scope, environment).stream()
                .flatMap(root -> BUILD_CONFIGURATION.getOrDefault(root.getFileName().toString(), ImmutableList.of()).stream().map(root::resolve))
                .collect(toImmutableList());
    }

    public static List<Path> temporary(AgentPermissionScope scope, Map<String, String> environment)
    {
        return scope.access().restricted() ? ImmutableList.of() : temporary(environment);
    }
    public static List<Path> temporary(Map<String, String> environment)
    {
        ImmutableSet.Builder<Path> roots = ImmutableSet.builder();
        for (String candidate : ImmutableList.of("/tmp", "/var/tmp", System.getProperty("java.io.tmpdir", ""), environment.getOrDefault("TMPDIR", ""))) {
            try {
                Path path = Path.of(candidate);
                if (path.isAbsolute() && Files.isDirectory(path)) {
                    roots.add(path.toRealPath());
                }
            }
            catch (IOException | InvalidPathException ignored) {
                // A missing or malformed temporary root must not grant a different location.
            }
        }
        return roots.build().asList();
    }}
