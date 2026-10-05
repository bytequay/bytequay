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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.requireNonNull;

/** The one review hook ByteQuay permits inside its isolated Codex home. */
final class CodexReviewGate
{
    static final String INSTRUCTION = """
            In this Codex review, children may use review_get_context, codegraph_explore and repository_memory SEARCH/READ
            when listed, native shell tools for read-only source searches, and send_message to share findings with the main agent.
            Use the MCP repository_memory tool for reads; the unqualified dynamic tool belongs to the main agent.
            Shell commands retain the native sandbox. Children must not edit files, run tests, commit, publish, save review
            results, REPORT or REMEMBER repository memory, request broader permissions, or delegate further work.
            Include this exploration scope in each child's self-contained assignment. Return conclusions, source references
            and missing evidence in your final answer; it is also delivered to the main agent automatically.
            If a call is denied, report the limitation instead of retrying it or working around the restriction.
            """;
    private static final String SCRIPT = readScript();
    private static final String FILE_NAME = "bytequay-review-child-gate.sh";

    private CodexReviewGate() {}

    static Path path(Path codexHome)
    {
        return requireNonNull(codexHome, "codexHome is null").resolve(FILE_NAME);
    }

    static String command(Path gate)
    {
        return "'" + requireNonNull(gate, "gate is null").toString().replace("'", "'\\''") + "'";
    }

    static Path install(Path codexHome)
    {
        Path target = path(codexHome);
        Path staged = null;
        try {
            staged = Files.createTempFile(codexHome, ".bytequay-review-gate-", ".sh");
            Files.writeString(staged, SCRIPT, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwx------"));
            Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING);
            return target;
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to install Codex review child gate", e);
        }
        finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                }
                catch (IOException ignored) {
                    // The staged path is owner-only and never passed to Codex.
                }
            }
        }
    }

    static void verify(Path codexHome)
    {
        Path gate = path(codexHome);
        try {
            if (!Files.isRegularFile(gate, NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(gate, NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))
                    || !Files.readString(gate, StandardCharsets.UTF_8).equals(SCRIPT)) {
                throw new AgentUnavailableException("Codex review child gate changed after installation");
            }
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to verify Codex review child gate", e);
        }
    }

    private static String readScript()
    {
        try (InputStream resource = CodexReviewGate.class.getResourceAsStream("review-child-gate.sh")) {
            return new String(requireNonNull(resource, "Codex review child gate resource is missing").readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to read Codex review child gate resource", e);
        }
    }
}
