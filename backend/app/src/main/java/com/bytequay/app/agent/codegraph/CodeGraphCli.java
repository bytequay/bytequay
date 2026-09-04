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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.CodeGraphException;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Runs the vendored CodeGraph bundle as a short-lived, sandboxed argv process.
 *
 * <p>The bundle's own Node runtime executes the CLI script directly, bypassing the shell launcher
 * and the npm shim, so no shell, installer, or download path is reachable. Every process gets a
 * fixed environment: telemetry, update checks, downloads, the file watcher, and the daemon are off,
 * {@code HOME} points at task-owned scratch, and the index lives wherever the caller points
 * {@code CODEGRAPH_DATA_DIR}, which the vendored patch makes upstream honor.
 *
 * <p>The process runs under a macOS sandbox profile that denies the network and every file write
 * except the index directory and that scratch home, and denies every read below the protected
 * roots except the source it indexes, that source's git directory, and its own index. Other
 * tasks' runtimes, other worktrees, task logs, and provider files stay unreadable.
 */
public class CodeGraphCli
{
    static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");
    private static final Logger LOG = LoggerFactory.getLogger(CodeGraphCli.class);
    private static final String MARKER = "BYTEQUAY_BUNDLE";
    private static final String NODE = "node";
    private static final String SCRIPT = "lib/dist/bin/codegraph.js";
    private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;

    private final Optional<Path> bundle;
    private final GitCommandRunner runner;
    private final List<Path> protectedRoots;

    public CodeGraphCli(Optional<Path> home, GitCommandRunner runner)
    {
        this(home, runner, ImmutableList.of());
    }

    /**
     * @param protectedRoots directories the helper may not read except for the task's own source,
     *        index, and scratch beneath them: the agent runtime root and the workspace root
     */
    public CodeGraphCli(Optional<Path> home, GitCommandRunner runner, List<Path> protectedRoots)
    {
        requireNonNull(home, "home is null");
        this.runner = requireNonNull(runner, "runner is null");
        this.protectedRoots = protectedRoots.stream().map(CodeGraphCli::realOrNormalized).collect(ImmutableList.toImmutableList());
        this.bundle = home.flatMap(CodeGraphCli::verify);
    }

    public boolean available()
    {
        return bundle.isPresent();
    }

    /**
     * Runs one CLI command in the source directory.
     *
     * @throws CodeGraphException when no bundle or sandbox is installed or the process was interrupted
     */
    Result run(Path workingDirectory, Path indexDirectory, Path scratchHome, Duration timeout, List<String> arguments)
    {
        requireNonNull(workingDirectory, "workingDirectory is null");
        requireNonNull(indexDirectory, "indexDirectory is null");
        requireNonNull(scratchHome, "scratchHome is null");
        requireNonNull(timeout, "timeout is null");
        requireNonNull(arguments, "arguments is null");
        Path home = bundle.orElseThrow(() -> new CodeGraphException("CodeGraph is not installed"));
        if (!Files.isExecutable(SANDBOX_EXEC)) {
            throw new CodeGraphException("CodeGraph needs " + SANDBOX_EXEC + " to confine its helper");
        }
        Path source;
        Path index;
        Path scratch;
        try {
            // The sandbox matches real paths, so a temporary directory under /var must be named /private/var.
            Files.createDirectories(indexDirectory);
            Files.createDirectories(scratchHome);
            source = workingDirectory.toRealPath();
            index = indexDirectory.toRealPath();
            scratch = scratchHome.toRealPath();
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot resolve the CodeGraph directories: " + e.getMessage(), e);
        }
        String profile = sandboxProfile(source, gitCommonDirectory(source), index, scratch, protectedRoots);
        List<String> command = ImmutableList.<String>builderWithExpectedSize(arguments.size() + 7)
                .add(SANDBOX_EXEC.toString(), "-p", profile)
                // --liftoff-only is upstream's own launcher flag: it avoids a V8 WASM zone OOM while parsing.
                .add(home.resolve(NODE).toString(), "--liftoff-only", "--disable-warning=ExperimentalWarning", home.resolve(SCRIPT).toString())
                .addAll(arguments)
                .build();
        Map<String, String> environment = ImmutableMap.<String, String>builder()
                .put("HOME", scratch.toString())
                .put("CODEGRAPH_DATA_DIR", index.toString())
                .put("DO_NOT_TRACK", "1")
                .put("CODEGRAPH_NO_UPDATE_CHECK", "1")
                .put("CODEGRAPH_NO_DOWNLOAD", "1")
                .put("CODEGRAPH_NO_WATCH", "1")
                .put("CODEGRAPH_NO_DAEMON", "1")
                .put("NO_COLOR", "1")
                .buildOrThrow();
        try {
            return runner.runCommand(source, environment, command, timeout, MAXIMUM_OUTPUT_BYTES);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeGraphException("CodeGraph was interrupted", e);
        }
    }

    /**
     * The sandbox profile for one run. Later rules win, so the denials come first and the task's
     * own directories are re-allowed after them, together with the metadata of every ancestor a
     * path lookup has to traverse below a protected root.
     */
    static String sandboxProfile(Path source, Optional<Path> gitCommonDirectory, Path index, Path scratch, List<Path> protectedRoots)
    {
        StringBuilder profile = new StringBuilder("(version 1)\n(allow default)\n(deny network*)\n(deny file-write*)\n");
        profile.append("(allow file-write* ").append(subpath(index)).append(' ').append(subpath(scratch)).append(" (literal \"/dev/null\"))\n");
        Set<Path> readable = new LinkedHashSet<>();
        readable.add(source);
        gitCommonDirectory.ifPresent(readable::add);
        readable.add(index);
        readable.add(scratch);
        Set<Path> traversable = new LinkedHashSet<>();
        for (Path root : protectedRoots) {
            profile.append("(deny file-read* ").append(subpath(root)).append(")\n");
            for (Path directory : readable) {
                for (Path ancestor = directory.getParent(); ancestor != null && ancestor.startsWith(root); ancestor = ancestor.getParent()) {
                    traversable.add(ancestor);
                }
            }
        }
        if (!traversable.isEmpty()) {
            profile.append("(allow file-read-metadata ").append(traversable.stream().map(CodeGraphCli::literal).collect(joining(" "))).append(")\n");
        }
        profile.append("(allow file-read* ").append(readable.stream().map(CodeGraphCli::subpath).collect(joining(" "))).append(")\n");
        return profile.toString();
    }

    private Optional<Path> gitCommonDirectory(Path source)
    {
        Result result = runner.run(source, "rev-parse", "--path-format=absolute", "--git-common-dir");
        if (!result.successful() || result.truncated() || result.output().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(realOrNormalized(Path.of(result.output().strip())));
    }

    /** A protected root or git directory may not exist yet; the sandbox needs the real path when it does. */
    private static Path realOrNormalized(Path path)
    {
        try {
            return path.toRealPath();
        }
        catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String subpath(Path path)
    {
        return "(subpath " + quote(path) + ")";
    }

    private static String literal(Path path)
    {
        return "(literal " + quote(path) + ")";
    }

    private static String quote(Path path)
    {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Optional<Path> verify(Path home)
    {
        Path normalized = home.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized.resolve(MARKER)) && Files.isExecutable(normalized.resolve(NODE)) && Files.isRegularFile(normalized.resolve(SCRIPT))) {
            return Optional.of(normalized);
        }
        LOG.warn("CodeGraph bundle at {} is missing or incomplete; agent tasks run without the code graph", normalized);
        return Optional.empty();
    }
}
