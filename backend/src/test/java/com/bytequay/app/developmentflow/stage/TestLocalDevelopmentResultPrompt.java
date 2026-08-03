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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.InitialContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.InitialTurn;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalDevelopmentResultPrompt
{
    @Test
    void initialImplementationReportsThroughTheResultTool()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        TaskCommandExecutor commands =
                new TaskCommandExecutor(new NoopTransactions());
        LocalDevelopmentStageManager local =
                mock(LocalDevelopmentStageManager.class);
        SqliteLocalDevelopmentRuntimeStore store =
                mock(SqliteLocalDevelopmentRuntimeStore.class);
        InitialContext context = new InitialContext(
                "task-1", "trunk-1", "workspace-1", 1, 1,
                "local-stage", 1, 0, StageCheckpoint.IMPLEMENTING,
                "fingerprint", "head", "base", "/tmp/task-1",
                """
                        {"kind":"CLI","agentOrProvider":"claude",
                         "model":"claude-opus-4-1","account":null,
                         "reasoningEffort":"HIGH"}
                        """,
                "claude", "claude-opus-4-1", null,
                "approval-1", "revision-1", "Change the left nav.", "digest");
        when(store.findInitialReceipt("task-1", "local-stage", "approval-1"))
                .thenReturn(Optional.empty());
        when(store.requireInitialContext("task-1", "local-stage", "approval-1"))
                .thenReturn(context);
        when(local.startInitialImplementationInCommand(any(), any(), any()))
                .thenReturn(CommandResult.applied(mock(StageManager.State.class)));
        LocalDevelopmentRuntimeCoordinator runtime =
                new LocalDevelopmentRuntimeCoordinator(
                        commands, mock(TaskManager.class), local, store,
                        mock(PRService.class), json,
                        Clock.fixed(
                                Instant.parse("2026-07-31T00:00:00Z"),
                                ZoneOffset.UTC),
                        8080);

        runtime.startInitialImplementation(
                "task-1", "local-stage", "approval-1");

        ArgumentCaptor<InitialTurn> turn =
                ArgumentCaptor.forClass(InitialTurn.class);
        verify(store).insertInitialTurn(turn.capture());
        JsonNode launch = json.readTree(turn.getValue().launchInput());
        assertResultTool(launch.path("prompt").asText());
        assertResultTool(launch.path("systemPrompt").asText());
    }

    /**
     * The Turn reports through record_development_result, so neither prompt
     * may still demand a raw-JSON final message — an agent told to do both
     * satisfies the wrong one, and the shape it was asked for is no longer
     * read at all.
     */
    private static void assertResultTool(String prompt)
    {
        assertThat(prompt)
                .contains("record_development_result")
                .contains("Your final message is not read")
                .doesNotContain("exactly one raw JSON object")
                .doesNotContain("schemaVersion");
    }

    private static final class NoopTransactions
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
