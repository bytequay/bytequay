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
package com.bytequay.app.developmentflow.task.creation;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/** Deterministic branch and worktree target for an already-allocated Task id. */
public record ProvisionTarget(
        String taskId,
        Path repositoryRoot,
        TaskAssignment.RepositoryRouting repositories)
{
    public ProvisionTarget
    {
        requireTaskId(taskId);
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        if (!repositoryRoot.isAbsolute()) {
            throw new IllegalArgumentException("repositoryRoot must be absolute");
        }
        repositoryRoot = repositoryRoot.normalize();
        requireNonNull(repositories, "repositories is null");
    }

    public static ProvisionTarget derive(
            String taskId,
            Path repositoryRoot,
            TaskAssignment.RepositoryRouting repositories)
    {
        return new ProvisionTarget(taskId, repositoryRoot, repositories);
    }

    public String repositoryId()
    {
        return repositories.repositoryId();
    }

    public String publishRepositoryId()
    {
        return repositories.publishRepositoryId();
    }

    public String branchName()
    {
        return "dev/" + taskId;
    }

    public Path worktreePath()
    {
        return repositoryRoot.resolve(".worktrees").resolve(taskId).normalize();
    }

    private static void requireTaskId(String value)
    {
        requireNonNull(value, "taskId is null");
        if (value.isBlank()
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || value.contains("..")
                || value.endsWith(".")
                || value.endsWith(".lock")) {
            throw new IllegalArgumentException("taskId is not a safe branch/path segment");
        }
    }
}
