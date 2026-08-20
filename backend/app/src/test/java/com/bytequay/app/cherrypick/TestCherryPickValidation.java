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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestCherryPickValidation
{
    @TempDir
    private Path worktree;

    private CherryPickValidation validation;

    @BeforeEach
    void setUp()
    {
        validation = new CherryPickValidation(new BoundedProcessRunner());
    }

    @Test
    void plansNothingWithoutACheckedInWrapper()
            throws IOException
    {
        Files.writeString(worktree.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);

        assertThat(validation.plan(worktree, ImmutableList.of("src/Main.java")))
                .as("ByteQuay must not claim a resolution is verified when it cannot verify one")
                .isEmpty();
    }

    @Test
    void scopesTheBuildToTheModulesAChangeTouched()
            throws IOException
    {
        wrapper();
        module("core");
        module("plugin/hive");

        assertThat(validation.plan(worktree, ImmutableList.of(
                "core/src/Main.java", "plugin/hive/src/Hive.java", "core/src/Other.java"))
                .orElseThrow()
                .argv())
                .containsExactly("./mvnw", "-pl", "core,plugin/hive", "-am", "-DskipTests", "package");
    }

    @Test
    void buildsTheWholeProjectWhenAChangeCannotBeIsolated()
            throws IOException
    {
        wrapper();
        module("core");

        assertThat(validation.plan(worktree, ImmutableList.of("core/src/Main.java", "README.md"))
                .orElseThrow()
                .argv())
                .as("a root-level change is not silently checked as less than it is")
                .containsExactly("./mvnw", "-DskipTests", "package");
    }

    @Test
    void reportsTheExitCodeAndKeepsTheOutput()
            throws IOException
    {
        Path script = worktree.resolve("mvnw");
        Files.writeString(script, "#!/bin/sh\necho 'compilation failure: Main.java'\nexit 1\n", StandardCharsets.UTF_8);
        assertThat(script.toFile().setExecutable(true)).isTrue();

        CherryPickValidation.Outcome outcome = validation.run(
                new CherryPickValidation.Plan(worktree, ImmutableList.of("./mvnw", "-DskipTests", "package")));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.bounded()).contains("compilation failure");
    }

    private void wrapper()
            throws IOException
    {
        Files.writeString(worktree.resolve("mvnw"), "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
    }

    private void module(String name)
            throws IOException
    {
        Path directory = Files.createDirectories(worktree.resolve(name));
        Files.writeString(directory.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        Files.createDirectories(directory.resolve("src"));
    }
}
