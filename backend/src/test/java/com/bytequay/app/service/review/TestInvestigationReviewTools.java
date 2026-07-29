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
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore.ReviewRoundSnapshot;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor.ToolCallResult;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestInvestigationReviewTools
{
    @Test
    void recordFindingRequiresAnExactFileRangeAndBlockingSeverity()
    {
        InvestigationReviewTools tools = new InvestigationReviewTools(
                mock(InvestigationReviewStore.class), new ObjectMapper());

        JsonNode schema = StreamSupport.stream(
                        tools.tools(TurnSpec.Transport.OPENAI_COMPAT, false).spliterator(), false)
                .filter(tool -> "record_finding".equals(tool.path("function").path("name").asText()))
                .findFirst().orElseThrow()
                .path("function").path("parameters");

        assertThat(StreamSupport.stream(schema.path("required").spliterator(), false)
                .map(JsonNode::asText).toList())
                .contains("path", "start_line", "end_line");
        assertThat(schema.path("properties").path("severity").path("minimum").asInt())
                .isEqualTo(4);
        assertThat(schema.path("properties").path("severity").path("maximum").asInt())
                .isEqualTo(5);
    }

    @Test
    void recordFindingRejectsLowerSeverityBeforePersistence()
    {
        InvestigationReviewStore store = mock(InvestigationReviewStore.class);
        when(store.assignmentBelongsToReview("a1", "r1")).thenReturn(true);
        when(store.assignmentRoundIsRunning("a1")).thenReturn(true);
        when(store.objectives("r1")).thenReturn(List.of(new ReviewObjectiveRow(
                "o1", "round1", "criterion1", "Preserve correctness", "failure-class",
                "applicable", "pending")));
        InvestigationReviewTools tools = new InvestigationReviewTools(
                store, new ObjectMapper());

        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("objective_id", "o1");
        input.put("criterion_kind", "hard-invariant");
        input.put("severity", 3);

        ToolCallResult result = tools.executor("r1", "a1")
                .execute(new ToolCall("t1", "record_finding", "{}", input));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("severity must be 4..5");
        verify(store, never()).insertFinding(any());
    }

    @Test
    void verifierDowngradeDropsFinding()
    {
        InvestigationReviewStore store = mock(InvestigationReviewStore.class);
        when(store.assignmentBelongsToReview("a1", "r1")).thenReturn(true);
        when(store.assignmentRoundIsRunning("a1")).thenReturn(true);
        when(store.findFinding("f1")).thenReturn(Optional.of(new FindingRow(
                "f1", "r1", "round1", "o1", "h1", "hard-invariant",
                "src/A.java", 7, 7, "A blocking claim.", 4, "SUPPORTED", "unknown",
                "Fix the broken branch.", "candidate", "head")));
        when(store.mutateWhileAssignmentRoundRunning(any(), any())).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return true;
        });
        InvestigationReviewTools tools = new InvestigationReviewTools(
                store, new ObjectMapper());

        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("finding_id", "f1");
        input.put("verifier_run_id", "verifier1");
        input.put("status", "verified");
        input.put("revised_severity", 3);
        input.put("explanation", "The impact is non-blocking.");

        ToolCallResult result = tools.executor("r1", "a1")
                .execute(new ToolCall("t1", "record_verification", "{}", input));

        assertThat(result.isError()).isFalse();
        verify(store).updateFinding(
                "f1", "dropped", "verified", "VERIFIED", "A blocking claim.", 3);
    }

    @Test
    void recordStepParsesStringEncodedArguments()
    {
        InvestigationReviewStore store = mock(InvestigationReviewStore.class);
        when(store.assignmentBelongsToReview("a1", "r1")).thenReturn(true);
        when(store.assignmentRoundIsRunning("a1")).thenReturn(true);
        when(store.mutateWhileAssignmentRoundRunning(any(), any())).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return true;
        });
        InvestigationReviewTools tools = new InvestigationReviewTools(
                store, new ObjectMapper());

        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("action_type", "readRange");
        input.put("reason", "check the range");
        // A model returned the tool arguments as a JSON string, not an object.
        input.put("arguments", "{\"path\":\"X.java\",\"start_line\":51,\"end_line\":80}");
        tools.executor("r1", "a1").execute(new ToolCall("t1", "record_step", "{}", input));

        ArgumentCaptor<InvestigationStepRow> row = ArgumentCaptor.forClass(InvestigationStepRow.class);
        verify(store).insertStep(row.capture());
        JsonNode arguments = row.getValue().argumentsJson();
        assertThat(arguments.isObject()).isTrue();
        assertThat(arguments.path("path").asText()).isEqualTo("X.java");
        assertThat(arguments.path("start_line").asInt()).isEqualTo(51);
    }

    @Test
    void quickReviewExecutorRejectsReadFileBeforeReadingSnapshot()
    {
        InvestigationReviewStore store = mock(InvestigationReviewStore.class);
        when(store.assignmentBelongsToReview("assignment-1", "review-1"))
                .thenReturn(true);
        when(store.assignmentRoundIsRunning("assignment-1")).thenReturn(true);
        when(store.assignmentUsesQuickReviewScope("assignment-1"))
                .thenReturn(true);
        InvestigationReviewTools tools = new InvestigationReviewTools(
                store, new ObjectMapper());

        ToolCallResult result = tools.executor("review-1", "assignment-1")
                .execute(new ToolCall(
                        "t1", "read_file", "{}",
                        new ObjectMapper().createObjectNode()));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("limited to the frozen pull-request diff");
        verify(store, never()).findRoundSnapshotByAssignment(any());
    }

    @Test
    void ordinaryFullReviewReadsFileFromThePersistedRoundSnapshot()
    {
        InvestigationReviewStore store = mock(InvestigationReviewStore.class);
        AgentReviewRow review = new AgentReviewRow(
                "review-1", "acme/widget", "pr-1", "base-1", "head-1",
                "ACTIVE", null, null, null);
        ReviewRoundSnapshot frozen = new ReviewRoundSnapshot(
                "round-1", "acme/widget", 42, "main", "Frozen review", "",
                "base-1", "head-1",
                "diff --git a/A.java b/A.java\n@@ -1 +1 @@\n-return false;\n+return true;\n",
                List.of(new DiffFile(
                        "A.java", "modified", 1, 1,
                        "@@ -1 +1 @@\n-return false;\n+return true;")),
                Map.of("A.java", "return true;\n"), null, null,
                ReviewCapabilities.frozenChangedFiles(), 1L);
        when(store.assignmentBelongsToReview("assignment-1", review.id()))
                .thenReturn(true);
        when(store.assignmentRoundIsRunning("assignment-1")).thenReturn(true);
        when(store.assignmentUsesQuickReviewScope("assignment-1"))
                .thenReturn(false);
        when(store.stepBelongsToAssignment("step-1", "assignment-1"))
                .thenReturn(true);
        when(store.findReview(review.id())).thenReturn(Optional.of(review));
        when(store.findRoundSnapshotByAssignment("assignment-1"))
                .thenReturn(Optional.of(frozen));
        when(store.mutateWhileAssignmentRoundRunning(any(), any())).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return true;
        });
        InvestigationReviewTools tools = new InvestigationReviewTools(
                store, new ObjectMapper());
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("step_id", "step-1");
        input.put("path", "A.java");

        ToolCallResult result = tools.executor(review.id(), "assignment-1")
                .execute(new ToolCall("t1", "read_file", "{}", input));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("return true");
    }
}
