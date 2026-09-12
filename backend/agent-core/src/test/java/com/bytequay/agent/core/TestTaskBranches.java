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
                .isEqualTo("chenjian2664/bytequay/find-and-refactor-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "## Réparer [CSV](https://example.test) / types!\nMore details"))
                .isEqualTo("user/bytequay/reparer-csv-types-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "修复问题"))
                .isEqualTo("user/bytequay/task-c587a121");
        String longBranch = TaskBranches.generate(TASK_ID, "user", "a".repeat(1000));
        assertThat(longBranch).isEqualTo("user/bytequay/" + "a".repeat(32) + "-c587a121");
        assertThat(TaskBranches.matches(TASK_ID, longBranch)).isTrue();
        assertThat(TaskBranches.matches(TASK_ID, TaskBranches.generate(TASK_ID, "u".repeat(39), "ﬃ".repeat(100)))).isTrue();
    }

    @Test
    void removesConversationalLeadInsBeforeShorteningTheDescription()
    {
        for (String prefix : new String[] {
                "Let's ", "Let’s ", "Let us ", "Please ", "Can you ", "Will you ", "I want to ", "We need to ",
                "I'd like to ", "We’d like to ", "Could you please help me to ", "Would you please help us ",
                "I would like to please "}) {
            assertThat(TaskBranches.generate(TASK_ID, "chenjian2664", prefix + "refactor nits in Delta Lake"))
                    .as(prefix)
                    .isEqualTo("chenjian2664/bytequay/refactor-nits-in-delta-c587a121");
        }
        assertThat(TaskBranches.generate(TASK_ID, "user", "Could you please help me to refactor nit in Delta Lake"))
                .isEqualTo("user/bytequay/refactor-nit-in-delta-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "Please, let's refactor nits in Delta Lake"))
                .isEqualTo("user/bytequay/refactor-nits-in-delta-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "Please help me"))
                .isEqualTo("user/bytequay/task-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "please ".repeat(10000) + "refactor nits in Delta Lake"))
                .isEqualTo("user/bytequay/refactor-nits-in-delta-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "Help messaging clients connect"))
                .isEqualTo("user/bytequay/help-messaging-clients-connect-c587a121");
    }

    @Test
    void keepsWholeWordsWithoutDanglingConnectorsOrLosingNegation()
    {
        assertThat(TaskBranches.generate(TASK_ID, "user", "Refactor the authentication layer"))
                .isEqualTo("user/bytequay/refactor-the-authentication-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "Fix flaky tests in the backend"))
                .isEqualTo("user/bytequay/fix-flaky-tests-c587a121");
        assertThat(TaskBranches.generate(TASK_ID, "user", "Please do not remove authentication"))
                .isEqualTo("user/bytequay/do-not-remove-authentication-c587a121");
    }

    @Test
    void rejectsUnsafeOrWrongTaskBranchesAndRetainsRecordedBranches()
    {
        String branch = TaskBranches.generate(TASK_ID, "user", "Fix bug");
        TaskWorktree.checkGeneratedBranch(TASK_ID, branch);
        TaskWorktree.checkGeneratedBranch(TASK_ID, "bytequay/task/" + TASK_ID);
        TaskWorktree.checkGeneratedBranch(TASK_ID, "user/bytequay/let-s-refactor-nits-in-delta-lake-c587a121");
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
