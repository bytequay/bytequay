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

import com.bytequay.app.workitem.GoalActivity;
import com.bytequay.app.workitem.GoalItemState;
import com.bytequay.app.workitem.GoalItemView;
import com.bytequay.app.workitem.GoalReferenceReader;
import com.bytequay.core.domain.GoalItemKind;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestWorkCandidateController
{
    @Test
    void searchesStoredItemsForTheAddBox()
            throws Exception
    {
        GoalReferenceReader references = mock(GoalReferenceReader.class);
        Instant now = Instant.parse("2026-09-28T10:00:00Z");
        when(references.candidates("workspace", "retry")).thenReturn(ImmutableList.of(new GoalItemView(GoalItemKind.PULL_REQUEST, "trinodb/trino#30702", "#30702",
                "Retry Iceberg commits", GoalItemState.MERGED, "Merged", false, Optional.of("https://github.com/trinodb/trino/pull/30702"),
                Optional.of(new GoalActivity("#30702 merged", now)), Optional.empty(), Optional.empty())));

        standaloneSetup(new WorkCandidateController(references)).build()
                .perform(get("/api/workspaces/workspace/goals/candidates").param("q", "retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("trinodb/trino#30702"))
                .andExpect(jsonPath("$[0].addedAt").value(nullValue()));
    }
}
