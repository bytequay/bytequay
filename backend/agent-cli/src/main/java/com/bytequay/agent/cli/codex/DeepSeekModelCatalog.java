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

import com.bytequay.agent.spi.AgentUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import static java.util.Objects.requireNonNull;

/** ByteQuay-owned, text-only Codex metadata for the two supported DeepSeek models. */
final class DeepSeekModelCatalog
{
    private static final String RESOURCE = "deepseek-models.json";

    private DeepSeekModelCatalog() {}

    static void prepare(Path path)
    {
        requireNonNull(path, "path is null");
        Path temporary = null;
        try {
            verifyHome(path.getParent());
            CodexHome.prepare(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".deepseek-models-", ".json",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary, contents());
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to prepare the isolated DeepSeek model catalog", e);
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // The launch fails closed if the catalog was not installed.
                }
            }
        }
    }

    static void verify(Path path)
    {
        try {
            verifyHome(path.getParent());
            if (Files.isSymbolicLink(path) || !Arrays.equals(Files.readAllBytes(path), contents())) {
                throw new AgentUnavailableException("DeepSeek model catalog does not match ByteQuay's launch profile");
            }
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to verify the isolated DeepSeek model catalog", e);
        }
    }

    static void verifyHome(Path home)
    {
        requireNonNull(home, "home is null");
        Path candidate = home;
        for (int depth = 0; depth < 3 && candidate != null; depth++, candidate = candidate.getParent()) {
            if (Files.isSymbolicLink(candidate)) {
                throw new AgentUnavailableException("DeepSeek Codex home cannot use a symbolic link");
            }
        }
        if (Files.exists(home)) {
            if (!Files.isDirectory(home)) {
                throw new AgentUnavailableException("DeepSeek Codex home is not a directory");
            }
            Path config = home.resolve("config.toml");
            if (Files.isSymbolicLink(config)) {
                throw new AgentUnavailableException("DeepSeek Codex configuration cannot use a symbolic link");
            }
        }
    }

    private static byte[] contents()
            throws IOException
    {
        try (InputStream source = DeepSeekModelCatalog.class.getResourceAsStream(RESOURCE)) {
            if (source == null) {
                throw new IOException("DeepSeek model catalog resource is missing");
            }
            return source.readAllBytes();
        }
    }
}
