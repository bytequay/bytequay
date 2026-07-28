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

import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2TaskControlService
{
    @Test
    void retryCiExtendsOnlyTheCurrentExhaustedEpisode()
    {
        TaskManager tasks = mock(TaskManager.class);
        TaskManager.Store store = mock(TaskManager.Store.class);
        ExecutionDispatcher dispatcher = mock(ExecutionDispatcher.class);
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
                tasks, store, dispatcher, ciRepair, jdbc);

        assertThat(controls.retryFailedCi("task-1")).isSameAs(state);

        verify(ciRepair).extendBudget(
                eq("task-1"), eq("episode-1"), anyString(),
                eq(1), eq(1), eq(1), eq("user"),
                eq("explicit Retry CI action"));
    }

    private static TaskManager.State activeTask()
    {
        return new TaskManager.State(
                "task-1", "trunk-1", TaskLifecycle.ACTIVE,
                1, 4, "stage-1", null, null, null, null);
    }
}
