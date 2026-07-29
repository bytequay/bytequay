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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalAutoPublishRedriver
{
    @Test
    void eligibleSubjectUsesOneStableAutomaticPublishCommand()
            throws Exception
    {
        V2PrRemoteControlService controls = mock(V2PrRemoteControlService.class);
        LocalAutoPublishRedriver redriver = new LocalAutoPublishRedriver(
                new CandidateJdbc(), controls);

        redriver.maintain(Instant.parse("2026-07-29T00:00:00Z"));
        redriver.maintain(Instant.parse("2026-07-29T00:01:00Z"));

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(controls, times(2)).approveAndShip(
                commands.capture(), eq("task-1"), eq("pr-1"), eq(false));
        assertThat(commands.getAllValues())
                .hasSize(2)
                .containsOnly(commands.getValue());
        assertThat(commands.getValue()).isNotBlank();
    }

    private static final class CandidateJdbc
            extends JdbcTemplate
    {
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args)
        {
            try {
                ResultSet row = mock(ResultSet.class);
                when(row.getString(anyString())).thenAnswer(invocation -> switch (
                        invocation.getArgument(0, String.class)) {
                    case "task_id" -> "task-1";
                    case "policy_revision_id" -> "policy-1";
                    case "stage_id" -> "stage-1";
                    case "report_id" -> "report-1";
                    case "validation_id" -> "validation-1";
                    case "brain_id" -> "brain-1";
                    case "pr_id" -> "pr-1";
                    default -> null;
                });
                when(row.getLong("task_epoch")).thenReturn(1L);
                when(row.getLong("generation")).thenReturn(1L);
                when(row.getLong("stage_version")).thenReturn(7L);
                return List.of(mapper.mapRow(row, 0));
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
