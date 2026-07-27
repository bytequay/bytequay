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
package com.bytequay.app.service.threads;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestTrunkToolAllowlist
{
    @Test
    void trunkCanPlanAndQueueNotJustCutOneTask()
    {
        // Regression: the queue-planning tools were once omitted from the
        // trunk allowlist, so the trunk could create_task but never line
        // one up, reorder, or drop. The trunk's whole job is planning the
        // queue, so these must stay reachable.
        assertThat(LogicLoopThreadAgent.TRUNK_TOOL_ALLOWLIST)
                .contains("create_task", "queue_task", "reorder_queue", "drop_queued_task");
    }

    @Test
    void trunkCanReadFilesToPlanAgainstSource()
    {
        // The trunk reads source to plan, and opens a pasted image handed
        // to it as a path.
        assertThat(LogicLoopThreadAgent.TRUNK_TOOL_ALLOWLIST).contains("read_file");
    }

    @Test
    void trunkCanReadItsRepositoryWithoutOpeningGitConfig()
    {
        assertThat(LogicLoopThreadAgent.TRUNK_TOOL_ALLOWLIST)
                .contains("read_current_repository");
    }

    @Test
    void trunkCanReadFreshIssueContext()
    {
        assertThat(LogicLoopThreadAgent.TRUNK_TOOL_ALLOWLIST)
                .contains("read_issue");
    }

    @Test
    void trunkCanAskAClarifyingQuestion()
    {
        // Regression: trunk-role.md instructs the trunk extensively to ask
        // rather than assume ("when in doubt between asking and assuming,
        // ask"), but the tool was missing from the runtime allowlist — the
        // prompt said to ask, the runtime didn't let it.
        assertThat(LogicLoopThreadAgent.TRUNK_TOOL_ALLOWLIST).contains("ask_user_question");
    }

    @Test
    void brainCanUseOnlyItsLocalReviewWriters()
    {
        assertThat(LogicLoopThreadAgent.BRAIN_TOOL_ALLOWLIST)
                .contains("codegraph_explore", "record_plan", "record_pr_comment", "record_review_verdict")
                .doesNotContain("push", "merge_pr", "post_comment");
        assertThat(LogicLoopThreadAgent.BRAIN_SYSTEM_PROMPT)
                .contains("record_pr_comment")
                .doesNotContain("no comments");
    }
}
