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
package com.bytequay.app.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.bytequay.app.utils.MavenModules.findModule;
import static org.assertj.core.api.Assertions.assertThat;

class TestMavenModules
{
    @TempDir
    private Path worktree;

    @Test
    void findsTheNearestNestedModule()
            throws IOException
    {
        Files.createDirectories(worktree.resolve("module/nested/src"));
        Files.writeString(worktree.resolve("module/pom.xml"), "<project/>");
        Files.writeString(worktree.resolve("module/nested/pom.xml"), "<project/>");

        assertThat(findModule(worktree, "module/nested/src/Example.java")).contains("module/nested");
        assertThat(findModule(worktree, "module/Other.java")).contains("module");
    }

    @Test
    void excludesTheWorktreeRoot()
            throws IOException
    {
        Files.writeString(worktree.resolve("pom.xml"), "<project/>");

        assertThat(findModule(worktree, "Main.java")).isEmpty();
        assertThat(findModule(worktree, "src/Main.java")).isEmpty();
    }
}
