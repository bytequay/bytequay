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
package com.bytequay.app.cherrypick;

import com.bytequay.app.service.github.BoundedProcessRunner;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Plans and runs the local build that decides whether a resolution is
 * acceptable.
 *
 * <p>ByteQuay runs this itself. An agent saying compilation passed is not
 * evidence that it did.
 */
@Component
class CherryPickValidation
{
    private static final long TIMEOUT_SECONDS = 30 * 60;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final String WRAPPER = "./mvnw";

    private final BoundedProcessRunner processes;

    CherryPickValidation(BoundedProcessRunner processes)
    {
        this.processes = requireNonNull(processes, "processes is null");
    }

    /**
     * Plans a build scoped to the modules a change touched.
     *
     * @param worktree worktree the commit lives in
     * @param changedPaths every path the verified commit and its conflicts touched
     * @return the command to run, or empty when none can be formed safely
     */
    Optional<Plan> plan(Path worktree, List<String> changedPaths)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(changedPaths, "changedPaths is null");
        if (!Files.isRegularFile(worktree.resolve("mvnw"))) {
            return Optional.empty();
        }
        Set<String> modules = new LinkedHashSet<>();
        for (String path : changedPaths) {
            Optional<String> module = module(worktree, path);
            if (module.isEmpty()) {
                // A change outside every module cannot be isolated, so build the
                // whole project rather than silently checking less than it.
                return Optional.of(new Plan(worktree, ImmutableList.of(WRAPPER, "-DskipTests", "package")));
            }
            modules.add(module.orElseThrow());
        }
        if (modules.isEmpty()) {
            return Optional.of(new Plan(worktree, ImmutableList.of(WRAPPER, "-DskipTests", "package")));
        }
        return Optional.of(new Plan(worktree, ImmutableList.of(
                WRAPPER, "-pl", String.join(",", modules), "-am", "-DskipTests", "package")));
    }

    /**
     * Runs one planned build.
     *
     * @param plan stored command
     * @return whether it passed, with bounded output either way
     */
    Outcome run(Plan plan)
    {
        requireNonNull(plan, "plan is null");
        try {
            BoundedProcessRunner.Result result = processes.run(
                    plan.workingDirectory(), plan.argv(), TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
            return new Outcome(result.exitCode() == 0, result.output());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, "Validation was interrupted");
        }
    }

    private static Optional<String> module(Path worktree, String path)
    {
        Path directory = worktree.resolve(path).getParent();
        while (directory != null && directory.startsWith(worktree) && !directory.equals(worktree)) {
            if (Files.isRegularFile(directory.resolve("pom.xml"))) {
                return Optional.of(worktree.relativize(directory).toString());
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    record Plan(Path workingDirectory, List<String> argv)
    {
        Plan
        {
            requireNonNull(workingDirectory, "workingDirectory is null");
            argv = ImmutableList.copyOf(requireNonNull(argv, "argv is null"));
        }

        String describe()
        {
            return String.join(" ", argv);
        }
    }

    record Outcome(boolean passed, String output)
    {
        Outcome
        {
            requireNonNull(output, "output is null");
        }

        String bounded()
        {
            return output.length() <= 8 * 1024 ? output : output.substring(output.length() - 8 * 1024);
        }
    }
}
