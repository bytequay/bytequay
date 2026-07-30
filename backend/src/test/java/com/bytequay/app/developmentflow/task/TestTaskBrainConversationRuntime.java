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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.beans.brain.BrainMessageResponse;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.Attachment;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.CliSession;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.ContinuationContext;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.ConversationContext;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.Message;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.NewTurn;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskBrainConversationRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-30T04:00:00Z");

    @Test
    void nextCliMessageResumesTheTaskBrainAndFreezesCompleteHistory()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        SqliteTaskBrainConversationStore store =
                mock(SqliteTaskBrainConversationStore.class);
        ChatAttachmentStore attachments = mock(ChatAttachmentStore.class);
        when(attachments.save("task-1", List.of())).thenReturn(List.of());
        when(store.requireConversationContext("task-1")).thenReturn(
                new ConversationContext(
                        "task-1", "trunk-1", "workspace-1", "ACTIVE", 1,
                        "stage-1", 1L, "LOCAL_DEVELOPMENT", "fingerprint-1",
                        "head-1", "base-1", "/tmp/task-1", "acme/widget",
                        json.writeValueAsString(new WorkModel(
                                WorkModelKind.CLI, "codex", "gpt-5.6", null,
                                "high")),
                        "codex", "gpt-5.6", "task brain role"));
        when(store.conversation("task-1")).thenReturn(List.of(
                new Message(
                        "message-1", "turn-1", 1, "USER",
                        "What changed?", NOW.minusSeconds(2)),
                new Message(
                        "message-2", "turn-1", 2, "ASSISTANT",
                        "The parser changed.", NOW.minusSeconds(1))));
        when(store.conversationAttachments("task-1"))
                .thenReturn(List.of());
        when(store.latestSuccessfulCliSession(
                "task-1", 1, "stage-1", 1L,
                "fingerprint-1", "head-1", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .thenReturn(Optional.of(new CliSession(
                        "session-task-brain-1", 100, 40)));
        TaskBrainConversationRuntime runtime = new TaskBrainConversationRuntime(
                new TaskCommandExecutor(new Transactions()),
                mock(TaskManager.class), store, attachments,
                mock(WorkspaceRepositoryResolver.class),
                mock(WatchedRepoStore.class), json,
                Clock.fixed(NOW, ZoneOffset.UTC), 53123);

        BrainMessageResponse response = runtime.sendMessage(
                "task-1", "What should I do next?", List.of());

        var turn = ArgumentCaptor.forClass(NewTurn.class);
        verify(store).insertConversationTurn(turn.capture(), any(), anyList());
        AgentTurnOperationHandler.LaunchInput launch = json.readValue(
                turn.getValue().launchInput(),
                AgentTurnOperationHandler.LaunchInput.class);
        assertThat(response.brainThreadId()).isEqualTo("trunk-1");
        assertThat(launch.resumeSessionId()).isEqualTo("session-task-brain-1");
        assertThat(launch.priorCumulativeInputTokens()).isEqualTo(100);
        assertThat(launch.priorCumulativeOutputTokens()).isEqualTo(40);
        assertThat(launch.prompt())
                .contains("What should I do next?")
                .doesNotContain("What changed?", "The parser changed.");
        assertThat(launch.fallbackPrompt())
                .contains("What changed?", "The parser changed.",
                        "What should I do next?");
        verify(store).latestSuccessfulCliSession(
                eq("task-1"), eq(1L), eq("stage-1"), eq(1L),
                eq("fingerprint-1"), eq("head-1"), eq("base-1"),
                eq("codex"), eq("gpt-5.6"),
                eq("/tmp/task-1"));
    }

    @Test
    void cliResumeCarriesCurrentAndHistoricalImagesIntoItsFreshFallback()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        SqliteTaskBrainConversationStore store =
                mock(SqliteTaskBrainConversationStore.class);
        ChatAttachmentStore attachments = mock(ChatAttachmentStore.class);
        String historicalPath = "/tmp/task-1/historical.png";
        byte[] historicalBytes = {1, 2, 3};
        Attachment historical = new Attachment(
                "attachment-old", historicalPath, "image/png",
                digest(historicalBytes), NOW.minusSeconds(2));
        String currentPath = "/tmp/task-1/current.png";
        byte[] currentBytes = {4, 5, 6};
        when(attachments.save(
                "task-1", List.of("data:image/png;base64,current")))
                .thenReturn(List.of(currentPath));
        when(attachments.read(historicalPath)).thenReturn(
                new ChatAttachmentStore.Attachment(
                        historicalBytes, "image/png"));
        when(attachments.read(currentPath)).thenReturn(
                new ChatAttachmentStore.Attachment(currentBytes, "image/png"));
        when(store.requireConversationContext("task-1")).thenReturn(
                activeContext(json, new WorkModel(
                        WorkModelKind.CLI, "codex", "gpt-5.6", null,
                        "high"), "codex", "gpt-5.6"));
        when(store.conversation("task-1")).thenReturn(List.of(
                new Message(
                        "message-1", "turn-1", 1, "USER",
                        "Please inspect the screenshot", NOW.minusSeconds(2)),
                new Message(
                        "message-2", "turn-1", 2, "ASSISTANT",
                        "I inspected it", NOW.minusSeconds(1))));
        when(store.conversationAttachments("task-1"))
                .thenReturn(List.of(historical));
        when(store.latestSuccessfulCliSession(
                "task-1", 1, "stage-1", 1L,
                "fingerprint-1", "head-1", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .thenReturn(Optional.of(new CliSession(
                        "session-task-brain-1", 100, 40)));
        TaskBrainConversationRuntime runtime = runtime(
                store, attachments, json);

        runtime.sendMessage(
                "task-1", "Compare it with this image",
                List.of("data:image/png;base64,current"));

        var turn = ArgumentCaptor.forClass(NewTurn.class);
        verify(store).insertConversationTurn(turn.capture(), any(), anyList());
        AgentTurnOperationHandler.LaunchInput launch = json.readValue(
                turn.getValue().launchInput(),
                AgentTurnOperationHandler.LaunchInput.class);
        assertThat(launch.resumeSessionId()).isEqualTo("session-task-brain-1");
        assertThat(launch.images())
                .extracting(AgentTurnProviderSession.ImageAttachment::path)
                .containsExactly(historicalPath, currentPath);
        assertThat(launch.images())
                .extracting(AgentTurnProviderSession.ImageAttachment::digest)
                .containsExactly(digest(historicalBytes), digest(currentBytes));
        assertThat(launch.prompt())
                .contains("Compare it with this image", currentPath)
                .doesNotContain(historicalPath);
        assertThat(launch.fallbackPrompt()).contains(
                "Please inspect the screenshot", "I inspected it",
                historicalPath, "Compare it with this image", currentPath);
    }

    @Test
    void apiMessageUploadsCurrentAndHistoricalConversationImages()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        SqliteTaskBrainConversationStore store =
                mock(SqliteTaskBrainConversationStore.class);
        ChatAttachmentStore attachments = mock(ChatAttachmentStore.class);
        String historicalPath = "/tmp/task-1/historical.jpg";
        byte[] historicalBytes = {7, 8, 9};
        Attachment historical = new Attachment(
                "attachment-old", historicalPath, "image/jpeg",
                digest(historicalBytes), NOW.minusSeconds(1));
        String currentPath = "/tmp/task-1/current.png";
        byte[] currentBytes = {10, 11, 12};
        when(attachments.save(
                "task-1", List.of("data:image/png;base64,current")))
                .thenReturn(List.of(currentPath));
        when(attachments.read(historicalPath)).thenReturn(
                new ChatAttachmentStore.Attachment(
                        historicalBytes, "image/jpeg"));
        when(attachments.read(currentPath)).thenReturn(
                new ChatAttachmentStore.Attachment(currentBytes, "image/png"));
        when(store.requireConversationContext("task-1")).thenReturn(
                activeContext(json, new WorkModel(
                        WorkModelKind.API, "openai", "gpt-5.6", "work",
                        "high"), "openai", "gpt-5.6"));
        when(store.conversation("task-1")).thenReturn(List.of(
                new Message(
                        "message-1", "turn-1", 1, "USER",
                        "Earlier image", NOW.minusSeconds(1))));
        when(store.conversationAttachments("task-1"))
                .thenReturn(List.of(historical));
        TaskBrainConversationRuntime runtime = runtime(
                store, attachments, json);

        runtime.sendMessage(
                "task-1", "Current image",
                List.of("data:image/png;base64,current"));

        var turn = ArgumentCaptor.forClass(NewTurn.class);
        verify(store).insertConversationTurn(turn.capture(), any(), anyList());
        AgentTurnOperationHandler.LaunchInput launch = json.readValue(
                turn.getValue().launchInput(),
                AgentTurnOperationHandler.LaunchInput.class);
        assertThat(launch.transport())
                .isEqualTo(AgentTurnProviderSession.Transport.API);
        assertThat(launch.resumeSessionId()).isNull();
        assertThat(launch.fallbackPrompt()).isNull();
        assertThat(launch.prompt()).contains(
                "Earlier image", historicalPath, "Current image", currentPath);
        assertThat(launch.images())
                .extracting(AgentTurnProviderSession.ImageAttachment::path)
                .containsExactly(historicalPath, currentPath);
        verify(store, never()).latestSuccessfulCliSession(
                any(), anyLong(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void rejectsASecondMessageUntilThePriorConversationTurnIsTerminal()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        SqliteTaskBrainConversationStore store =
                mock(SqliteTaskBrainConversationStore.class);
        ChatAttachmentStore attachments = mock(ChatAttachmentStore.class);
        when(store.requireConversationContext("task-1")).thenReturn(
                new ConversationContext(
                        "task-1", "trunk-1", "workspace-1", "ACTIVE", 1,
                        "stage-1", 1L, "LOCAL_DEVELOPMENT", "fingerprint-1",
                        "head-1", "base-1", "/tmp/task-1", "acme/widget",
                        json.writeValueAsString(new WorkModel(
                                WorkModelKind.CLI, "codex", "gpt-5.6", null,
                                "high")),
                        "codex", "gpt-5.6", "task brain role"));
        when(store.hasLiveConversationTurn("task-1")).thenReturn(true);
        TaskBrainConversationRuntime runtime = new TaskBrainConversationRuntime(
                new TaskCommandExecutor(new Transactions()),
                mock(TaskManager.class), store, attachments,
                mock(WorkspaceRepositoryResolver.class),
                mock(WatchedRepoStore.class), json,
                Clock.fixed(NOW, ZoneOffset.UTC), 53123);

        assertThatThrownBy(() -> runtime.sendMessage(
                "task-1", "Do this next", List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409))
                .hasMessageContaining("still running");
        verify(store, never()).conversation("task-1");
        verify(store, never()).conversationAttachments("task-1");
        verify(store, never()).insertConversationTurn(any(), any(), anyList());
        verify(attachments, never()).save(any(), any());
    }

    @Test
    void userWaitFallbackIncludesTheExactProviderTrace()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        SqliteTaskBrainConversationStore store =
                mock(SqliteTaskBrainConversationStore.class);
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay", "http://127.0.0.1:53123/api/v2/"
                                + "task-turns/turn-1/operations/operation-1/mcp",
                        DispatchTicket.OwnerKind.TASK_TURN,
                        "turn-1", "operation-1",
                        AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY,
                        "mcp__bytequay__approval_prompt");
        String oldLaunch = json.writeValueAsString(
                new AgentTurnOperationHandler.LaunchInput(
                        1, AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", "high", "/tmp/task-1",
                        "brain role", "initial task question", endpoint));
        ContinuationContext source = new ContinuationContext(
                "turn-1", "operation-1", TaskBrainConversationRuntime.PURPOSE,
                "task-1", 1, 1, "stage-1", 1L,
                "fingerprint-1", "head-1", "base-1", "CLI", oldLaunch,
                "execution-1", "session-1", 0L, 0L,
                "turn-1", "operation-1", 1,
                TaskBrainConversationRuntime.CALLBACK, 1, true, false,
                "trunk-1", "workspace-1", "ACTIVE", 1,
                "stage-1", 1L, false,
                "fingerprint-1", "head-1", "base-1");
        when(store.requireContinuationTaskId("turn-1", "operation-1"))
                .thenReturn("task-1");
        when(store.findContinuationContext(
                "turn-1", "operation-1", "QUESTION", "question-1"))
                .thenReturn(Optional.of(source));
        when(store.executionLog("execution-1"))
                .thenReturn(List.of("tool activity before the wait"));
        TaskBrainConversationRuntime runtime = new TaskBrainConversationRuntime(
                new TaskCommandExecutor(new Transactions()),
                mock(TaskManager.class), store, mock(ChatAttachmentStore.class),
                mock(WorkspaceRepositoryResolver.class),
                mock(WatchedRepoStore.class), json,
                Clock.fixed(NOW, ZoneOffset.UTC), 53123);

        runtime.continueUserWait(
                "turn-1", "operation-1", "QUESTION", "question-1",
                "Use the safer option");

        ArgumentCaptor<NewTurn> successor = ArgumentCaptor.forClass(NewTurn.class);
        verify(store).insertContinuation(
                eq(source), successor.capture(), eq("QUESTION"),
                eq("question-1"), any());
        AgentTurnOperationHandler.LaunchInput launch = json.readValue(
                successor.getValue().launchInput(),
                AgentTurnOperationHandler.LaunchInput.class);
        assertThat(launch.resumeSessionId()).isEqualTo("session-1");
        assertThat(launch.prompt())
                .contains("Use the safer option")
                .doesNotContain("initial task question");
        assertThat(launch.fallbackPrompt()).contains(
                "initial task question", "tool activity before the wait",
                "Use the safer option");
    }

    private static TaskBrainConversationRuntime runtime(
            SqliteTaskBrainConversationStore store,
            ChatAttachmentStore attachments,
            ObjectMapper json)
    {
        return new TaskBrainConversationRuntime(
                new TaskCommandExecutor(new Transactions()),
                mock(TaskManager.class), store, attachments,
                mock(WorkspaceRepositoryResolver.class),
                mock(WatchedRepoStore.class), json,
                Clock.fixed(NOW, ZoneOffset.UTC), 53123);
    }

    private static ConversationContext activeContext(
            ObjectMapper json, WorkModel workModel, String provider, String model)
            throws Exception
    {
        return new ConversationContext(
                "task-1", "trunk-1", "workspace-1", "ACTIVE", 1,
                "stage-1", 1L, "LOCAL_DEVELOPMENT", "fingerprint-1",
                "head-1", "base-1", "/tmp/task-1", "acme/widget",
                json.writeValueAsString(workModel), provider, model,
                "task brain role");
    }

    private static String digest(byte[] content)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class Transactions
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction, TransactionDefinition definition)
        {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status)
        {
        }
    }
}
