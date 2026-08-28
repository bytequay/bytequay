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

import com.bytequay.app.service.RepoService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestRepoControllerCodeSnippet
{
    @Test
    void returnsNumberedCodeLines()
            throws Exception
    {
        RepoService repos = mock(RepoService.class);
        RepoController controller = new RepoController(
                repos,
                mock(WorkspaceRepositoryService.class),
                mock(WorkspaceMetadataService.class));
        String commit = "4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa";
        when(repos.getCodeSnippet("trinodb", "trino", commit, "src/Main.java", 796, 797))
                .thenReturn(ImmutableList.of("try (writer) {", "}"));

        standaloneSetup(controller).build().perform(get("/api/repos/trinodb/trino/code-snippet")
                        .queryParam("commit", commit)
                        .queryParam("path", "src/Main.java")
                        .queryParam("startLine", "796")
                        .queryParam("endLine", "797"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].number").value(796))
                .andExpect(jsonPath("$.lines[0].content").value("try (writer) {"))
                .andExpect(jsonPath("$.lines[1].number").value(797))
                .andExpect(jsonPath("$.lines[1].content").value("}"));

        verify(repos).getCodeSnippet("trinodb", "trino", commit, "src/Main.java", 796, 797);
    }
}
