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

import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.AutomationPlan;
import com.bytequay.app.domain.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2AutomationPlanService
{
    @Test
    void approvesOnlyTheExactReviewedIssuePlanWithAutomationIdentity()
    {
        SqlitePlanRuntimeStore store = mock(SqlitePlanRuntimeStore.class);
        PlanRuntimeCoordinator coordinator = mock(PlanRuntimeCoordinator.class);
        AutomationPlan row = reviewed("high", "low", "small");
        when(store.listAutomationPlans(
                "workspace", Task.ORIGIN_ISSUE_MONITOR,
                Task.TYPE_WORKSPACE_ISSUE_TRIAGE)).thenReturn(List.of(row));
        when(store.hasOpenStewardship("revision")).thenReturn(false);
        V2AutomationPlanService service = new V2AutomationPlanService(
                store, coordinator, new ObjectMapper());

        V2AutomationPlanService.Snapshot snapshot = service.listCurrent(
                "workspace", Task.ORIGIN_ISSUE_MONITOR,
                Task.TYPE_WORKSPACE_ISSUE_TRIAGE).getFirst();
        service.approveIssueIntake(snapshot);

        ArgumentCaptor<PlanRuntimeCoordinator.AutomationPlanApprovalCommand> command =
                ArgumentCaptor.forClass(
                        PlanRuntimeCoordinator.AutomationPlanApprovalCommand.class);
        verify(coordinator).approvePlanByAutomation(command.capture());
        assertThat(command.getValue().automationKind())
                .isEqualTo(V2AutomationPlanService.ISSUE_INTAKE);
        assertThat(command.getValue().approval().actor())
                .isEqualTo("automation/remote-issue-intake");
        assertThat(command.getValue().approval().revisionId()).isEqualTo("revision");
        assertThat(command.getValue().approval().selfReviewId()).isEqualTo("review");
    }

    @Test
    void rejectsUnsafePlanAtTheTypedOwnerBoundary()
    {
        SqlitePlanRuntimeStore store = mock(SqlitePlanRuntimeStore.class);
        PlanRuntimeCoordinator coordinator = mock(PlanRuntimeCoordinator.class);
        AutomationPlan row = reviewed("medium", "low", "small");
        when(store.listAutomationPlans(
                "workspace", Task.ORIGIN_ISSUE_MONITOR,
                Task.TYPE_WORKSPACE_ISSUE_TRIAGE)).thenReturn(List.of(row));
        V2AutomationPlanService service = new V2AutomationPlanService(
                store, coordinator, new ObjectMapper());
        V2AutomationPlanService.Snapshot snapshot = service.listCurrent(
                "workspace", Task.ORIGIN_ISSUE_MONITOR,
                Task.TYPE_WORKSPACE_ISSUE_TRIAGE).getFirst();

        assertThatThrownBy(() -> service.approveIssueIntake(snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not high-confidence");
        verify(coordinator, never()).approvePlanByAutomation(any());
    }

    private static AutomationPlan reviewed(
            String confidence, String risk, String complexity)
    {
        String content = """
                {"status":"finalized","goal":"Fix it",
                 "intent":{"steps":[{"action":"Change it"}]},
                 "signals":{"confidence":"%s","riskLevel":"%s",
                 "estimatedComplexity":"%s"}}
                """.formatted(confidence, risk, complexity);
        return new AutomationPlan(
                "task", "trunk", "workspace", Task.ORIGIN_ISSUE_MONITOR,
                Task.TYPE_WORKSPACE_ISSUE_TRIAGE, 42, Instant.EPOCH,
                1, 5, "stage", 1L, 7L, "AWAITING_APPROVAL",
                "revision", content, "review", "REVIEWED", null);
    }
}
