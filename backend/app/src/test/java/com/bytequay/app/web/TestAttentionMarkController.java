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
package com.bytequay.app.web;

import com.bytequay.app.service.attention.AttentionMarkService;
import com.bytequay.core.domain.AttentionMark;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAttentionMarkController
{
    private static final AttentionMark SNOOZED = new AttentionMark("trinodb/trino#31402", AttentionMark.Kind.SNOOZE, Optional.of(Instant.parse("2026-10-20T01:00:00Z")),
            "2026-10-19T03:00:00Z", Optional.of("[\"open\"]"), "tomorrow", Optional.of("{\"key\":\"trinodb/trino#31402\"}"));

    private final AttentionMarkService marks = mock(AttentionMarkService.class);
    // Boot's own serialization settings, so the test asserts the wire format the renderer parses.
    private final MockMvc http = standaloneSetup(new AttentionMarkController(marks)).setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .build();

    @Test
    void listsTheMarks()
            throws Exception
    {
        when(marks.marks()).thenReturn(ImmutableList.of(SNOOZED));

        http.perform(get("/api/attention/marks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("trinodb/trino#31402"))
                .andExpect(jsonPath("$[0].kind").value("SNOOZE"))
                .andExpect(jsonPath("$[0].until").value("2026-10-20T01:00:00Z"))
                .andExpect(jsonPath("$[0].snoozedItem").value("{\"key\":\"trinodb/trino#31402\"}"));
    }

    @Test
    void appliesTheChangesTogether()
            throws Exception
    {
        http.perform(post("/api/attention/marks").contentType(MediaType.APPLICATION_JSON).content("""
                {"saved": [{"key": "trinodb/trino#31402", "kind": "SNOOZE", "until": "2026-10-20T01:00:00Z", "seenUpdatedAt": "2026-10-19T03:00:00Z",
                  "seenActivityVersion": "[\\"open\\"]", "label": "tomorrow", "snoozedItem": "{\\"key\\":\\"trinodb/trino#31402\\"}"}],
                 "removed": ["task:7d0e"]}
                """)).andExpect(status().isNoContent());

        verify(marks).change(ImmutableList.of(SNOOZED), ImmutableList.of("task:7d0e"));
    }

    @Test
    void refusesAMarkTheListCouldNotHaveMade()
            throws Exception
    {
        // Only a snooze ends at a time.
        http.perform(post("/api/attention/marks").contentType(MediaType.APPLICATION_JSON).content("""
                {"saved": [{"key": "task:7d0e", "kind": "ACK", "until": "2026-10-20T01:00:00Z", "seenUpdatedAt": "2026-10-19T03:00:00Z", "label": "acknowledged"}],
                 "removed": []}
                """)).andExpect(status().isBadRequest());
        http.perform(post("/api/attention/marks").contentType(MediaType.APPLICATION_JSON).content("{\"saved\": [], \"removed\": [\" \"]}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/attention/marks").contentType(MediaType.APPLICATION_JSON).content("{\"saved\": []}")).andExpect(status().isBadRequest());

        verifyNoInteractions(marks);
    }
}
