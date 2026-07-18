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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestWorkspaceDocumentLoader
{
    @TempDir
    private Path tempDir;

    @Test
    void readsOnlyTheExactByteQuayWorkspaceDocument()
            throws IOException
    {
        Files.writeString(tempDir.resolve("WORKSPACE.md"), "# Architecture\n\nUse the scheduler.");
        Files.writeString(tempDir.resolve("AGENTS.md"), "provider-specific instructions");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "provider-specific instructions");

        assertThat(WorkspaceDocumentLoader.load(tempDir.toString()))
                .isEqualTo("# Architecture\n\nUse the scheduler.")
                .doesNotContain("provider-specific");
    }

    @Test
    void missingOrSymlinkedWorkspaceDocumentIsIgnored()
            throws IOException
    {
        assertThat(WorkspaceDocumentLoader.load(tempDir.toString())).isEmpty();

        Path target = tempDir.resolve("outside.md");
        Files.writeString(target, "do not follow");
        Files.createSymbolicLink(tempDir.resolve("WORKSPACE.md"), target);

        assertThat(WorkspaceDocumentLoader.load(tempDir.toString())).isEmpty();
    }

    @Test
    void oversizedWorkspaceDocumentIsTruncatedDeterministically()
            throws IOException
    {
        Files.writeString(tempDir.resolve("WORKSPACE.md"),
                "x".repeat(WorkspaceDocumentLoader.MAX_CHARS + 100));

        String loaded = WorkspaceDocumentLoader.load(tempDir.toString());

        assertThat(loaded)
                .startsWith("x".repeat(100))
                .endsWith("[WORKSPACE.md truncated by ByteQuay]")
                .hasSizeLessThan(WorkspaceDocumentLoader.MAX_CHARS + 100);
    }
}
