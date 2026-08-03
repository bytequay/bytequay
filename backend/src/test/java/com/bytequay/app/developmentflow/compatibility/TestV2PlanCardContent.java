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
package com.bytequay.app.developmentflow.compatibility;

import com.bytequay.app.beans.stage.TaskBrainViewData;
import org.junit.jupiter.api.Test;

import static com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection.markdownPlan;
import static org.assertj.core.api.Assertions.assertThat;

class TestV2PlanCardContent
{
    @Test
    void parsesTheHeadingsTheDraftPromptAsksFor()
    {
        V2DevelopmentFlowProjection.MarkdownPlan plan = markdownPlan("""
                # Plan: Bump the nav font size

                ## Goal
                Raise the left-nav rows to 14px.

                ## Change
                frontend/src/css/workspace-task-v2.css

                ## Steps
                1. Edit the token.
                2. Run the frontend gates.

                ## Validation
                npx tsc --noEmit and npm test.

                ## Scope guardrails
                - No other tokens change.
                """);
        assertThat(plan.goal()).isEqualTo("Raise the left-nav rows to 14px.");
        assertThat(plan.steps()).extracting(TaskBrainViewData.PlanStep::action)
                .containsExactly("Edit the token.", "Run the frontend gates.");
        assertThat(plan.validation()).isEqualTo("npx tsc --noEmit and npm test.");
        assertThat(plan.guardrails()).containsExactly("No other tokens change.");
    }

    /** The brain qualifies headings and hard-wraps its Markdown; both survive. */
    @Test
    void parsesQualifiedHeadingsAndRejoinsWrappedItems()
    {
        V2DevelopmentFlowProjection.MarkdownPlan plan = markdownPlan("""
                # Plan — Remove unreferenced leaf classes

                ## Objective
                Delete genuinely dead leaf classes from the backend.

                ## Scope guardrails (do NOT touch)
                - Framework-wired beans, JPA
                  entities/repositories, controllers.
                - Only the three named packages.

                ## Execution steps
                1. Delete the two files listed above. No other files change,
                   since nothing imported these types.
                2. From `backend/`, run `mvn verify`.
                """);
        assertThat(plan.goal()).isEqualTo("Remove unreferenced leaf classes");
        assertThat(plan.steps()).extracting(TaskBrainViewData.PlanStep::action)
                .containsExactly(
                        "Delete the two files listed above. No other files change,"
                                + " since nothing imported these types.",
                        "From `backend/`, run `mvn verify`.");
        assertThat(plan.guardrails()).containsExactly(
                "Framework-wired beans, JPA entities/repositories, controllers.",
                "Only the three named packages.");
    }

    /** Revisions recorded before the contract still render one synthetic step. */
    @Test
    void keepsTheSyntheticStepWhenNoStepsSectionExists()
    {
        V2DevelopmentFlowProjection.MarkdownPlan plan = markdownPlan("""
                ## Change
                frontend/src/css/v3-conv.css
                """);
        assertThat(plan.steps()).extracting(TaskBrainViewData.PlanStep::action)
                .containsExactly("Update frontend/src/css/v3-conv.css");
        assertThat(plan.steps().getFirst().files())
                .containsExactly("frontend/src/css/v3-conv.css");
    }
}
