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

import com.bytequay.app.workspace.ComposerSuggestion;
import com.bytequay.app.workspace.ComposerSuggestionService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static com.bytequay.app.workspace.ComposerSuggestionService.Command.BACKPORT;
import static com.bytequay.app.workspace.ComposerSuggestionService.Command.FIX;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestComposerSuggestionController
{
    private final ComposerSuggestionService suggestions = mock(ComposerSuggestionService.class);
    private final MockMvc mvc = standaloneSetup(new ComposerSuggestionController(suggestions)).build();

    @Test
    void routesCommandAndSearchWithinWorkspace()
            throws Exception
    {
        when(suggestions.list("workspace", FIX, "part")).thenReturn(ImmutableList.of(new ComposerSuggestion(
                7, "Repair partial updates", "https://github.com/owner/project/issues/7", "alice", Instant.parse("2026-08-01T00:00:00Z"))));
        mvc.perform(get("/api/workspaces/workspace/composer-suggestions").param("command", "FIX").param("search", "part"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(7))
                .andExpect(jsonPath("$[0].title").value("Repair partial updates"))
                .andExpect(jsonPath("$[0].url").value("https://github.com/owner/project/issues/7"))
                .andExpect(jsonPath("$[0].author").value("alice"));
        verify(suggestions).list("workspace", FIX, "part");
    }

    @Test
    void defaultsToUnfilteredRecommendations()
            throws Exception
    {
        when(suggestions.list("workspace", BACKPORT, "")).thenReturn(ImmutableList.of());
        mvc.perform(get("/api/workspaces/workspace/composer-suggestions").param("command", "BACKPORT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        verify(suggestions).list("workspace", BACKPORT, "");
    }

    @Test
    void rejectsUnknownCommand()
            throws Exception
    {
        mvc.perform(get("/api/workspaces/workspace/composer-suggestions").param("command", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(suggestions);
    }
}
