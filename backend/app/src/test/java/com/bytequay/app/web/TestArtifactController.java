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

import com.bytequay.core.domain.Artifact;
import com.bytequay.core.store.ArtifactStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestArtifactController
{
    private static final Artifact PAGE = new Artifact("a1", "workspace", "Delete files", "<p>Readers merge deletes.</p>", Instant.parse("2026-10-19T10:00:00Z"));

    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final MockMvc mvc = standaloneSetup(new ArtifactController(artifacts)).build();

    @Test
    void listsTitlesWithoutPages()
            throws Exception
    {
        when(artifacts.list("workspace")).thenReturn(ImmutableList.of(PAGE.summary()));
        mvc.perform(get("/api/workspaces/workspace/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("a1"))
                .andExpect(jsonPath("$[0].title").value("Delete files"))
                .andExpect(jsonPath("$[0].html").doesNotExist());
    }

    @Test
    void readsAPageOnlyFromItsWorkspace()
            throws Exception
    {
        when(artifacts.find("workspace", "a1")).thenReturn(Optional.of(PAGE));
        when(artifacts.find("other", "a1")).thenReturn(Optional.empty());
        mvc.perform(get("/api/workspaces/workspace/artifacts/a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>Readers merge deletes.</p>"));
        mvc.perform(get("/api/workspaces/other/artifacts/a1"))
                .andExpect(status().isNotFound());
    }
}
