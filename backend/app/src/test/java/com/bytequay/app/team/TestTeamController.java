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
package com.bytequay.app.team;

import com.bytequay.app.team.TeamCircle.Avatar;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.core.domain.RepositoryContributor;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.bytequay.core.hosting.HostingServer.GITHUB_COM;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestTeamController
{
    @Test
    void exposesEachAccountsDirectoryAndPrivateCircleCommands()
            throws Exception
    {
        TeamService service = mock(TeamService.class);
        TeamDirectory directory = new TeamDirectory(7, "alice", GITHUB_COM, ImmutableList.of(
                new TeamCircle("circle", "Friends", "Trusted maintainers", new Avatar("teal", "circle"), ImmutableList.of(),
                        ImmutableList.of(new TeamMember(42, "bob", "Bob", "")))));
        when(service.directories()).thenReturn(ImmutableList.of(directory));
        when(service.contributors(7)).thenReturn(ImmutableList.of(new RepositoryContributor(42, "bob", "avatar", false)));
        when(service.create(7, "Friends", "Trusted maintainers", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(new TeamMemberInput(42, "bob")))).thenReturn(directory);
        when(service.update(7, "circle", "Work", "Internal work", new Avatar("blue", "circle"), ImmutableList.of("acme/app"))).thenReturn(directory);
        when(service.addMember(7, "circle", new TeamMemberInput(42, "bob"))).thenReturn(directory);
        when(service.removeMember(7, "circle", 42)).thenReturn(directory);
        when(service.remove(7, "circle")).thenReturn(directory);
        MockMvc mvc = standaloneSetup(new TeamController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(7))
                .andExpect(jsonPath("$[0].accountLogin").value("alice"))
                .andExpect(jsonPath("$[0].server.provider").value("GITHUB"))
                .andExpect(jsonPath("$[0].server.webBaseUrl").value("https://github.com"))
                .andExpect(jsonPath("$[0].circles[0].description").value("Trusted maintainers"))
                .andExpect(jsonPath("$[0].circles[0].avatar.color").value("teal"))
                .andExpect(jsonPath("$[0].circles[0].avatar.shape").value("circle"))
                .andExpect(jsonPath("$[0].circles[0].members[0].remoteId").value(42));
        mvc.perform(get("/api/teams/7/contributors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].login").value("bob"));
        mvc.perform(post("/api/teams/7/circles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Friends\",\"description\":\"Trusted maintainers\",\"members\":[{\"remoteId\":42,\"login\":\"bob\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/teams/7/circles/circle").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Work\",\"description\":\"Internal work\",\"avatar\":{\"color\":\"blue\",\"shape\":\"circle\"},\"repositories\":[\"acme/app\"]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/teams/7/circles/circle").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Work\",\"avatar\":{\"color\":\"chartreuse\",\"shape\":\"circle\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/teams/7/circles/circle/members").contentType(MediaType.APPLICATION_JSON).content("{\"remoteId\":42,\"login\":\"bob\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/teams/7/circles/circle/members").contentType(MediaType.APPLICATION_JSON).content("{\"login\":\"bob\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/teams/7/circles").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Invalid\",\"members\":[{\"remoteId\":0,\"login\":\"bob\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/teams/7/circles/circle/members/42")).andExpect(status().isOk());
        mvc.perform(delete("/api/teams/7/circles/circle")).andExpect(status().isOk());
        mvc.perform(delete("/api/teams/7/circles/circle/members/not-a-number")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/teams/not-an-account/contributors")).andExpect(status().isBadRequest());
        verify(service).create(7, "Friends", "Trusted maintainers", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(new TeamMemberInput(42, "bob")));
        verify(service).update(7, "circle", "Work", "Internal work", new Avatar("blue", "circle"), ImmutableList.of("acme/app"));
        verify(service).addMember(7, "circle", new TeamMemberInput(42, "bob"));
        verify(service).removeMember(7, "circle", 42);
        verify(service).remove(7, "circle");
    }
}
