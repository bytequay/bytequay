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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static java.util.Objects.requireNonNull;

/**
 * ByteQuay's own Codex home.
 *
 * <p>Every Codex process ByteQuay starts runs with {@code CODEX_HOME} pointing here, so its login,
 * config, sessions and rules are ByteQuay's alone and nothing from the user's {@code ~/.codex}
 * (rules, hooks, plugins, MCP servers) reaches a task. The user signs this home in once with the
 * command readiness shows; ByteQuay never reads, copies or links the user's own credentials.
 */
public final class CodexHome
{
    private CodexHome() {}

    /**
     * Creates the home if it does not exist and keeps it owner-only, with an empty user config so
     * Codex has a user layer that grants nothing. A login already stored there is left alone.
     *
     * @param home the directory to prepare
     * @return the same path, for chaining into the driver
     * @throws IOException when the directory or its config cannot be created
     */
    public static Path prepare(Path home)
            throws IOException
    {
        requireNonNull(home, "home is null");
        Files.createDirectories(home);
        // Re-applied every start: the user may have created the directory by signing in first.
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        Path config = home.resolve("config.toml");
        if (Files.notExists(config)) {
            Files.createFile(config);
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        }
        return home;
    }
}
