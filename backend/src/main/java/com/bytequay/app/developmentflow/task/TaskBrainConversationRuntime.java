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
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.Attachment;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.ContinuationContext;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.ConversationContext;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.DeliveryContext;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.Message;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.NewTurn;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.ResultReceipt;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static java.util.Objects.requireNonNull;

/** Owner runtime for V2 Task Brain chat and its exact user-wait continuations. */
@Component
public final class TaskBrainConversationRuntime
{
    public static final String PURPOSE = "TASK_BRAIN_CONVERSATION";
    public static final String CALLBACK = "TASK_TURN_RESULT";

    private static final String ACTOR = "v2-task-brain-conversation";
    private static final Set<String> TERMINAL =
            Set.of("COMPLETED", "CANCELED", "REMOTE_CLOSED");

    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final SqliteTaskBrainConversationStore store;
    private final ChatAttachmentStore attachments;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final ObjectMapper json;
    private final ObjectReader workModelReader;
    private final Clock clock;
    private final int serverPort;
    private ReasoningEffortService reasoningEfforts;

    @Autowired
    public TaskBrainConversationRuntime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            SqliteTaskBrainConversationStore store,
            ChatAttachmentStore attachments,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            ObjectMapper json,
            @Value("${server.port:53123}") int serverPort)
    {
        this(commands, tasks, store, attachments, repositories, watchedRepos,
                json, Clock.systemUTC(), serverPort);
    }

    TaskBrainConversationRuntime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            SqliteTaskBrainConversationStore store,
            ChatAttachmentStore attachments,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.store = requireNonNull(store, "store is null");
        this.attachments = requireNonNull(attachments, "attachments is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.json = requireNonNull(json, "json is null");
        this.workModelReader = json.readerFor(WorkModel.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
    }

    @Autowired
    public void setReasoningEfforts(ReasoningEffortService reasoningEfforts)
    {
        this.reasoningEfforts = requireNonNull(
                reasoningEfforts, "reasoningEfforts is null");
    }

    public boolean isV2Task(String taskId)
    {
        return store.isV2Task(taskId);
    }

    public BrainMessageResponse sendMessage(
            String taskId, String text, List<String> imageDataUrls)
    {
        requireText(taskId, "taskId");
        String body = required(text, "text").trim();
        return commands.execute(taskId, () ->
                sendInCommand(taskId, body, imageDataUrls));
    }

    public DispatchTicket.DeliveryReceipt deliver(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        requireNonNull(result, "result is null");
        if (result.owner().kind() != DispatchTicket.OwnerKind.TASK_TURN
                || !CALLBACK.equals(result.owner().callbackRoute())) {
            return receipt(SUPERSEDED, "Task Brain conversation owner mismatch");
        }
        String taskId = store.requireConversationTaskId(
                result.owner().id(), result.fence().operationId());
        return commands.execute(taskId, () -> deliverInCommand(result));
    }

    /** Returns null when the exact owner/subject is no longer current. */
    public String continueUserWait(
            String sourceTurnId,
            String sourceOperationId,
            String waitKind,
            String waitId,
            String answer)
    {
        requireText(sourceTurnId, "sourceTurnId");
        requireText(sourceOperationId, "sourceOperationId");
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        String taskId = store.requireContinuationTaskId(
                sourceTurnId, sourceOperationId);
        return commands.execute(taskId, () -> continueUserWaitInCommand(
                sourceTurnId, sourceOperationId, waitKind, waitId, answer));
    }

    private BrainMessageResponse sendInCommand(
            String taskId, String body, List<String> imageDataUrls)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ConversationContext context = store.requireConversationContext(taskId);
        requireChatLifecycle(context);
        if (store.hasLiveConversationTurn(taskId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "another Task Brain message is still running");
        }
        WorkModel workModel = decodeWorkModel(context.workModelSnapshot());
        if (reasoningEfforts != null) {
            workModel = reasoningEfforts.forTask(
                    context.trunkId(), context.taskId(), workModel);
        }
        requireEngine(context.provider(), context.model(), workModel);
        boolean active = "ACTIVE".equals(context.lifecycle());
        Path workingDirectory = active
                ? exactPath(context.worktreePath())
                : repositoryRoot(context.workspaceId());
        List<Message> history = store.conversation(taskId);
        List<Attachment> historicalAttachments =
                store.conversationAttachments(taskId);
        List<String> paths = attachments.save(taskId, imageDataUrls);
        Instant now = clock.instant();
        String turnId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        String ticketId = UUID.randomUUID().toString();
        List<Attachment> saved = persistedAttachments(turnId, paths, now);
        List<AgentTurnProviderSession.ImageAttachment> launchImages =
                launchImages(historicalAttachments, saved);
        String fallbackPrompt = conversationPrompt(
                history, historicalAttachments, body, paths);
        SqliteTaskBrainConversationStore.CliSession session =
                workModel.kind() == WorkModelKind.CLI
                        ? store.latestSuccessfulCliSession(
                                taskId, context.taskEpoch(),
                                active ? context.stageId() : null,
                                active ? context.stageGeneration() : null,
                                context.codeFingerprint(), context.headSha(),
                                context.baseSha(), context.provider(), context.model(),
                                workingDirectory.toString()).orElse(null)
                        : null;
        String resumeSessionId = session == null
                ? null : session.providerSessionId();
        String prompt = resumeSessionId == null
                ? fallbackPrompt : conversationTurnPrompt(body, paths);
        String launch = launch(
                context.provider(), context.model(), context.roleSkill(), workModel,
                workingDirectory, turnId, operationId, prompt, launchImages,
                resumeSessionId,
                resumeSessionId == null ? null : fallbackPrompt,
                session == null ? 0 : session.cumulativeInputTokens(),
                session == null ? 0 : session.cumulativeOutputTokens());
        String lane = workModel.kind().name();
        int runnerLaneMask = workModel.kind() == WorkModelKind.CLI ? 1 : 2;
        int laneMask = active ? runnerLaneMask : runnerLaneMask | 8;
        NewTurn turn = new NewTurn(
                turnId, operationId, ticketId, PURPOSE,
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), active ? context.stageId() : null,
                active ? context.stageGeneration() : null,
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                1, lane, laneMask, active, false, CALLBACK, launch, now);
        Message user = new Message(
                "task-brain-user:" + turnId, turnId, 1, "USER", body, now);
        store.insertConversationTurn(turn, user, saved);
        return new BrainMessageResponse(turnId, context.trunkId());
    }

    private DispatchTicket.DeliveryReceipt deliverInCommand(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        TaskCommandExecutor.requireCurrent(
                store.requireConversationTaskId(
                        result.owner().id(), result.fence().operationId()));
        String rawDigest = digest(write(result));
        ResultReceipt duplicate = store.findResultReceipt(result.owner().id())
                .orElse(null);
        if (duplicate != null) {
            if (!duplicate.rawResultDigest().equals(rawDigest)) {
                throw new IllegalStateException(
                        "Task Brain conversation was delivered with different evidence");
            }
            return receipt(
                    DispatchTicket.Acceptance.valueOf(duplicate.acceptance()),
                    duplicate.evidence());
        }
        DeliveryContext context = store.requireDeliveryContext(
                result.owner().id(), result.fence().operationId());
        if (!context.fence().equals(toResultFence(result.fence()))) {
            throw new IllegalArgumentException(
                    "Task Brain conversation result differs from its persisted fence");
        }
        Instant now = clock.instant();
        if (!isCurrent(context)) {
            ResultReceipt recorded = store.finish(
                    context, result.outcome().name(), rawDigest,
                    SUPERSEDED.name(), "SUPERSEDED", null,
                    "Task Brain conversation subject is stale", now);
            return receipt(SUPERSEDED, evidence(recorded));
        }

        String finalText = result.payload().finalText();
        String terminalStatus;
        String assistant = null;
        String detail;
        if (result.outcome() == SUCCEEDED
                && finalText != null && !finalText.isBlank()) {
            terminalStatus = "SUCCEEDED";
            assistant = finalText.trim();
            detail = "Task Brain response accepted";
        }
        else if (result.outcome() == CANCELED) {
            terminalStatus = "CANCELED";
            detail = value(result.payload().error(), "Task Brain response canceled");
        }
        else {
            terminalStatus = "FAILED";
            detail = value(result.payload().error(),
                    result.outcome() == SUCCEEDED
                            ? "Task Brain returned no response"
                            : "Task Brain response failed");
        }
        ResultReceipt recorded = store.finish(
                context, result.outcome().name(), rawDigest, ACCEPTED.name(),
                terminalStatus, assistant, detail, now);
        return receipt(ACCEPTED, evidence(recorded));
    }

    private String continueUserWaitInCommand(
            String sourceTurnId,
            String sourceOperationId,
            String waitKind,
            String waitId,
            String answer)
    {
        String duplicate = store.findContinuationSuccessor(waitKind, waitId)
                .orElse(null);
        if (duplicate != null) {
            return duplicate;
        }
        ContinuationContext source = store.findContinuationContext(
                        sourceTurnId, sourceOperationId, waitKind, waitId)
                .orElse(null);
        if (source == null || !continuationCurrent(source)) {
            return null;
        }
        if (!PURPOSE.equals(source.purpose())) {
            TaskManager.ResultCommand owner = new TaskManager.ResultCommand(
                    id("task-brain-wait-preflight", waitKind + ":" + waitId),
                    ACTOR, source.taskId(), source.logicalFence());
            if (!tasks.isCurrentBrainResultInCommand(owner)) {
                return null;
            }
        }

        Instant now = clock.instant();
        String seed = waitKind + ":" + waitId;
        String turnId = id("task-brain-wait-turn", seed);
        String operationId = id("task-brain-wait-operation", seed);
        String ticketId = id("task-brain-wait-ticket", seed);
        String normalizedAnswer = value(answer, "The user resolved the request.").trim();
        String launch = continuationLaunch(
                source, turnId, operationId,
                waitKind, waitId, normalizedAnswer);
        NewTurn successor = new NewTurn(
                turnId, operationId, ticketId, source.purpose(),
                source.workspaceId(), source.trunkId(), source.taskId(),
                source.taskEpoch(), source.stageId(), source.stageGeneration(),
                source.codeFingerprint(), source.headSha(), source.baseSha(),
                source.sourceAttempt() + 1, source.deliveryLane(),
                source.laneMask(), source.exclusiveTask(),
                source.writerRequired(), source.callbackRoute(), launch, now);
        Message user = PURPOSE.equals(source.purpose())
                ? new Message(
                        "task-brain-user:" + turnId, turnId, 1, "USER",
                        normalizedAnswer, now)
                : null;
        store.insertContinuation(source, successor, waitKind, waitId, user);
        return turnId;
    }

    private boolean continuationCurrent(ContinuationContext source)
    {
        if (source.currentTaskEpoch() != source.taskEpoch()
                || !matches(source.codeFingerprint(),
                        source.currentCodeFingerprint())
                || !matches(source.headSha(), source.currentHeadSha())
                || !matches(source.baseSha(), source.currentBaseSha())) {
            return false;
        }
        if (PURPOSE.equals(source.purpose()) && source.stageId() == null) {
            return TERMINAL.contains(source.lifecycle());
        }
        return "ACTIVE".equals(source.lifecycle())
                && source.stageId() != null
                && source.stageId().equals(source.currentStageId())
                && Objects.equals(
                        source.stageGeneration(), source.currentStageGeneration())
                && !source.stageCompleted();
    }

    private static boolean isCurrent(DeliveryContext context)
    {
        if (context.currentTaskEpoch() != context.taskEpoch()
                || !matches(context.codeFingerprint(),
                        context.currentCodeFingerprint())
                || !matches(context.headSha(), context.currentHeadSha())
                || !matches(context.baseSha(), context.currentBaseSha())) {
            return false;
        }
        if (context.stageId() == null) {
            return TERMINAL.contains(context.lifecycle());
        }
        return "ACTIVE".equals(context.lifecycle())
                && context.stageId().equals(context.currentStageId())
                && Objects.equals(
                        context.stageGeneration(), context.currentStageGeneration())
                && !context.stageCompleted();
    }

    private void requireChatLifecycle(ConversationContext context)
    {
        if ("ACTIVE".equals(context.lifecycle())) {
            if (context.stageId() == null || context.stageGeneration() == null) {
                throw new IllegalStateException(
                        "Active Task Brain conversation requires its current Stage");
            }
        }
        else if (!TERMINAL.contains(context.lifecycle())) {
            throw new IllegalStateException(
                    "Task Brain conversation is unavailable while Task is "
                            + context.lifecycle());
        }
        requireText(context.codeFingerprint(), "codeFingerprint");
        requireText(context.headSha(), "headSha");
        requireText(context.baseSha(), "baseSha");
    }

    private String launch(
            String provider,
            String model,
            String roleSkill,
            WorkModel workModel,
            Path workingDirectory,
            String turnId,
            String operationId,
            String prompt,
            List<AgentTurnProviderSession.ImageAttachment> images,
            String resumeSessionId,
            String fallbackPrompt,
            long priorCumulativeInputTokens,
            long priorCumulativeOutputTokens)
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay", endpointUrl(turnId, operationId),
                        DispatchTicket.OwnerKind.TASK_TURN, turnId, operationId,
                        AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY,
                        "mcp__bytequay__approval_prompt");
        AgentTurnOperationHandler.LaunchInput input =
                new AgentTurnOperationHandler.LaunchInput(
                        1, workModel.kind() == WorkModelKind.CLI
                                ? AgentTurnProviderSession.Transport.CLI
                                : AgentTurnProviderSession.Transport.API,
                        provider, workModel.account(), model,
                        workModel.reasoningEffort(), workingDirectory.toString(),
                        systemPrompt(roleSkill), prompt, images, endpoint,
                        resumeSessionId, fallbackPrompt,
                        priorCumulativeInputTokens,
                        priorCumulativeOutputTokens);
        return write(input);
    }

    private String continuationLaunch(
            ContinuationContext source,
            String turnId,
            String operationId,
            String waitKind,
            String waitId,
            String answer)
    {
        try {
            JsonNode parsed = json.readTree(source.launchInput());
            if (!(parsed instanceof ObjectNode launch)) {
                throw new IllegalArgumentException("launch input is not an object");
            }
            String prompt = launch.hasNonNull("fallbackPrompt")
                    ? required(launch.path("fallbackPrompt").asText(null),
                            "fallbackPrompt")
                    : required(launch.path("prompt").asText(null), "prompt");
            StringBuilder reconstructed = new StringBuilder(prompt);
            if (source.executionId() != null) {
                List<String> trace = store.executionLog(source.executionId());
                if (!trace.isEmpty()) {
                    reconstructed.append(
                            "\n\nDurable provider trace from the prior Turn:\n");
                    trace.forEach(event -> reconstructed.append(event).append('\n'));
                }
            }
            String fallbackPrompt = reconstructed
                    + "\n\nThe prior execution paused for "
                    + waitKind.toLowerCase(Locale.ROOT)
                    + " " + waitId + ". The user's answer is:\n" + answer
                    + "\n\nContinue the same operation from that answer. Do not repeat "
                    + "work already completed before the pause.";
            if ("CLI".equals(source.deliveryLane())
                    && source.providerSessionId() != null
                    && (!"codex".equals(launch.path("provider").asText())
                    || (source.cumulativeInputTokens() != null
                    && source.cumulativeOutputTokens() != null))) {
                launch.put("prompt", "The prior execution paused for "
                        + waitKind.toLowerCase(Locale.ROOT) + " " + waitId
                        + ". The user's answer is:\n" + answer
                        + "\n\nContinue the same operation from that answer.");
                launch.put("resumeSessionId", source.providerSessionId());
                launch.put("fallbackPrompt", fallbackPrompt);
                launch.put("priorCumulativeInputTokens",
                        source.cumulativeInputTokens() == null
                                ? 0 : source.cumulativeInputTokens());
                launch.put("priorCumulativeOutputTokens",
                        source.cumulativeOutputTokens() == null
                                ? 0 : source.cumulativeOutputTokens());
            }
            else {
                launch.put("prompt", fallbackPrompt);
                launch.remove("resumeSessionId");
                launch.remove("fallbackPrompt");
                launch.remove("priorCumulativeInputTokens");
                launch.remove("priorCumulativeOutputTokens");
            }
            JsonNode endpointNode = launch.path("toolEndpoint");
            if (!(endpointNode instanceof ObjectNode endpoint)) {
                throw new IllegalArgumentException("tool endpoint is missing");
            }
            endpoint.put("url", endpointUrl(turnId, operationId));
            endpoint.put("ownerId", turnId);
            endpoint.put("operationId", operationId);
            applyCurrentEffort(source, launch);
            return write(launch);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Frozen Task Brain continuation launch is invalid", e);
        }
    }

    private void applyCurrentEffort(
            ContinuationContext source, ObjectNode launch)
    {
        if (reasoningEfforts == null) {
            return;
        }
        AgentTurnProviderSession.Transport transport =
                AgentTurnProviderSession.Transport.valueOf(
                        required(launch.path("transport").asText(null),
                                "transport"));
        WorkModel frozen = new WorkModel(
                transport == AgentTurnProviderSession.Transport.CLI
                        ? WorkModelKind.CLI : WorkModelKind.API,
                required(launch.path("provider").asText(null), "provider"),
                required(launch.path("model").asText(null), "model"),
                launch.path("credentialAccount").isTextual()
                        ? launch.path("credentialAccount").asText() : null,
                launch.path("reasoningEffort").isTextual()
                        ? launch.path("reasoningEffort").asText() : null);
        WorkModel current = reasoningEfforts.forTask(
                source.trunkId(), source.taskId(), frozen);
        if (current.reasoningEffort() == null) {
            launch.putNull("reasoningEffort");
        }
        else {
            launch.put("reasoningEffort", current.reasoningEffort());
        }
    }

    private String endpointUrl(String turnId, String operationId)
    {
        return "http://127.0.0.1:" + serverPort
                + "/api/v2/task-turns/" + turnId
                + "/operations/" + operationId + "/mcp";
    }

    private static String conversationPrompt(
            List<Message> history,
            List<Attachment> historicalAttachments,
            String body,
            List<String> paths)
    {
        StringBuilder prompt = new StringBuilder(
                "Answer the user's Task-level question using read-only tools. "
                        + "Do not edit files, mutate workflow state, or create remote effects.\n");
        if (!history.isEmpty()) {
            prompt.append("\nConversation so far:\n");
            for (Message message : history) {
                prompt.append(message.role().equals("USER") ? "User: " : "Brain: ")
                        .append(message.body()).append('\n');
            }
        }
        if (!historicalAttachments.isEmpty()) {
            prompt.append(
                    "\nImages from the earlier conversation (managed read-only files):\n");
            historicalAttachments.forEach(attachment -> prompt
                    .append("- ").append(attachment.contentRef()).append('\n'));
        }
        prompt.append("\nUser: ").append(body);
        if (!paths.isEmpty()) {
            prompt.append("\n\nAttached images (managed read-only files):\n");
            paths.forEach(path -> prompt.append("- ").append(path).append('\n'));
        }
        prompt.append("\nReply directly to the user.");
        return prompt.toString();
    }

    private List<Attachment> persistedAttachments(
            String turnId, List<String> paths, Instant now)
    {
        List<Attachment> saved = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            ChatAttachmentStore.Attachment attachment = attachments.read(path);
            saved.add(new Attachment(
                    "task-brain-attachment:" + turnId + ":" + (index + 1),
                    path, attachment.mimeType(),
                    digest(attachment.bytes()), now));
        }
        return List.copyOf(saved);
    }

    private List<AgentTurnProviderSession.ImageAttachment> launchImages(
            List<Attachment> historical, List<Attachment> current)
    {
        List<AgentTurnProviderSession.ImageAttachment> images =
                new ArrayList<>(historical.size() + current.size());
        for (Attachment attachment : historical) {
            images.add(verifiedImage(attachment));
        }
        for (Attachment attachment : current) {
            images.add(verifiedImage(attachment));
        }
        return List.copyOf(images);
    }

    private AgentTurnProviderSession.ImageAttachment verifiedImage(
            Attachment attachment)
    {
        String contentRef = required(
                attachment.contentRef(), "attachment.contentRef");
        String mediaType = required(
                attachment.mediaType(), "attachment.mediaType");
        String expectedDigest = required(
                attachment.digest(), "attachment.digest");
        ChatAttachmentStore.Attachment content =
                attachments.read(contentRef);
        if (!mediaType.equals(content.mimeType())
                || !expectedDigest.equals(digest(content.bytes()))) {
            throw new IllegalStateException(
                    "durable Task Brain attachment changed: " + contentRef);
        }
        return new AgentTurnProviderSession.ImageAttachment(
                contentRef, mediaType, expectedDigest);
    }

    private static String conversationTurnPrompt(String body, List<String> paths)
    {
        StringBuilder prompt = new StringBuilder("User: ").append(body);
        if (!paths.isEmpty()) {
            prompt.append("\n\nAttached images (managed read-only files):\n");
            paths.forEach(path -> prompt.append("- ").append(path).append('\n'));
        }
        return prompt.append("\nReply directly to the user.").toString();
    }

    private static String systemPrompt(String roleSkill)
    {
        String base = "You are the read-only Task Brain for a V2 development "
                + "Task. Explain, review, and investigate the exact Task subject. "
                + "You must not modify files, transition Task or Stage state, "
                + "or create remote effects.";
        return roleSkill == null || roleSkill.isBlank()
                ? base : base + "\n\nRole skill:\n" + roleSkill;
    }

    private WorkModel decodeWorkModel(String snapshot)
    {
        try {
            return workModelReader.readValue(snapshot);
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Frozen Task work model is invalid", e);
        }
    }

    private static void requireEngine(
            String provider, String model, WorkModel workModel)
    {
        if (!provider.equals(workModel.agentOrProvider())
                || workModel.model() != null && !workModel.model().isBlank()
                    && !model.equals(workModel.model())
                || workModel.kind() == WorkModelKind.CLI
                    && workModel.account() != null
                || workModel.kind() == WorkModelKind.CLI
                    && !Set.of("codex", "claude-code").contains(provider)) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model do not identify one engine");
        }
    }

    private Path repositoryRoot(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repository =
                repositories.resolve(workspaceId);
        WatchedRepo watched = watchedRepos.find(
                        repository.owner(), repository.repo())
                .orElseThrow(() -> new IllegalStateException(
                        "Workspace repository has no watched clone: "
                                + repository.fullName()));
        Path root = exactPath(watched.localClonePath());
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "Workspace repository clone is unavailable: " + root);
        }
        return root;
    }

    private static Path exactPath(String value)
    {
        requireText(value, "workingDirectory");
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalStateException(
                    "working directory is not an exact absolute path");
        }
        return path;
    }

    private String evidence(ResultReceipt receipt)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "TASK_BRAIN_CONVERSATION_DELIVERY_V1");
        node.put("acceptance", receipt.acceptance());
        node.put("turnId", receipt.turnId());
        node.put("operationId", receipt.operationId());
        node.put("terminalStatus", receipt.terminalStatus());
        node.put("detail", receipt.evidence());
        return write(node);
    }

    private static ResultFence toResultFence(
            DispatchTicket.OperationFence fence)
    {
        return new ResultFence(
                requireNonNull(fence.taskEpoch()), fence.stageId(),
                fence.stageGeneration() == null ? 0 : fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
    }

    private static boolean matches(String expected, String current)
    {
        return expected == null || expected.equals(current);
    }

    private static String id(String namespace, String value)
    {
        return UUID.nameUUIDFromBytes(
                ("bytequay-v2:" + namespace + ":" + value)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String digest(byte[] value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String digest(String value)
    {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode Task Brain evidence", e);
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(acceptance, evidence);
    }

    private static String value(String value, String fallback)
    {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String value, String name)
    {
        requireText(value, name);
        return value;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
