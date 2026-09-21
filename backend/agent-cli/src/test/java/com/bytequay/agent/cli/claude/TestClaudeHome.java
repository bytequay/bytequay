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
package com.bytequay.agent.cli.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestClaudeHome
{
    @TempDir
    private Path temporaryDirectory;

    @Test
    void createsAnOwnerOnlyHomeAndRetightensOneTheUserCreated()
            throws IOException
    {
        Path home = temporaryDirectory.resolve("claude-home");

        // Nothing but the owner may read the login and transcripts that will be stored here.
        assertThat(ClaudeHome.prepare(home)).isEqualTo(home);
        assertThat(Files.getPosixFilePermissions(home))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

        // Signing in first creates the directory with default permissions; every start re-tightens
        // it, but never touches what is already stored there.
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(home.resolve(".credentials.json"), "{}");

        ClaudeHome.prepare(home);

        assertThat(Files.getPosixFilePermissions(home))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        assertThat(home.resolve(".credentials.json")).hasContent("{}");
    }

    @Test
    void deletesOnlyTranscriptDirectoriesNothingHasWrittenToSinceTheCutoff()
            throws IOException
    {
        Path home = temporaryDirectory.resolve("claude-home");
        Instant now = Instant.now();
        // The worktree this one ran in is long deleted, so no later session can ever reuse it.
        Path orphaned = transcript(home, "-ByteQuay-workspaces-60424c47-worktrees-14df07b6", now.minus(Duration.ofDays(60)));
        Path live = transcript(home, "-ByteQuay-workspaces-60424c47-worktrees-28310906", now.minus(Duration.ofDays(1)));

        assertThat(ClaudeHome.pruneSessions(home, now.minus(Duration.ofDays(30)))).isEqualTo(1);

        assertThat(orphaned.getParent()).doesNotExist();
        assertThat(live).exists();
    }

    @Test
    void prunesNothingWhenTheHomeHasNoTranscriptsYet()
            throws IOException
    {
        assertThat(ClaudeHome.pruneSessions(ClaudeHome.prepare(temporaryDirectory.resolve("claude-home")), Instant.now())).isZero();
    }

    private static Path transcript(Path home, String workingPath, Instant writtenAt)
            throws IOException
    {
        Path file = home.resolve("projects").resolve(workingPath).resolve("2fdeb731-9604-426c-aa51-e360e8578e19.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}\n");
        Files.setLastModifiedTime(file, FileTime.from(writtenAt));
        return file;
    }
}
