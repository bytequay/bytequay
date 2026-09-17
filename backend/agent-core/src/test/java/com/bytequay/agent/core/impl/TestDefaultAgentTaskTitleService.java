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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskNames;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultAgentTaskTitleService
{
    private static final String RESULT = """
            {"title":"Fix duplicate table samples","branch":"fix-dup-table-sample-issue-31178"}
            """;
    private static final AgentTaskDefaults DEFAULTS = new AgentTaskDefaults(Optional.of("model-a"), Optional.of("high"), AgentPermissionPolicy.AUTO);

    @TempDir
    private Path temporaryDirectory;

    private AgentResourceService resources;
    private AgentDriver driver;
    private AgentSession session;
    private RunningTurn turn;
    private DefaultAgentTaskTitleService service;

    @BeforeEach
    void setUp()
    {
        resources = mock(AgentResourceService.class);
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        driver = mock(AgentDriver.class);
        session = mock(AgentSession.class);
        turn = mock(RunningTurn.class);
        when(drivers.require(AgentLaneId.CODEX_CLI)).thenReturn(driver);
        when(resources.allocate(any())).thenReturn(new TaskResources(temporaryDirectory, temporaryDirectory.resolve("events.jsonl"),
                temporaryDirectory.resolve("blobs"), temporaryDirectory.resolve("provider")));
        when(driver.open(any(), any())).thenReturn(session);
        when(session.start(any())).thenReturn(turn);
        complete(RESULT);
        service = new DefaultAgentTaskTitleService(resources, drivers, () -> "Common instructions", Duration.ofMillis(100));
    }

    @Test
    void namesTheWholeRequestInAnIsolatedTextOnlySession()
            throws Exception
    {
        String request = "Develop the following GitHub issue\n" + "Boilerplate ".repeat(200)
                + "\nIssue #31178: TABLESAMPLE emits duplicate tables. Please fix duplicate table samples.\n\t\"quoted\"";
        assertThat(service.generate(AgentLaneId.CODEX_CLI, DEFAULTS, request))
                .contains(new TaskNames("Fix duplicate table samples", "fix-dup-table-sample-issue-31178"));

        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any());
        assertThat(context.getValue().textOnly()).isTrue();
        assertThat(context.getValue().worktree()).isEqualTo(temporaryDirectory.resolve("naming-worktree"));
        assertThat(context.getValue().model()).contains("model-a");
        assertThat(context.getValue().effort()).contains("high");
        assertThat(context.getValue().managedInstructions()).isEqualTo("Common instructions");
        ArgumentCaptor<AgentTurnCommand> command = ArgumentCaptor.forClass(AgentTurnCommand.class);
        verify(session).start(command.capture());
        String prompt = command.getValue().message();
        assertThat(prompt).contains("Infer the intended change", "Read the issue body for intent", "No username");
        String encoded = prompt.substring(prompt.indexOf("Request and context as a JSON string:\n") + "Request and context as a JSON string:\n".length());
        assertThat(new ObjectMapper().readValue(encoded, String.class)).isEqualTo(request);
        verify(session).close();
        verify(resources).delete(TaskId.valueOf(context.getValue().taskId()));
    }

    @Test
    void rejectsMalformedNamesInsteadOfSilentlyTruncatingOrUsingUnsafeBranches()
    {
        complete("{\"title\":\"Fix samples\",\"branch\":\"refs/heads/main\"}");
        assertThat(service.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Fix samples")).isEmpty();
        verify(session).close();
        verify(resources).delete(any());
    }

    @Test
    void failedNamingLeavesTheCallerToChooseAFallback()
    {
        when(turn.completion()).thenReturn(CompletableFuture.completedFuture(new AgentTurnResult(
                AgentTurnResult.State.FAILED, Optional.empty(), Optional.of("unavailable"), Duration.ZERO)));
        assertThat(service.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Fix samples")).isEmpty();
        verify(session).close();
        verify(resources).delete(any());
    }

    @Test
    void cancelsAStuckNamingTurn()
    {
        when(turn.completion()).thenReturn(new CompletableFuture<>());
        assertThat(service.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Fix samples")).isEmpty();
        verify(turn).cancel();
        verify(session).close();
        verify(resources).delete(any());
    }

    @Test
    void retainsScratchAndRejectsResultWhenShutdownCannotBeVerified()
    {
        doThrow(new IllegalStateException("still running")).when(session).close();
        assertThat(service.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Fix samples")).isEmpty();
        verify(resources, never()).delete(any());
    }

    private void complete(String response)
    {
        when(turn.completion()).thenReturn(CompletableFuture.completedFuture(new AgentTurnResult(
                AgentTurnResult.State.COMPLETED, Optional.of(response), Optional.empty(), Duration.ZERO)));
    }
}
