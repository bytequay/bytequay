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

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

@Service
public class WorkspaceCommitsSettingsService
{
    private static final String TARGET_BRANCH_PREFIX = "workspace.commitsTargetBranch.";

    private final WorkspaceStore workspaces;
    private final AppSettingsStore settings;
    private final GitCommandRunner git;

    public WorkspaceCommitsSettingsService(WorkspaceStore workspaces, AppSettingsStore settings, GitCommandRunner git)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.git = requireNonNull(git, "git is null");
    }

    public WorkspaceCommitsTarget get(String workspaceId)
    {
        return resolve(requireWorkspace(workspaceId));
    }

    public WorkspaceCommitsTarget save(String workspaceId, String branch)
    {
        Lock lock = WorkspaceOperationLocks.get(workspaceId);
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(workspaceId);
            checkArgument(branch != null && !branch.isBlank() && branch.length() <= 512, "A branch name of at most 512 characters is required.");
            checkArgument(!branch.startsWith("-") && !branch.startsWith("refs/") && !branch.equals("HEAD"), "Enter a branch name, not a Git option or full ref.");
            // Ref validation needs no checkout, so preferences remain usable before provisioning.
            Result validation = git.run(Path.of("/"), "check-ref-format", "refs/heads/" + branch);
            checkState(validation.exitCode() >= 0, "Git branch validation could not complete.");
            checkArgument(validation.successful(), "Branch name is not a valid Git branch.");
            settings.put(TARGET_BRANCH_PREFIX + workspaceId, branch);
            return resolve(workspace);
        }
        finally {
            lock.unlock();
        }
    }

    public WorkspaceCommitsTarget reset(String workspaceId)
    {
        Lock lock = WorkspaceOperationLocks.get(workspaceId);
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(workspaceId);
            settings.delete(TARGET_BRANCH_PREFIX + workspaceId);
            return resolve(workspace);
        }
        finally {
            lock.unlock();
        }
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        return workspaces.findById(workspaceId).orElseThrow(() -> WorkspaceException.notFound(workspaceId));
    }

    private WorkspaceCommitsTarget resolve(Workspace workspace)
    {
        Optional<String> override = settings.get(TARGET_BRANCH_PREFIX + workspace.id());
        return new WorkspaceCommitsTarget(
                workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin",
                override.orElseGet(() -> workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch())),
                override.isPresent());
    }
}
