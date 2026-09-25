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
package com.bytequay.agent.cli.process;

import com.bytequay.agent.spi.AgentPermissionScope;
import com.bytequay.agent.spi.SessionAccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Checks a provider's concrete file-write request against ByteQuay's already granted roots. */
public final class AgentPermissionManager
{
    private AgentPermissionManager() {}

    /** Unknown paths and actions are left to the task approval card; shell commands are never inferred from their text. */
    public static boolean allowsFileWrite(Path requested, Path worktree, Map<String, String> environment, AgentPermissionScope scope)
    {
        requireNonNull(requested, "requested is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(environment, "environment is null");
        requireNonNull(scope, "scope is null");
        // Keep new policies from inheriting write grants without an explicit decision.
        boolean buildCaches = switch (scope.policy()) {
            case AUTO, CI_AUTOFIX -> true;
            case CHERRY_PICK -> false;
        };
        if (!requested.isAbsolute() || scope.access() != SessionAccess.FULL || scope.review()) {
            return false;
        }
        try {
            Path target = canonicalTarget(requested.normalize());
            Path taskRoot = worktree.toRealPath();
            return target.startsWith(taskRoot)
                    || (buildCaches && BuildCacheRoots.writableRoots(environment).stream().anyMatch(target::startsWith));
        }
        catch (IOException | SecurityException failure) {
            return false;
        }
    }

    private static Path canonicalTarget(Path target)
            throws IOException
    {
        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("File-write path has no existing ancestor");
        }
        return existing.toRealPath().resolve(existing.relativize(target)).normalize();
    }
}
