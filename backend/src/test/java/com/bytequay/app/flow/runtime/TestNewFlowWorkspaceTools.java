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
package com.bytequay.app.flow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestNewFlowWorkspaceTools
{
    @TempDir
    private Path worktree;

    @Test
    void replacesOrDeletesOnlyTheRequestedWorktreeLines()
            throws Exception
    {
        Path file = worktree.resolve("pom.xml");
        Files.writeString(file, "one\ntwo\n<<<<<<<\nold\n=======\nnew\n>>>>>>>\nlast\n");
        NewFlowWorkspaceTools tools = new NewFlowWorkspaceTools(worktree);

        tools.replaceFileLines("pom.xml", 3, 7, "resolved");

        assertThat(Files.readString(file))
                .isEqualTo("one\ntwo\nresolved\nlast\n");
        assertThatThrownBy(() -> tools.replaceFileLines(
                "../outside", 1, 1, "no"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.replaceFileLines(
                "pom.xml", 2, 99, "no"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
