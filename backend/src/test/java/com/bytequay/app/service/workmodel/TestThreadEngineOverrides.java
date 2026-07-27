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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.sqlite.WorkModelJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** New trunks persist one complete, immutable engine snapshot. */
class TestThreadEngineOverrides
{
    private static final String THREAD_ID = "t-1";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ThreadEngineOverrides overrides =
            new ThreadEngineOverrides(jdbc, mapper, entityManager);

    @Test
    void storesAllFourCompleteEngines()
    {
        Map<String, WorkModel> requested = snapshot();

        overrides.replace(THREAD_ID, requested);

        verify(entityManager).flush();
        verify(jdbc).update(contains("DELETE"), eq(THREAD_ID));
        verify(jdbc, times(4)).update(
                contains("INSERT"), eq(THREAD_ID), anyString(), anyString(), anyString());
        WorkModel review = requested.get(SessionAudience.REVIEW);
        verify(jdbc).update(
                contains("INSERT"),
                eq(THREAD_ID),
                eq(SessionAudience.REVIEW),
                eq("api:anthropic:work"),
                eq(WorkModelJson.serialise(mapper, review)));
    }

    @Test
    void rejectsAnIncompleteSnapshotBeforeDeletingExistingRows()
    {
        Map<String, WorkModel> incomplete = new LinkedHashMap<>(snapshot());
        incomplete.remove(SessionAudience.CI_FIX);

        assertThatThrownBy(() -> overrides.replace(THREAD_ID, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all session audiences");

        verify(entityManager, never()).flush();
        verify(jdbc, never()).update(contains("DELETE"), eq(THREAD_ID));
    }

    @Test
    void rejectsAMovingModelDefaultBeforeDeletingExistingRows()
    {
        Map<String, WorkModel> incomplete = new LinkedHashMap<>(snapshot());
        incomplete.put(SessionAudience.PLAN,
                new WorkModel(WorkModelKind.CLI, "codex", null, null));

        assertThatThrownBy(() -> overrides.replace(THREAD_ID, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model for plan is required");

        verify(entityManager, never()).flush();
        verify(jdbc, never()).update(contains("DELETE"), eq(THREAD_ID));
    }

    @Test
    void frozenJsonWinsOverTheLegacyChoice()
            throws Exception
    {
        WorkModel frozen = snapshot().get(SessionAudience.PLAN);
        stubRow("cli:claude-code", WorkModelJson.serialise(mapper, frozen));

        assertThat(overrides.forAudience(THREAD_ID, "plan")).contains(frozen);
        assertThat(overrides.forAudience(null, "plan")).isEmpty();
    }

    @Test
    void legacyRowsStillResolveTheirPickerChoice()
            throws Exception
    {
        stubRow("api:anthropic:work", null);

        assertThat(overrides.forAudience(THREAD_ID, "plan"))
                .contains(new WorkModel(WorkModelKind.API, "anthropic", null, "work"));
    }

    private void stubRow(String choice, String json)
            throws Exception
    {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("choice")).thenReturn(choice);
        when(resultSet.getString("work_model_json")).thenReturn(json);
        when(jdbc.query(
                anyString(),
                ArgumentMatchers.<RowMapper<WorkModel>>any(),
                eq(THREAD_ID),
                eq("plan")))
                .thenAnswer(invocation -> {
                    RowMapper<WorkModel> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                });
    }

    private static Map<String, WorkModel> snapshot()
    {
        Map<String, WorkModel> result = new LinkedHashMap<>();
        result.put(SessionAudience.PLAN,
                new WorkModel(WorkModelKind.CLI, "codex", "gpt-5.3-codex", null));
        result.put(SessionAudience.DEV,
                new WorkModel(WorkModelKind.CLI, "claude-code", "claude-opus-4-8", null));
        result.put(SessionAudience.REVIEW,
                new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-8", "work"));
        result.put(SessionAudience.CI_FIX,
                new WorkModel(WorkModelKind.API, "deepseek", "deepseek-v4-flash", null));
        return result;
    }
}
