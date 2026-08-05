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
package com.bytequay.app.service.workspaces;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run learns how to compile by reading the project's own CI config. It is a
 * parser rather than a question for a model because the answer is executed.
 */
class TestCiJobScriptReader
{
    @Test
    void takesTheFirstPlainBuildInvocationFromTheWorkflows(@TempDir Path root)
            throws IOException
    {
        workflow(root, "ci.yml", """
                name: CI
                jobs:
                  test:
                    steps:
                      - uses: actions/checkout@v4
                      - name: Build
                        run: |
                          echo "warming the cache"
                          ./mvnw -B clean install -DskipTests
                """);

        assertThat(CiJobScriptReader.anyBuildScript(root))
                .contains("./mvnw -B clean install -DskipTests");
    }

    @Test
    void ignoresAnythingThatIsNotABareBuildCommand(@TempDir Path root)
            throws IOException
    {
        // A pipeline, a redirect and a wrapper script all get skipped rather
        // than executed — the caller falls back to a plain compile.
        workflow(root, "ci.yml", """
                jobs:
                  test:
                    steps:
                      - run: ./mvnw verify | tee build.log
                      - run: make build && ./mvnw test
                      - run: ./scripts/build.sh
                """);

        assertThat(CiJobScriptReader.anyBuildScript(root)).isEmpty();
    }

    @Test
    void readsWorkflowsInNameOrderSoTheChoiceIsStable(@TempDir Path root)
            throws IOException
    {
        workflow(root, "b-release.yml", "jobs:\n  x:\n    steps:\n      - run: mvn deploy\n");
        workflow(root, "a-ci.yml", "jobs:\n  x:\n    steps:\n      - run: mvn -q compile\n");

        assertThat(CiJobScriptReader.anyBuildScript(root)).contains("mvn -q compile");
    }

    @Test
    void aRepositoryWithoutWorkflowsSaysSoRatherThanGuessing(@TempDir Path root)
    {
        assertThat(CiJobScriptReader.anyBuildScript(root)).isEmpty();
    }

    @Test
    void aNamedJobStillWinsWhenTheCallerKnowsWhichOne(@TempDir Path root)
            throws IOException
    {
        workflow(root, "ci.yml", """
                jobs:
                  quick:
                    steps:
                      - run: mvn -q compile
                  full:
                    name: Full build
                    steps:
                      - run: ./mvnw clean verify
                """);

        assertThat(CiJobScriptReader.buildScript(root, "Full build"))
                .contains("./mvnw clean verify");
        assertThat(CiJobScriptReader.buildScript(root, "nope")).isEmpty();
    }

    private static void workflow(Path root, String name, String body)
            throws IOException
    {
        Path dir = root.resolve(".github/workflows");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
    }
}
