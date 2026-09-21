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

import com.bytequay.agent.cli.ProviderSessions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * ByteQuay's own Claude home.
 *
 * <p>Every Claude process ByteQuay starts runs with {@code CLAUDE_CONFIG_DIR} pointing here, so its
 * settings, session transcripts and history are ByteQuay's alone and nothing in the user's
 * {@code ~/.claude} reaches a task. The user signs this home in once with the command readiness
 * shows; ByteQuay never reads, copies or links the user's own credentials.
 *
 * <p>The home carries the identity a sign-in establishes, not the secret: on macOS the credential
 * itself lives in the login Keychain, so a home with no sign-in of its own reports logged out even
 * on a machine whose personal CLI is signed in.
 */
public final class ClaudeHome
{
    /** Claude writes one session transcript directory per working path under {@code projects}. */
    private static final String PROJECTS = "projects";
    private static final int PATH_DEPTH = 1;

    private ClaudeHome() {}

    /**
     * Creates the home if it does not exist and keeps it owner-only. A login already stored there is
     * left alone. Claude needs no seeded config file: {@code --setting-sources=} already excludes the
     * user settings layer.
     *
     * @param home the directory to prepare
     * @return the same path, for chaining into the driver
     * @throws IOException when the directory cannot be created
     */
    public static Path prepare(Path home)
            throws IOException
    {
        requireNonNull(home, "home is null");
        Files.createDirectories(home);
        // Re-applied every start: the user may have created the directory by signing in first.
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        return home;
    }

    /**
     * Deletes the per-path transcript directories that have all gone untouched since a cutoff.
     *
     * <p>Every task runs in its own worktree, and cleanup deletes that worktree once the task's
     * retention deadline passes. The directory keyed to it is orphaned from then on, because no
     * later session is ever given the same path, so nothing but age can retire it.
     *
     * @param home the Claude home to prune
     * @param deleteBefore the instant every transcript in a directory must predate for it to be deleted
     * @return how many transcript directories were deleted
     * @throws IOException when the projects tree cannot be read or a directory cannot be deleted
     */
    public static int pruneSessions(Path home, Instant deleteBefore)
            throws IOException
    {
        requireNonNull(home, "home is null");
        return ProviderSessions.prune(home.resolve(PROJECTS), PATH_DEPTH, deleteBefore);
    }
}
