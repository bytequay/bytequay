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
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;

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

    @Test
    void deletesOnlyDayPartitionsNothingHasWrittenToSinceTheCutoff()
            throws IOException
    {
        Path home = temporaryDirectory.resolve("codex-home");
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(30));
        Path expired = rollout(home, "2026/06/02", now.minus(Duration.ofDays(60)));
        // Started before the cutoff but resumed since: Codex keeps appending to the original day.
        Path resumed = rollout(home, "2026/06/03", now.minus(Duration.ofDays(1)));
        Path recent = rollout(home, "2026/09/19", now.minus(Duration.ofDays(2)));

        assertThat(CodexHome.pruneSessions(home, cutoff)).isEqualTo(1);

        assertThat(expired.getParent()).doesNotExist();
        assertThat(resumed).exists();
        assertThat(recent).exists();
        // Only the day goes; the months it hung off are left for the next thread started that month.
        assertThat(home.resolve("sessions/2026/06")).exists();
    }

    @Test
    void prunesNothingWhenTheHomeHasNoSessionsYet()
            throws IOException
    {
        assertThat(CodexHome.pruneSessions(CodexHome.prepare(temporaryDirectory.resolve("codex-home")), Instant.now())).isZero();
    }

    private static Path rollout(Path home, String day, Instant writtenAt)
            throws IOException
    {
        Path file = home.resolve("sessions").resolve(day).resolve("rollout-" + day.replace('/', '-') + "-0a1b2c3d.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}\n");
        Files.setLastModifiedTime(file, FileTime.from(writtenAt));
        return file;
    }
}
