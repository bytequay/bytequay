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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE_TURN;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK_TURN;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.WORKTREE_WRITE;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.CANCELED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.STAGE_DEVELOPMENT;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.CLI;
import static com.bytequay.app.developmentflow.execution.agentturn.CliAgentTurnProviderSession.CliProvider.CLAUDE_CODE;
import static com.bytequay.app.developmentflow.execution.agentturn.CliAgentTurnProviderSession.CliProvider.CODEX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCliAgentTurnProviderSession
{
    private static final Path WORKTREE = Path.of("/tmp/agent-turn-worktree");

    @Test
    void openIsInertAndCancellationBeforeStartNeverSpawns()
            throws Exception
    {
        AgentTurnProviderSession.Observer observer =
                mock(AgentTurnProviderSession.Observer.class);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> "/definitely/not/an/executable");

        try (AgentTurnProviderSession.Session session = provider.open(
                request(READ_ONLY), observer)) {
            verifyNoInteractions(observer);

            session.cancel();
            AgentTurnProviderSession.Result result = session.startAndAwait(null);

            assertThat(result.completion()).isEqualTo(CANCELED);
            assertThat(result.processPid()).isNull();
            verifyNoInteractions(observer);
        }
    }

    @Test
    void worktreeWriteCannotStartWithoutAuthorizedFence()
            throws Exception
    {
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> "/definitely/not/an/executable");
        try (AgentTurnProviderSession.Session session = provider.open(
                request(WORKTREE_WRITE), mock(AgentTurnProviderSession.Observer.class))) {
            assertThatThrownBy(() -> session.startAndAwait(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("writer fence");
        }
    }

    @Test
    void cancellationTearsDownDescendantsAndParent()
    {
        Process process = mock(Process.class);
        ProcessHandle parent = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        ProcessHandle grandchild = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child, grandchild));
        when(process.toHandle()).thenReturn(parent);

        CliAgentTurnProviderSession.stopProcessTree(process);

        InOrder teardown = inOrder(grandchild, child, parent);
        teardown.verify(grandchild).destroyForcibly();
        teardown.verify(child).destroyForcibly();
        teardown.verify(parent).destroyForcibly();
    }

    @Test
    void commandsExposeOnlyTheFrozenBytequayMcpEndpoint()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnProviderSession.Request request = request(READ_ONLY);
        List<String> codex = CliAgentTurnProviderSession.buildArgv(
                request, CODEX, "codex", null);

        assertThat(codex).contains(
                "--ignore-user-config",
                "mcp_servers={bytequay={url=\"" + endpoint().url()
                        + "\",default_tools_approval_mode=\"approve\"}}");
        assertThat(codex).noneMatch(value ->
                value.startsWith("mcp_servers.bytequay."));
        assertThat(codex).doesNotContain("--dangerously-bypass-approvals-and-sandbox");

        Path config = CliAgentTurnProviderSession.createMcpConfig(endpoint(), mapper);
        try {
            List<String> claude = CliAgentTurnProviderSession.buildArgv(
                    request, CLAUDE_CODE, "claude", config);
            assertThat(claude).contains(
                    "--setting-sources", "",
                    "--mcp-config", config.toString(),
                    "--strict-mcp-config",
                    "--permission-prompt-tool", "mcp__bytequay__approval_prompt");
            assertThat(mapper.readTree(Files.readString(config)))
                    .isEqualTo(mapper.readTree("""
                            {"mcpServers":{"bytequay":{"type":"http","url":"%s"}}}
                            """.formatted(endpoint().url())));
        }
        finally {
            CliAgentTurnProviderSession.deleteMcpConfig(config);
        }
        assertThat(config).doesNotExist();
    }

    @Test
    void endpointUrlMustNameTheSameTypedTurn()
    {
        assertThatThrownBy(() -> new AgentTurnProviderSession.OwnerToolEndpoint(
                "bytequay",
                "http://127.0.0.1:53123/api/v2/task-turns/other-turn/"
                        + "operations/operation-1/mcp",
                TASK_TURN,
                "task-turn-1",
                "operation-1",
                TASK_BRAIN_READ_ONLY,
                "mcp__bytequay__approval_prompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact typed Turn");
    }

    private static AgentTurnProviderSession.Request request(
            AgentTurnProviderSession.Access access)
    {
        return new AgentTurnProviderSession.Request(
                CLI,
                "codex",
                null,
                "gpt-5.6",
                "high",
                WORKTREE,
                "system",
                "prompt",
                endpoint(access),
                access);
    }

    private static AgentTurnProviderSession.OwnerToolEndpoint endpoint()
    {
        return endpoint(READ_ONLY);
    }

    private static AgentTurnProviderSession.OwnerToolEndpoint endpoint(
            AgentTurnProviderSession.Access access)
    {
        boolean write = access == WORKTREE_WRITE;
        return new AgentTurnProviderSession.OwnerToolEndpoint(
                "bytequay",
                write
                        ? "http://127.0.0.1:53123/api/v2/stage-turns/stage-turn-1/"
                                + "operations/operation-1/mcp"
                        : "http://127.0.0.1:53123/api/v2/task-turns/task-turn-1/"
                                + "operations/operation-1/mcp",
                write ? STAGE_TURN : TASK_TURN,
                write ? "stage-turn-1" : "task-turn-1",
                "operation-1",
                write ? STAGE_DEVELOPMENT : TASK_BRAIN_READ_ONLY,
                "mcp__bytequay__approval_prompt");
    }
}
