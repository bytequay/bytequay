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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestTestRunnerDetector
{
    private final TestRunnerDetector detector = new TestRunnerDetector();

    @Test
    void detectsMavenFromPomXml(@TempDir Path worktree)
            throws IOException
    {
        Files.writeString(worktree.resolve("pom.xml"), "<project/>");

        Optional<TestRunnerDetector.Detected> detected = detector.detect(worktree);

        assertThat(detected).isPresent();
        assertThat(detected.get().ecosystem()).isEqualTo("maven");
        assertThat(detected.get().argv()).containsExactly("mvn", "-q", "verify");
    }

    @Test
    void detectsGradleFromBuildGradleKts(@TempDir Path worktree)
            throws IOException
    {
        Files.writeString(worktree.resolve("build.gradle.kts"), "");

        Optional<TestRunnerDetector.Detected> detected = detector.detect(worktree);

        assertThat(detected).isPresent();
        assertThat(detected.get().ecosystem()).isEqualTo("gradle");
        assertThat(detected.get().argv()).containsExactly("./gradlew", "test");
    }

    @Test
    void detectsNpmFromPackageJson(@TempDir Path worktree)
            throws IOException
    {
        Files.writeString(worktree.resolve("package.json"), "{}");

        Optional<TestRunnerDetector.Detected> detected = detector.detect(worktree);

        assertThat(detected).isPresent();
        assertThat(detected.get().ecosystem()).isEqualTo("npm");
        assertThat(detected.get().argv()).containsExactly("npm", "test", "--silent");
    }

    @Test
    void detectsCargoGoAndPytest(@TempDir Path worktree)
            throws IOException
    {
        // One marker at a time on its own temp dir — TempDir gives a
        // fresh path per parameter.
        Path cargoDir = Files.createTempDirectory(worktree, "cargo-");
        Files.writeString(cargoDir.resolve("Cargo.toml"), "");
        assertThat(detector.detect(cargoDir))
                .map(TestRunnerDetector.Detected::ecosystem).contains("cargo");

        Path goDir = Files.createTempDirectory(worktree, "go-");
        Files.writeString(goDir.resolve("go.mod"), "");
        assertThat(detector.detect(goDir))
                .map(TestRunnerDetector.Detected::ecosystem).contains("go");

        Path pyDir = Files.createTempDirectory(worktree, "py-");
        Files.writeString(pyDir.resolve("pyproject.toml"), "");
        assertThat(detector.detect(pyDir))
                .map(TestRunnerDetector.Detected::ecosystem).contains("pytest");
    }

    @Test
    void mavenWinsOverNpmWhenBothPresent(@TempDir Path worktree)
            throws IOException
    {
        // Polyglot repos hit Maven first because the JVM stack is more
        // expensive to ignore than the JS test suite.
        Files.writeString(worktree.resolve("pom.xml"), "<project/>");
        Files.writeString(worktree.resolve("package.json"), "{}");

        Optional<TestRunnerDetector.Detected> detected = detector.detect(worktree);

        assertThat(detected).isPresent();
        assertThat(detected.get().ecosystem()).isEqualTo("maven");
    }

    @Test
    void returnsEmptyWhenNoMarkerPresent(@TempDir Path worktree)
    {
        Optional<TestRunnerDetector.Detected> detected = detector.detect(worktree);

        assertThat(detected).isEmpty();
    }

    @Test
    void returnsEmptyForNullOrMissingDir()
    {
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect(Path.of("/nope/nonexistent-dir-1234"))).isEmpty();
    }
}
