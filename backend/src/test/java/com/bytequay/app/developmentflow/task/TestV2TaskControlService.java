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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.task.V2TaskControlService.PolicyRevisionRedriver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2TaskControlService
{
    @Test
    void retryCiExtendsOnlyTheCurrentExhaustedEpisode()
    {
        TaskManager tasks = mock(TaskManager.class);
        TaskManager.Store store = mock(TaskManager.Store.class);
        DispatchTicketControl tickets = mock(DispatchTicketControl.class);
        RemoteCiRepairRuntimeCoordinator ciRepair =
                mock(RemoteCiRepairRuntimeCoordinator.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskManager.State state = activeTask();
        when(store.findById("task-1")).thenReturn(Optional.of(state));
        when(jdbc.query(
                anyString(), ArgumentMatchers.<RowMapper<String>>any(),
                eq("task-1")))
                .thenReturn(List.of("episode-1"));
        V2TaskControlService controls = new V2TaskControlService(
                tasks, store, tickets, ciRepair, jdbc);

        assertThat(controls.retryFailedCi("task-1")).isSameAs(state);

        verify(ciRepair).extendBudget(
                eq("task-1"), eq("episode-1"), anyString(),
                eq(1), eq(1), eq(1), eq("user"),
                eq("explicit Retry CI action"));
    }

    @Test
    void policyRevisionImmediatelyRedrivesItsCurrentOwner()
    {
        TaskManager tasks = mock(TaskManager.class);
        TaskManager.Store store = mock(TaskManager.Store.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PolicyRevisionRedriver redriver = mock(PolicyRevisionRedriver.class);
        TaskManager.PolicyRevision current = policy(
                "policy-1", 1, false, false, 1);
        TaskManager.PolicyRevision revised = policy(
                "policy-2", 2, true, false, 1);
        when(tasks.policy("task-1")).thenReturn(current);
        when(store.findById("task-1")).thenReturn(Optional.of(activeTask()));
        when(tasks.revisePolicy(any())).thenReturn(CommandResult.applied(revised));
        V2TaskControlService controls = new V2TaskControlService(
                tasks, store, mock(DispatchTicketControl.class),
                mock(RemoteCiRepairRuntimeCoordinator.class), jdbc,
                mock(V2UserWaitStore.class), redriver);

        assertThat(controls.setAutoApprove("task-1", true)).isTrue();

        verify(redriver).redrive("task-1");
    }

    @Test
    void repeatedPolicyRequestStillRetriesAnInterruptedRedrive()
    {
        TaskManager tasks = mock(TaskManager.class);
        PolicyRevisionRedriver redriver = mock(PolicyRevisionRedriver.class);
        TaskManager.PolicyRevision current = policy(
                "policy-1", 1, true, false, 1);
        when(tasks.policy("task-1")).thenReturn(current);
        V2TaskControlService controls = new V2TaskControlService(
                tasks, mock(TaskManager.Store.class),
                mock(DispatchTicketControl.class),
                mock(RemoteCiRepairRuntimeCoordinator.class),
                mock(JdbcTemplate.class), mock(V2UserWaitStore.class), redriver);

        assertThat(controls.setMinApprovals("task-1", 1)).isOne();

        verify(tasks, never()).revisePolicy(any());
        verify(redriver).redrive("task-1");
    }

    private static TaskManager.State activeTask()
    {
        return new TaskManager.State(
                "task-1", "trunk-1", TaskLifecycle.ACTIVE,
                1, 4, "stage-1", null, null, null, null);
    }

    private static TaskManager.PolicyRevision policy(
            String id,
            int revision,
            boolean autoApprove,
            boolean autoMerge,
            int minApprovals)
    {
        return new TaskManager.PolicyRevision(
                id, "task-1", "trunk-1", revision,
                autoApprove, autoMerge, minApprovals,
                3, 2, true, "default");
    }
}
