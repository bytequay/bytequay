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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The pins arrive from the create dialog, so unknown audiences and
 *  unparseable choice ids must not reach the table. */
class TestThreadEngineOverrides
{
    private static final String THREAD_ID = "t-1";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ThreadEngineOverrides overrides = new ThreadEngineOverrides(jdbc);

    @Test
    void onlyKnownAudiencesWithParseableChoicesAreStored()
    {
        Map<String, String> requested = new LinkedHashMap<>();
        requested.put(SessionAudience.DEV, "cli:codex");
        requested.put("marketing", "cli:codex");
        requested.put(SessionAudience.REVIEW, "claude-opus-4-8");

        overrides.replace(THREAD_ID, requested);

        verify(jdbc).update(contains("DELETE"), eq(THREAD_ID));
        verify(jdbc).update(contains("INSERT"), eq(THREAD_ID), eq("dev"), eq("cli:codex"));
        verify(jdbc, never()).update(contains("INSERT"), eq(THREAD_ID), eq("marketing"), anyString());
        verify(jdbc, never()).update(contains("INSERT"), eq(THREAD_ID), eq("review"), anyString());
    }

    @Test
    void anEmptySetClearsThePinsWithoutInserting()
    {
        overrides.replace(THREAD_ID, Map.of());

        verify(jdbc).update(contains("DELETE"), eq(THREAD_ID));
        verify(jdbc, never()).update(contains("INSERT"), eq(THREAD_ID), anyString(), anyString());
    }

    @Test
    void aPinReadsBackAsTheChoicesWorkModel()
    {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(THREAD_ID), eq("plan")))
                .thenReturn(List.of("api:anthropic:work"));

        assertThat(overrides.forAudience(THREAD_ID, "plan"))
                .contains(new WorkModel(WorkModelKind.API, "anthropic", null, "work"));
        assertThat(overrides.forAudience(null, "plan")).isEmpty();
    }
}
