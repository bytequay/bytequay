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
import com.bytequay.app.service.skills.ByteQuayRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestToolExposurePolicy
{
    private final ToolExposurePolicy policy = new ToolExposurePolicy();

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
}
