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
package com.bytequay.agent.cli.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexHome
{
    @TempDir
    private Path temporaryDirectory;

    @Test
    void createsAnOwnerOnlyHomeWithAnEmptyUserConfig()
            throws IOException
    {
        Path home = temporaryDirectory.resolve("codex-home");

        assertThat(CodexHome.prepare(home)).isEqualTo(home);

        // Nothing but the owner may read the login that will be stored here, and the user layer
        // exists so Codex loads it, but grants nothing.
        assertThat(Files.getPosixFilePermissions(home))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        assertThat(home.resolve("config.toml")).isEmptyFile();
        assertThat(Files.getPosixFilePermissions(home.resolve("config.toml")))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void leavesAnExistingLoginAloneAndTightensADirectoryTheUserCreated()
            throws IOException
    {
        Path home = temporaryDirectory.resolve("codex-home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("auth.json"), "{}");
        Files.writeString(home.resolve("config.toml"), "model = \"gpt-5\"\n");

        CodexHome.prepare(home);

        // Signing in first creates the directory with default permissions; every start re-tightens
        // it, but never touches what is already stored there.
        assertThat(Files.getPosixFilePermissions(home))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        assertThat(home.resolve("auth.json")).hasContent("{}");
        assertThat(home.resolve("config.toml")).hasContent("model = \"gpt-5\"\n");
    }
}
