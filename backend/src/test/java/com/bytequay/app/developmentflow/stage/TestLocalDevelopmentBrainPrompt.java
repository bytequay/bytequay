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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.LocalValidationOperationHandler.ValidationResult;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainReviewRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationEvidence;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalDevelopmentBrainPrompt
{
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void launchInputAsksForTheVerdictThroughItsResultTool()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
        TaskManager tasks = mock(TaskManager.class);
        LocalDevelopmentStageManager local =
                mock(LocalDevelopmentStageManager.class);
        SqliteLocalDevelopmentRuntimeStore store =
                mock(SqliteLocalDevelopmentRuntimeStore.class);
        ValidationContext context = validationContext();
        ValidationEvidence evidence =
                new ValidationEvidence("validation-evidence", 1, true);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "local-stage", 1L, "validation-operation", 1,
                "fingerprint", "head", "base");
        ValidationResult validation = new ValidationResult(
                1, "validation-1", "validation-operation", "task-1", 1,
                "local-stage", 1, 1, true, true, List.of(),
                "fingerprint", "head", "base", 1, 2);
        String validationJson = json.writeValueAsString(validation);

        when(commands.execute(anyString(), any()))
                .thenAnswer(invocation ->
                        invocation.<Supplier<?>>getArgument(1).get());
        when(store.requireValidationTaskId("validation-operation"))
                .thenReturn("task-1");
        when(store.requireValidationContext("validation-operation"))
                .thenReturn(context);
        when(store.findValidationReceipt("validation-1"))
                .thenReturn(Optional.empty());
        when(store.completeValidation(
                eq(context), eq(true), eq("[]"), eq(validationJson),
                any(), any()))
                .thenReturn(evidence);
        when(store.insertBrainReview(
                eq(context), eq(evidence), anyString(), anyString(), anyString(),
                anyString(), eq("CLI"), eq(1), anyString(), eq(NOW)))
                .thenReturn(new BrainReviewRequest(
                        "brain-episode", "brain-turn", "brain-operation",
                        "brain-ticket", new ResultFence(
                                1, "local-stage", 1, "brain-operation", 1,
                                "fingerprint", "head", "base")));
        when(local.acceptValidationInCommand(any()))
                .thenReturn(CommandResult.applied(mock(StageManager.State.class)));
        when(tasks.requestBrainReviewInCommand(any()))
                .thenReturn(CommandResult.applied(mock(TaskManager.State.class)));

        LocalDevelopmentRuntimeCoordinator runtime =
                new LocalDevelopmentRuntimeCoordinator(
                        commands, tasks, local, store, mock(PRService.class), json,
                        Clock.fixed(NOW, ZoneOffset.UTC), 8080);

        DispatchTicket.DeliveryReceipt receipt = runtime.deliverValidation(
                new DispatchTicket.OwnerReference(
                        STAGE, "local-stage",
                        LocalValidationOperationHandler.CALLBACK_ROUTE),
                fence,
                new DispatchTicket.DispatchResult(
                        fence, SUCCEEDED, validationJson, validationJson, null));

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        ArgumentCaptor<String> launchInput =
                ArgumentCaptor.forClass(String.class);
        verify(store).insertBrainReview(
                eq(context), eq(evidence), anyString(), anyString(), anyString(),
                anyString(), eq("CLI"), eq(1), launchInput.capture(), eq(NOW));
        JsonNode launch = json.readTree(launchInput.getValue());
        // The review reports through record_development_verdict now. This
        // prompt used to forbid exactly that and demand a raw JSON final
        // message instead — a reviewer that wrote prose lost its whole review.
        assertThat(launch.path("prompt").asText())
                .contains("record_development_verdict")
                .contains("APPROVED or CHANGES_REQUESTED")
                .contains("Your final message is not read")
                .doesNotContain("Do not submit the verdict through a tool")
                .doesNotContain("exactly one raw JSON object")
                .doesNotContain("\"schemaVersion\":1");
        assertThat(launchInput.getValue())
                .doesNotContain("owner-scoped tool", "record_review_verdict");
    }

    private static ValidationContext validationContext()
    {
        return new ValidationContext(
                "validation-1", "validation-operation", "DISPATCHED", 1,
                "task-1", 1, "local-stage", 1, "report-1",
                "fingerprint", "head", "base",
                "implement intent", "commit summary", "one file",
                "checks passed", "none", "none", "[]",
                "ACTIVE", 1, 3, "local-stage", 1L,
                StageCheckpoint.VALIDATING, 4, false,
                "fingerprint", "head", "base", "/tmp/task-1",
                "trunk-1", "workspace-1",
                """
                        {"kind":"CLI","agentOrProvider":"codex",
                         "model":"gpt-5.6-sol","account":null,
                         "reasoningEffort":"LOW"}
                        """,
                "brain-1", "codex", "gpt-5.6-sol", null,
                "validation-ticket", "RESULT_PENDING");
    }
}
