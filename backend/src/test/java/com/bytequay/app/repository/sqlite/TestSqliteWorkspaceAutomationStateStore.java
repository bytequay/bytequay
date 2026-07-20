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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.WorkspaceAutomationState;
import com.bytequay.app.repository.WorkspaceAutomationStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteWorkspaceAutomationStateStore
{
    @Autowired
    private WorkspaceAutomationStateStore states;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void upsertsGenericStateAndDeletesItWithTheWorkspace()
    {
        String workspaceId = "ws-automation-" + UUID.randomUUID();
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO workspaces (
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES (?, ?, '', 0, ?, ?)
                """, workspaceId, "Automation test", now, now);

        Instant firstUpdate = Instant.ofEpochMilli(now - 1_000);
        states.save(new WorkspaceAutomationState(
                workspaceId, "quality-scan", 7, "{\"outcome\":\"SUCCESS\"}", firstUpdate));
        assertThat(states.find(workspaceId, "quality-scan")).contains(
                new WorkspaceAutomationState(
                        workspaceId, "quality-scan", 7,
                        "{\"outcome\":\"SUCCESS\"}", firstUpdate));

        Instant secondUpdate = Instant.ofEpochMilli(now);
        states.save(new WorkspaceAutomationState(
                workspaceId, "quality-scan", 8, "{\"outcome\":\"FAILED\"}", secondUpdate));
        assertThat(states.find(workspaceId, "quality-scan")).contains(
                new WorkspaceAutomationState(
                        workspaceId, "quality-scan", 8,
                        "{\"outcome\":\"FAILED\"}", secondUpdate));

        jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
        assertThat(states.find(workspaceId, "quality-scan")).isEmpty();
    }
}
