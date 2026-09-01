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
package com.bytequay.app.workspace;

import com.bytequay.core.domain.Workspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Resolves ByteQuay-owned repository and worktree paths from a trusted workspace ID. */
@Component
public final class WorkspacePaths
{
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");

    private final Path root;

    public WorkspacePaths(
            @Value("${bytequay.workspace.root:${java.io.tmpdir}/bytequay-workspaces}") String root)
    {
        this.root = Path.of(requireNonNull(root, "root is null")).toAbsolutePath().normalize();
    }

    /** Returns the managed root containing every watched-repository workspace. */
    public Path root()
    {
        return root;
    }

    /** Returns the owned directory for one workspace after validating its identifier. */
    public Path workspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        if (!SAFE_ID.matcher(workspaceId).matches()) {
            throw WorkspaceException.invalidRepository("Workspace path is invalid.");
        }
        Path resolved = root.resolve(workspaceId).normalize();
        if (!resolved.startsWith(root)) {
            throw WorkspaceException.invalidRepository("Workspace path is invalid.");
        }
        return resolved;
    }

    /** Returns the checkout path persisted for a workspace and rejects path drift. */
    public Path repository(Workspace workspace)
    {
        requireNonNull(workspace, "workspace is null");
        Path expected = repository(workspace.id());
        Path persisted = Path.of(workspace.localRepositoryPath()).toAbsolutePath().normalize();
        if (!persisted.equals(expected)) {
            throw WorkspaceException.invalidRepository("Managed repository path is invalid.");
        }
        return expected;
    }

    /** Returns the backend-owned checkout path for cleanup of a workspace execution. */
    public Path repository(String workspaceId)
    {
        return workspace(workspaceId).resolve("repository").normalize();
    }

    /** Returns the backend-owned parent for task worktrees. */
    public Path worktrees(String workspaceId)
    {
        return workspace(workspaceId).resolve("worktrees").normalize();
    }
}
