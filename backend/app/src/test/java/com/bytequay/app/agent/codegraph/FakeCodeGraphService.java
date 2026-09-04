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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.CodeGraphException;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A CodeGraph service that records calls; application tests wire it where the real adapter would go. */
public final class FakeCodeGraphService
        implements CodeGraphService
{
    public record Refresh(RepositoryIdentity repository, Path repositoryDirectory, String commit) {}

    public final List<Refresh> refreshes = new ArrayList<>();
    public final List<RepositoryIdentity> deletedBaselines = new ArrayList<>();
    public boolean available = true;
    public boolean failRefresh;

    @Override
    public boolean available()
    {
        return available;
    }

    @Override
    public void refreshBaseline(RepositoryIdentity repository, Path repositoryDirectory, String defaultBranchCommit)
    {
        if (failRefresh) {
            throw new CodeGraphException("refresh failed on purpose");
        }
        refreshes.add(new Refresh(repository, repositoryDirectory, defaultBranchCommit));
    }

    @Override
    public void prepare(TaskWorktree worktree, TaskResources resources) {}

    @Override
    public String explore(TaskWorktree worktree, TaskResources resources, String query)
    {
        return "";
    }

    @Override
    public void deleteIndex(TaskResources resources) {}

    @Override
    public void deleteBaseline(RepositoryIdentity repository)
    {
        deletedBaselines.add(repository);
    }
}
