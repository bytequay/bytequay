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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Runs the vendored CodeGraph bundle as a short-lived argv process.
 *
 * <p>The bundle's own Node runtime executes the CLI script directly, bypassing the shell launcher
 * and the npm shim, so no shell, installer, or download path is reachable. Every process gets a
 * fixed environment: telemetry, update checks, downloads, the file watcher, and the daemon are off,
 * {@code HOME} points at task-owned scratch, and the index lives wherever the caller points
 * {@code CODEGRAPH_DATA_DIR}, which the vendored patch makes upstream honor.
 */
public final class CodeGraphCli
{
    private static final Logger LOG = LoggerFactory.getLogger(CodeGraphCli.class);
    private static final String MARKER = "BYTEQUAY_BUNDLE";
    private static final String NODE = "node";
    private static final String SCRIPT = "lib/dist/bin/codegraph.js";
    private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;

    private final Optional<Path> bundle;
    private final GitCommandRunner runner;

    public CodeGraphCli(Optional<Path> home, GitCommandRunner runner)
    {
        requireNonNull(home, "home is null");
        this.runner = requireNonNull(runner, "runner is null");
        this.bundle = home.flatMap(CodeGraphCli::verify);
    }

    public boolean available()
    {
        return bundle.isPresent();
    }

    /**
     * Runs one CLI command in the worktree.
     *
     * @throws CodeGraphException when no bundle is installed or the process was interrupted
     */
    Result run(Path workingDirectory, Path indexDirectory, Path scratchHome, Duration timeout, List<String> arguments)
    {
        requireNonNull(workingDirectory, "workingDirectory is null");
        requireNonNull(indexDirectory, "indexDirectory is null");
        requireNonNull(scratchHome, "scratchHome is null");
        requireNonNull(timeout, "timeout is null");
        requireNonNull(arguments, "arguments is null");
        Path home = bundle.orElseThrow(() -> new CodeGraphException("CodeGraph is not installed"));
        List<String> command = ImmutableList.<String>builderWithExpectedSize(arguments.size() + 4)
                // --liftoff-only is upstream's own launcher flag: it avoids a V8 WASM zone OOM while parsing.
                .add(home.resolve(NODE).toString(), "--liftoff-only", "--disable-warning=ExperimentalWarning", home.resolve(SCRIPT).toString())
                .addAll(arguments)
                .build();
        Map<String, String> environment = ImmutableMap.<String, String>builder()
                .put("HOME", scratchHome.toString())
                .put("CODEGRAPH_DATA_DIR", indexDirectory.toString())
                .put("DO_NOT_TRACK", "1")
                .put("CODEGRAPH_NO_UPDATE_CHECK", "1")
                .put("CODEGRAPH_NO_DOWNLOAD", "1")
                .put("CODEGRAPH_NO_WATCH", "1")
                .put("CODEGRAPH_NO_DAEMON", "1")
                .put("NO_COLOR", "1")
                .buildOrThrow();
        try {
            Files.createDirectories(scratchHome);
            return runner.runCommand(workingDirectory, environment, command, timeout, MAXIMUM_OUTPUT_BYTES);
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot create the CodeGraph scratch home: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeGraphException("CodeGraph was interrupted", e);
        }
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
