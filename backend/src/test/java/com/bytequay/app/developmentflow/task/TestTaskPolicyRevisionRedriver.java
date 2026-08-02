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

import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemotePolicyRedriveRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskPolicyRevisionRedriver
{
    @Test
    void restartScanRedrivesPlanAndRemoteThroughTheirOwners()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlanRuntimeCoordinator plans = mock(PlanRuntimeCoordinator.class);
        RemotePolicyRedriveRuntime remote = mock(RemotePolicyRedriveRuntime.class);
        when(jdbc.queryForList(
                anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("task-1", "task-2"));

        new TaskPolicyRevisionRedriver(jdbc, plans, remote)
                .maintain(Instant.parse("2026-07-29T00:00:00Z"));

        verify(plans).redrivePolicyApproval("task-1");
        verify(plans).redrivePolicyApproval("task-2");
        verify(remote).redrive("task-1");
        verify(remote).redrive("task-2");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(
                sql.capture(), eq(String.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("task.lifecycle_state = 'ACTIVE'")
                .contains("owner.checkpoint = 'AWAITING_APPROVAL'")
                .contains("MAX(latest.revision)")
                .contains("readiness.id IS NULL")
                .contains("readiness.ready = 0")
                .contains("snapshot.merge_queue_capability <> 'UNKNOWN'")
                .contains("policy.auto_merge = 1")
                .contains("remote_merge_authorization");
    }
}
