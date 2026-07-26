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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.KnowledgeItem.Applicability;
import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewContext.Snapshot;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewKnowledge;
import com.bytequay.app.service.review.InvestigationReviewService.PlanDraft;
import com.bytequay.app.service.review.InvestigationReviewService.PlanObjective;
import com.bytequay.app.service.runs.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestInvestigationReviewPlanning
{
    private final InvestigationReviewContext contexts = mock(InvestigationReviewContext.class);
    private final InvestigationReviewModel model = mock(InvestigationReviewModel.class);
    private final PRService prs = mock(PRService.class);
    private final InvestigationReviewService service = new InvestigationReviewService(
            mock(InvestigationReviewStore.class), contexts, model,
            mock(AgentRunService.class), prs, mock(TaskStore.class),
            mock(ThreadStore.class), new ObjectMapper());

    private PR pr;
    private Snapshot snapshot;

    @BeforeEach
    void setUp()
    {
        pr = PR.createExternal(
                "pr-1", "trinodb/trino", 17, "https://example.test/17", "octocat",
                "feature", "main", "Keep Iceberg metadata compatible",
                "Update the connector metadata path", PR.STATUS_REMOTE_OPEN,
                Instant.EPOCH, null, null);
        String path = "plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java";
        String diff = """
                diff --git a/%s b/%s
                --- a/%s
                +++ b/%s
                @@ -1 +1 @@
                -public String metadata() { return "old"; }
                +public String metadata() { return "new"; }
                """.formatted(path, path, path, path);
        snapshot = new Snapshot(
                pr, "base", "head", diff,
                List.of(new DiffFile(path, "modified", 1, 1, null)),
                null, null, ReviewCapabilities.remoteOnly());
        when(prs.findById(pr.id())).thenReturn(Optional.of(pr));
        when(contexts.load(pr, false)).thenReturn(snapshot);
    }

    @Test
    void testLearnedKnowledgeFramesThePlanWithoutReplacingSafetyObjectives()
    {
        when(model.reviewKnowledge(snapshot)).thenReturn(List.of(
                knowledge("duplicate", "domain-invariant",
                        "Preserve existing behavior on changed return and error paths.", 1),
                knowledge("compat", "compatibility-contract",
                        "Preserve connector metadata compatibility.", 1),
                knowledge("recipe", "investigation-recipe",
                        "Trace metadata consumers across connector boundaries.", 1),
                knowledge("term", "glossary", "A split is a schedulable unit.", 1),
                knowledge("convention", "doc-note", "Use connector-local test fixtures.", 1),
                knowledge("fourth", "domain-invariant", "This exceeds the learned cap.", 1)));

        PlanDraft plan = service.preflight(pr.id());

        List<PlanObjective> learned = plan.objectives().stream()
                .filter(objective -> "project-intelligence".equals(objective.sourceType()))
                .toList();
        assertThat(learned).extracting(PlanObjective::sourceRef)
                .containsExactly("compat", "recipe", "convention");
        assertThat(learned).extracting(PlanObjective::kind)
                .containsExactly("hard-invariant", "engineering-principle", "repo-convention");
        assertThat(learned).extracting(PlanObjective::statement)
                .allMatch(statement -> statement.startsWith("[plugin/trino-iceberg] "));
        assertThat(plan.objectives().getFirst().sourceType()).isEqualTo("shipped-rule");
        assertThat(plan.objectives()).anyMatch(objective ->
                "failure-class".equals(objective.sourceType()));
        assertThat(plan.reviewClass()).isEqualTo("standard");
        assertThat(plan.budget().costCapCents()).isEqualTo(50);
        assertThat(plan.budget().wallClockMinutes()).isEqualTo(10);
    }

    @Test
    void testUpdatedKnowledgeInvalidatesThePreflightCache()
    {
        when(model.reviewKnowledge(snapshot)).thenReturn(
                List.of(knowledge("compat", "compatibility-contract", "Old rule.", 1)),
                List.of(knowledge("compat", "compatibility-contract", "Updated rule.", 2)));

        PlanDraft first = service.preflight(pr.id());
        PlanDraft second = service.preflight(pr.id());

        assertThat(learnedStatements(first)).containsExactly("[plugin/trino-iceberg] Old rule.");
        assertThat(learnedStatements(second)).containsExactly("[plugin/trino-iceberg] Updated rule.");
    }

    @Test
    void testKnowledgeRetrievalFailureDoesNotBlockTheSafetyPlan()
    {
        when(model.reviewKnowledge(snapshot)).thenThrow(new IllegalStateException("index unavailable"));

        PlanDraft plan = service.preflight(pr.id());

        assertThat(learnedStatements(plan)).isEmpty();
        assertThat(plan.objectives().getFirst().sourceType()).isEqualTo("shipped-rule");
        assertThat(plan.objectives()).anyMatch(objective ->
                "failure-class".equals(objective.sourceType()));
    }

    private static ReviewKnowledge knowledge(
            String id, String kind, String statement, long updatedAtMs)
    {
        return new ReviewKnowledge(
                id, kind, statement,
                List.of(new Applicability("module", "plugin/trino-iceberg")),
                updatedAtMs);
    }

    private static List<String> learnedStatements(PlanDraft plan)
    {
        return plan.objectives().stream()
                .filter(objective -> "project-intelligence".equals(objective.sourceType()))
                .map(PlanObjective::statement)
                .toList();
    }
}
