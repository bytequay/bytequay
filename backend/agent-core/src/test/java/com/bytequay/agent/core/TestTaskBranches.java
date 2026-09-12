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

class TestTaskBranches
{
    private static final TaskId TASK_ID = TaskId.valueOf("c587a121-a71d-40a1-9755-3f6ee9db0547");

    @Test
    void generatesReadableNamesFromTheAuthenticatedLoginAndInitialPrompt()
    {
        assertThat(TaskBranches.generate(TASK_ID, "Chenjian2664", "Find and refactor a minor issue"))
                .isEqualTo("chenjian2664/bytequay/find-and-refactor-a-minor-issue-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "## Réparer [CSV](https://example.test) / types!\nMore details"))
                .isEqualTo("user/bytequay/reparer-csv-types-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "修复问题"))
                .isEqualTo("user/bytequay/task-c587a121");
        String longBranch = TaskBranches.generate(TASK_ID, "user", "a".repeat(1000));
        assertThat(longBranch.length()).isLessThanOrEqualTo(128);
        assertThat(TaskBranches.matches(TASK_ID, longBranch)).isTrue();
        assertThat(TaskBranches.matches(TASK_ID, TaskBranches.generate(TASK_ID, "u".repeat(39), "ﬃ".repeat(100)))).isTrue();
    }

    @Test
    void rejectsUnsafeOrWrongTaskBranchesAndRetainsRecordedBranches()
    {
        String branch = TaskBranches.generate(TASK_ID, "user", "Fix bug");
        TaskWorktree.checkGeneratedBranch(TASK_ID, branch);
        TaskWorktree.checkGeneratedBranch(TASK_ID, "bytequay/task/" + TASK_ID);
        assertThatThrownBy(() -> TaskBranches.generate(TASK_ID, "../user", "Fix bug"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskBranches.generate(TASK_ID, "user", " "))
                .isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[] {
                "other/branch", "user/bytequay/../fix-c587a121", "user/bytequay/fix.lock-c587a121",
                "user/bytequay/fix@{1}-c587a121", "user/bytequay/fix-00000000",
                "bytequay/task/00000000-a71d-40a1-9755-3f6ee9db0547"}) {
            assertThatThrownBy(() -> new TaskWorktree(TASK_ID, "workspace", RepositoryIdentity.valueOf("org/repo"),
                    Path.of("/tmp").resolve(TASK_ID.toString()), invalid, "a".repeat(40)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("branch is not derived");
        }
    }
}
