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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Records every CodeGraph call so lifecycle tests can assert what the services hand it. */
final class RecordingCodeGraphService
        implements CodeGraphService
{
    final List<TaskWorktree> prepared = new ArrayList<>();
    final List<TaskResources> preparedResources = new ArrayList<>();
    final List<TaskResources> deletedIndexes = new ArrayList<>();
    final List<RepositoryIdentity> deletedBaselines = new ArrayList<>();

    @Override
    public boolean available()
    {
        return true;
    }

    @Override
    public void refreshBaseline(RepositoryIdentity repository, Path repositoryDirectory, String defaultBranchCommit) {}

    @Override
    public void prepare(TaskWorktree worktree, TaskResources resources)
    {
        prepared.add(worktree);
        preparedResources.add(resources);
    }

    @Override
    public String explore(TaskWorktree worktree, TaskResources resources, String query)
    {
        return "";
    }

    @Override
    public void deleteIndex(TaskResources resources)
    {
        deletedIndexes.add(resources);
    }

    @Override
    public void deleteBaseline(RepositoryIdentity repository)
    {
        deletedBaselines.add(repository);
    }
}
