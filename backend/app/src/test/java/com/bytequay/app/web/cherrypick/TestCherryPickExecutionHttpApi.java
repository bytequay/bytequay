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
package com.bytequay.app.web.cherrypick;

import com.bytequay.app.agent.AgentProperties;
import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.cherrypick.StartCherryPickExecutionCommand;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.AgentController;
import com.bytequay.app.web.agent.AgentRequestFilter;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestCherryPickExecutionHttpApi
{
    private static final String SESSION = "desktop-session";
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final String BODY = """
            {"repository":"/tmp/clone","worktreeParent":"/tmp/worktrees","previewId":"preview-1",
             "expectedSourceBaseSha":"%s","expectedSourceTipSha":"%s","expectedTargetSha":"%s",
             "userSkippedSourceShas":["%s"],"laneId":"claude","idempotencyKey":"key-1"}
            """.formatted("b".repeat(40), "c".repeat(40), "a".repeat(40), "2".repeat(40));

    private CherryPickExecutionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        service = mock(CherryPickExecutionService.class);
        AgentProperties properties = new AgentProperties(
                Path.of("/tmp/agent-runtime"), Duration.ofMinutes(30), SESSION, "codex", false, "claude", false);
        mvc = standaloneSetup(new CherryPickExecutionController(service))
                .addFilters(new AgentRequestFilter(properties, new ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void protectsEveryEndpointWithTheDesktopSession()
            throws Exception
    {
        mvc.perform(get("/api/cherry-pick-ranges/executions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cherry-pick-ranges/executions/any")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cherry-pick-ranges/executions")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cherry-pick-ranges/executions/any/cancel").param("repository", "/tmp/clone"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).start(any(), any(), any());
        verify(service, never()).cancel(any(), any());
    }

    @Test
    void passesTheConfirmedSkipsThroughUnchanged()
            throws Exception
    {
        when(service.start(any(), any(), any())).thenReturn(execution());

        mvc.perform(post("/api/cherry-pick-ranges/executions")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.workBranch").value("bump-477-478-a7"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].result").value("PENDING"))
                .andExpect(jsonPath("$.items[1].result").value("USER_SKIPPED"))
                .andExpect(jsonPath("$.currentPosition").value(1));

        ArgumentCaptor<StartCherryPickExecutionCommand> command =
                ArgumentCaptor.forClass(StartCherryPickExecutionCommand.class);
        ArgumentCaptor<Path> repository = ArgumentCaptor.forClass(Path.class);
        verify(service).start(command.capture(), repository.capture(), any());
        assertThat(command.getValue().userSkippedSourceShas()).containsExactly("2".repeat(40));
        assertThat(command.getValue().idempotencyKey()).isEqualTo("key-1");
        assertThat(repository.getValue()).isEqualTo(Path.of("/tmp/clone"));
    }

    @Test
    void mapsAStalePreviewToBadRequestAndALiveExecutionToConflict()
            throws Exception
    {
        doThrow(new CherryPickInvalidRequestException("Preview is stale"))
                .when(service).start(any(), any(), any());
        mvc.perform(post("/api/cherry-pick-ranges/executions")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Preview is stale"));

        doThrow(new CherryPickConflictException("Another cherry-pick execution is already running"))
                .when(service).start(any(), any(), any());
        mvc.perform(post("/api/cherry-pick-ranges/executions")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsRelativePathsAndUnknownExecutions()
            throws Exception
    {
        mvc.perform(post("/api/cherry-pick-ranges/executions")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("/tmp/clone", "relative/clone")))
                .andExpect(status().isBadRequest());
        verify(service, never()).start(any(), any(), any());

        mvc.perform(get("/api/cherry-pick-ranges/executions/not-a-uuid")
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isBadRequest());

        CherryPickExecutionId missing = CherryPickExecutionId.random();
        when(service.get(missing)).thenThrow(new CherryPickNotFoundException(missing));
        mvc.perform(get("/api/cherry-pick-ranges/executions/" + missing)
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isNotFound());
    }

    private static CherryPickExecution execution()
    {
        return CherryPickExecution.create(
                CherryPickExecutionId.random(),
                "key-1",
                new Workspace(
                        RepoRef.of("trinodb", "trino"),
                        "origin",
                        "cork",
                        "master",
                        "cork-master",
                        "bump-477-478-a7",
                        Path.of("/tmp/worktrees/bump-477-478-a7"),
                        "claude"),
                new Range("b".repeat(40), "c".repeat(40), "a".repeat(40)),
                ImmutableList.of(
                        CherryPickItem.planned(1, "1".repeat(40), "9".repeat(40), "Kept",
                                Optional.empty(), Disposition.TO_CHERRY_PICK),
                        CherryPickItem.planned(2, "2".repeat(40), "8".repeat(40), "Skipped",
                                Optional.empty(), Disposition.USER_SKIPPED)),
                NOW);
    }
}
