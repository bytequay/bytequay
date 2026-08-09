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
package com.bytequay.app.service.agents;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.service.agents.ToolExposurePolicy.V2Profile;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestToolExposurePolicy
{
    private final ToolExposurePolicy policy = new ToolExposurePolicy();

    @Test
    void trunkCannotSeeRetiredQueueTools()
    {
        assertThat(policy.activeTools(ByteQuayRole.TRUNK, null))
                .contains("create_task")
                .doesNotContain("queue_task", "reorder_queue", "drop_queued_task");
    }

    @Test
    void developmentHandsOffOnlyThroughTheLocalPrWorkflow()
    {
        assertThat(policy.activeTools(ByteQuayRole.TASK, StageType.DEVELOPMENT_STAGE))
                .contains("record_local_review")
                .doesNotContain("request_review", "ship_task", "push", "validate");
    }

    @Test
    void ciFixStagesCannotCreateAnotherPublishGate()
    {
        assertThat(policy.activeTools(ByteQuayRole.TASK, StageType.CI_FIXING_STAGE))
                .contains("run_checks", "record_pr_check")
                .doesNotContain("ship_task", "push", "request_review");
    }

    @Test
    void remoteDevelopmentCannotRecreateTheRemovedMarkReadyGate()
    {
        assertThat(policy.activeTools(ByteQuayRole.TASK, StageType.REMOTE_DEVELOPMENT_STAGE))
                .contains("record_round_reply", "resolve_review_comment", "record_pr_commit", "record_pr_check")
                .doesNotContain(
                        "mark_ready", "ship_task", "push", "request_review", "request_reviewer");
    }

    @Test
    void agentsCannotSplitReviewRoundRepliesOutsideTheAtomicGate()
    {
        assertThat(policy.activeTools(ByteQuayRole.TASK, StageType.REMOTE_DEVELOPMENT_STAGE))
                .contains("record_round_reply")
                .doesNotContain("reply_review_thread", "resolve_review_thread");
        assertThat(policy.activeTools(ByteQuayRole.TASK, StageType.DEVELOPMENT_STAGE))
                .doesNotContain("reply_review_thread", "resolve_review_thread");
        assertThat(policy.activeTools(ByteQuayRole.TASK, StageType.REMOTE_DEVELOPMENT_STAGE))
                .doesNotContain("reply_review_thread", "resolve_review_thread");
        assertThat(policy.activeTools(ByteQuayRole.TASK, StageType.BRANCH_GUARD_STAGE))
                .doesNotContain("reply_review_thread", "resolve_review_thread");
    }

    @Test
    void completionSummaryCannotMutateWorkflowOrRequestApproval()
    {
        assertThat(policy.completionSummaryTools())
                .contains("read_commit_summary", "read_diff_summary")
                .doesNotContain(
                        "create_task", "record_plan", "record_review_verdict",
                        "approval_prompt", "ask_user_question", "push");
    }

    @Test
    void automaticRemoteBrainReviewsCannotWaitForTheUser()
    {
        assertThat(policy.automaticTaskBrainReviewTools())
                .contains("read_commit_summary", "read_remote_pr_status")
                .doesNotContain("approval_prompt", "ask_user_question");
    }

    @Test
    void everyV2CatalogExcludesLegacyLifecycleAndArtifactMutators()
    {
        Set<String> retired = ImmutableSet.of(
                "create_task", "record_plan", "record_review_verdict",
                "record_round_reply", "validate", "record_iteration_summary",
                "record_dev_report", "record_pr_progress",
                "record_pr_description", "record_pr_commit",
                "record_pr_check", "record_local_review",
                "record_pr_comment", "resolve_pr_comment",
                "resolve_review_comment", "ship_task", "push",
                "request_review", "mark_ready");

        for (V2Profile profile : V2Profile.values()) {
            assertThat(policy.v2Tools(profile))
                    .as("V2 profile %s", profile)
                    .doesNotContainAnyElementsOf(retired);
        }
    }

    @Test
    void v2ProfilesExposeOnlyTheirRequiredObservationAndCheckTools()
    {
        assertThat(policy.v2Tools(V2Profile.LOCAL_DEVELOPMENT))
                .contains("approval_prompt", "ask_user_question", "run_checks");
        assertThat(policy.v2Tools(V2Profile.REMOTE_DEVELOPMENT))
                .contains("approval_prompt", "ask_user_question", "run_checks",
                        "read_remote_pr_status", "read_ci_log");
        assertThat(policy.v2Tools(V2Profile.PLAN_PROTOCOL)).isEmpty();
        assertThat(policy.v2Tools(V2Profile.CLEANUP)).isEmpty();
    }
}
