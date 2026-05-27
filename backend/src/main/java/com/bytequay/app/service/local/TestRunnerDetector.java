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
package com.bytequay.app.service.local;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * First-cut runner detection for the {@code run_checks} agent tool.
 * Probes the worktree root for the marker file of a known ecosystem
 * and returns the canonical "verify" command for it.
 *
 * <p>The work-model integration that's landing on a separate axis
 * will let the user override these defaults per workspace; until
 * then this gives a sensible baseline so the agent has a working
 * tool from day one.
 */
@Component
public class TestRunnerDetector
{
    /**
     * @param worktree directory to probe — typically the active
     *                 task's worktreePath
     * @return the argv to run, or empty when no ecosystem marker is
     *         present
     */
    public Optional<Detected> detect(Path worktree)
    {
        if (worktree == null || !Files.isDirectory(worktree)) {
            return Optional.empty();
        }
        if (Files.exists(worktree.resolve("pom.xml"))) {
            return Optional.of(new Detected("maven", List.of("mvn", "-q", "verify")));
        }
        if (Files.exists(worktree.resolve("build.gradle"))
                || Files.exists(worktree.resolve("build.gradle.kts"))) {
            return Optional.of(new Detected("gradle", List.of("./gradlew", "test")));
        }
        if (Files.exists(worktree.resolve("package.json"))) {
            return Optional.of(new Detected("npm", List.of("npm", "test", "--silent")));
        }
        if (Files.exists(worktree.resolve("Cargo.toml"))) {
            return Optional.of(new Detected("cargo", List.of("cargo", "test", "--quiet")));
        }
        if (Files.exists(worktree.resolve("go.mod"))) {
            return Optional.of(new Detected("go", List.of("go", "test", "./...")));
        }
        if (Files.exists(worktree.resolve("pyproject.toml"))
                || Files.exists(worktree.resolve("setup.py"))) {
            return Optional.of(new Detected("pytest", List.of("pytest", "-q")));
        }
        return Optional.empty();
    }

    /** Name of the detected ecosystem + the argv to run. */
    public record Detected(String ecosystem, List<String> argv) {}
}
