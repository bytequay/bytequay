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
package com.bytequay.agent.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorktreeContract
{
    private static final String START_COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String RESULT_HEAD = "fedcba9876543210fedcba9876543210fedcba98";

    @Test
    void provisionContainsOnlyTrustedOwnershipFacts()
    {
        TaskId taskId = TaskId.random();
        ProvisionWorktreeCommand command = new ProvisionWorktreeCommand(
                taskId,
                "workspace-1",
                RepositoryIdentity.valueOf("bytequay/bytequay"),
                START_COMMIT);

        assertThat(command.taskId()).isEqualTo(taskId);
        assertThat(command.workspaceId()).isEqualTo("workspace-1");
        assertThat(command.physicalRepository()).isEqualTo(RepositoryIdentity.valueOf("bytequay/bytequay"));
        assertThat(command.startCommit()).isEqualTo(START_COMMIT);

        assertThatThrownBy(() -> new ProvisionWorktreeCommand(
                taskId,
                " ",
                RepositoryIdentity.valueOf("bytequay/bytequay"),
                START_COMMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
        assertThatThrownBy(() -> new ProvisionWorktreeCommand(
                taskId,
                "workspace-1",
                RepositoryIdentity.valueOf("bytequay/bytequay"),
                "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact Git object ID");
    }

    @Test
    void worktreeRequiresTaskDerivedPathAndBranch()
    {
        TaskId taskId = TaskId.random();

        assertThat(worktree(taskId).generatedBranch()).isEqualTo("bytequay/task/" + taskId);
        assertThatThrownBy(() -> new TaskWorktree(
                taskId,
                "workspace-1",
                RepositoryIdentity.valueOf("bytequay/bytequay"),
                Path.of("worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                START_COMMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> new TaskWorktree(
                taskId,
                "workspace-1",
                RepositoryIdentity.valueOf("bytequay/bytequay"),
                Path.of("/tmp/worktrees").resolve(taskId.toString()),
                "other/branch",
                START_COMMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch is not derived");
    }

    @Test
    void snapshotRequiresAnExactHead()
    {
        TaskWorktree worktree = worktree(TaskId.random());

        assertThat(new WorktreeSnapshot(worktree, RESULT_HEAD, false, false).head()).isEqualTo(RESULT_HEAD);
        assertThatThrownBy(() -> new WorktreeSnapshot(worktree, "HEAD", false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact Git object ID");
    }

    private static TaskWorktree worktree(TaskId taskId)
    {
        return new TaskWorktree(
                taskId,
                "workspace-1",
                RepositoryIdentity.valueOf("bytequay/bytequay"),
                Path.of("/tmp/worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                START_COMMIT);
    }
}
