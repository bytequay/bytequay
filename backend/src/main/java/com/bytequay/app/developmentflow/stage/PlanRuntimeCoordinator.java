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
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.ProvisionContext;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.ProvisionReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.TurnRequest;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static java.util.Objects.requireNonNull;

/** Task-owned provisioning acceptance and Plan TaskTurn orchestration. */
public final class PlanRuntimeCoordinator
{
    public static final String PROVISION_CALLBACK = "TASK_PROVISION_RESULT";
    public static final String TURN_CALLBACK = "TASK_TURN_RESULT";

    private static final String PLAN_DRAFT = "PLAN_DRAFT";
    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final PlanStageManager plan;
    private final SqlitePlanRuntimeStore store;
    private final ObjectMapper json;
    private final ObjectReader provisionEvidenceReader;
    private final ObjectReader workModelReader;
    private final Clock clock;
    private final int serverPort;

    public PlanRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            PlanStageManager plan,
            SqlitePlanRuntimeStore store,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.plan = requireNonNull(plan, "plan is null");
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.provisionEvidenceReader = json.readerFor(
                        ProvisionTaskOperationHandler.ProvisionEvidence.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.workModelReader = json.readerFor(WorkModel.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
    }

    public DispatchTicket.DeliveryReceipt deliverProvisioning(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.TASK
                || !PROVISION_CALLBACK.equals(owner.callbackRoute())) {
            return receipt(SUPERSEDED, "provision owner mismatch");
        }
        if (!expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "raw provision fence is stale");
        }
        if (rawResult.outcome() != SUCCEEDED) {
            return receipt(ACCEPTED, "provision operation ended "
                    + rawResult.outcome().name());
        }

        ProvisionTaskOperationHandler.ProvisionEvidence evidence =
                decodeProvisionEvidence(rawResult.evidenceJson());
        ProvisionTaskOperationHandler.ProvisionEvidence payload =
                decodeProvisionEvidence(rawResult.payloadJson());
        if (!evidence.equals(payload)) {
            throw new IllegalArgumentException(
                    "Provision payload and evidence must be identical typed values");
        }
        requireProvisionEvidence(owner, expectedFence, evidence);

        ProvisionReceipt accepted = commands.execute(owner.id(), () ->
                acceptProvisioningInCommand(evidence, rawResult.evidenceJson()));
        return receipt(ACCEPTED, provisionReceiptJson(accepted));
    }

    private ProvisionReceipt acceptProvisioningInCommand(
            ProvisionTaskOperationHandler.ProvisionEvidence evidence,
            String evidenceJson)
    {
        TaskCommandExecutor.requireCurrent(evidence.taskId());
        String evidenceDigest = digest(evidenceJson);
        ProvisionReceipt duplicate = store.findProvisionReceipt(
                        evidence.taskId(), evidence.operationId())
                .orElse(null);
        if (duplicate != null) {
            if (!evidenceDigest.equals(duplicate.evidenceDigest())) {
                throw new IllegalStateException(
                        "Provision operation was accepted with different evidence");
            }
            return duplicate;
        }

        ProvisionContext context = store.requireProvisionContext(
                evidence.taskId(), evidence.operationId());
        if (!"PROVISIONING".equals(context.lifecycle())
                || !"DISPATCHED".equals(context.provisionStatus())
                || context.taskEpoch() != evidence.taskEpoch()
                || context.taskVersion() != 0
                || context.provisionAttempt() != 1
                || !context.repositoryId().equals(evidence.repositoryId())
                || !context.branchName().equals(evidence.branchName())
                || !context.worktreePath().equals(evidence.worktreePath())) {
            throw new IllegalStateException(
                    "Decoded provisioning result does not own the frozen Task context");
        }

        String planStageId = id("plan-stage", evidence.operationId());
        String draftTurnId = id("plan-draft-turn", evidence.operationId());
        String draftOperationId = id("plan-draft-operation", evidence.operationId());
        String draftTicketId = id("plan-draft-ticket", evidence.operationId());
        Instant now = clock.instant();

        TaskManager.StageOpening opening = tasks.acceptProvisionedCodeInCommand(
                new TaskManager.ProvisionedCode(
                        evidence.taskId(), evidence.taskEpoch(), evidence.operationId(),
                        context.provisionAttempt(), evidence.repositoryId(),
                        evidence.branchName(), evidence.worktreePath(), evidence.baseSha(),
                        evidence.headSha(), evidence.codeFingerprint(), evidenceJson),
                id("accept-provision", evidence.operationId()),
                "v2-plan-runtime", planStageId, 1);
        PlanStageManager.AcceptedOpening opened = plan.openFromTaskInCommand(opening);
        CommandResult<StageManager.State> stage = opened.stage();

        TurnRequest draft = turn(
                context, evidence, planStageId, 1, PLAN_DRAFT,
                draftTurnId, draftOperationId, draftTicketId,
                draftPrompt(context), now);
        store.insertTurn(draft);
        CommandResult<StageManager.State> requested = plan.requestDraftInCommand(
                new StageManager.Command(
                        id("request-plan-draft", evidence.operationId()),
                        "v2-plan-runtime", evidence.taskId(), evidence.taskEpoch(),
                        planStageId, 1, stage.state().version()),
                draft.turnId(), draft.fence());
        if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Initial Plan draft request was superseded");
        }

        ProvisionReceipt receipt = new ProvisionReceipt(
                evidence.taskId(), evidence.operationId(), evidenceDigest,
                planStageId, 1, draftTurnId, draftOperationId, draftTicketId, now);
        store.insertProvisionReceipt(receipt);
        return receipt;
    }

    private TurnRequest turn(
            ProvisionContext context,
            ProvisionTaskOperationHandler.ProvisionEvidence code,
            String stageId,
            long stageGeneration,
            String purpose,
            String turnId,
            String operationId,
            String ticketId,
            String prompt,
            Instant requestedAt)
    {
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        if (!context.provider().equals(model.agentOrProvider())
                || model.model() != null && !model.model().isBlank()
                    && !context.model().equals(model.model())
                || model.kind() == WorkModelKind.CLI && model.account() != null) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model do not identify one engine");
        }
        String lane = model.kind().name();
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", lane);
        launch.put("provider", context.provider());
        putNullable(launch, "credentialAccount", model.account());
        launch.put("model", context.model());
        putNullable(launch, "reasoningEffort", model.reasoningEffort());
        launch.put("workingDirectory", context.worktreePath());
        launch.put("systemPrompt", systemPrompt(context.roleSkill(), purpose));
        launch.put("prompt", prompt);
        ObjectNode endpoint = launch.putObject("toolEndpoint");
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:" + serverPort
                + "/api/v2/task-turns/" + turnId
                + "/operations/" + operationId + "/mcp");
        endpoint.put("ownerKind", "TASK_TURN");
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", "TASK_BRAIN_READ_ONLY");
        endpoint.put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return new TurnRequest(
                turnId, operationId, ticketId, purpose, context.workspaceId(),
                context.trunkId(), context.taskId(), context.taskEpoch(), stageId,
                stageGeneration, code.codeFingerprint(), code.headSha(), code.baseSha(),
                lane, laneMask, write(launch), requestedAt);
    }

    private String draftPrompt(ProvisionContext context)
    {
        String assignment = switch (context.assignmentKind()) {
            case "NEW_FROM_TRUNK" -> "Plan seed:\n" + context.planSeed()
                    + "\n\nRequested work:\n" + context.prompt();
            case "EXISTING_OWN_PR" -> "Plan the remaining work for existing PR #"
                    + context.pullRequestNumber() + " in "
                    + context.assignmentRepositoryId() + ".";
            case "REVIEW_FINDINGS" -> "Plan fixes for review " + context.sourceId()
                    + " findings " + context.selectedFindingsJson() + ".";
            case "ISSUE" -> "Plan the implementation for issue "
                    + context.sourceId() + ".";
            case "AUTOMATION" -> "Plan the automated request from "
                    + context.producer() + ": " + context.reason();
            case "QUALITY_SCAN" -> "Plan remediation for quality evidence "
                    + context.sourceId() + ".";
            default -> throw new IllegalStateException(
                    "Unknown TaskAssignment kind: " + context.assignmentKind());
        };
        return assignment + "\n\nInspect the repository without changing files. "
                + "Then call record_plan exactly once with task_id='"
                + context.taskId() + "' and a finalized structured plan. "
                + "Do not edit code, commit, push, or create remote effects.";
    }

    private ProvisionTaskOperationHandler.ProvisionEvidence decodeProvisionEvidence(
            String value)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provision evidence is missing");
        }
        try {
            return provisionEvidenceReader.readValue(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Provision evidence is invalid", e);
        }
    }

    private WorkModel decodeWorkModel(String value)
    {
        try {
            WorkModel model = workModelReader.readValue(value);
            if (model.kind() == null
                    || model.agentOrProvider() == null
                    || model.agentOrProvider().isBlank()) {
                throw new IllegalArgumentException("Frozen work model is incomplete");
            }
            return model;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Frozen work model is not strict WorkModel JSON", e);
        }
    }

    private static void requireProvisionEvidence(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expected,
            ProvisionTaskOperationHandler.ProvisionEvidence evidence)
    {
        Path worktree = Path.of(required(evidence.worktreePath(), "worktreePath"));
        if (!"PROVISION_TASK_V1".equals(evidence.schema())
                || !owner.id().equals(evidence.taskId())
                || !expected.operationId().equals(evidence.operationId())
                || !Objects.equals(expected.taskEpoch(), evidence.taskEpoch())
                || evidence.baseSource() == null
                || required(evidence.repositoryId(), "repositoryId").isBlank()
                || required(evidence.branchName(), "branchName").isBlank()
                || required(evidence.baseSha(), "baseSha").isBlank()
                || required(evidence.headSha(), "headSha").isBlank()
                || required(evidence.codeFingerprint(), "codeFingerprint").isBlank()
                || !worktree.isAbsolute()
                || !worktree.normalize().equals(worktree)) {
            throw new IllegalArgumentException(
                    "Provision evidence does not match the exact Task operation");
        }
    }

    private String provisionReceiptJson(ProvisionReceipt receipt)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "PROVISION_TO_PLAN_V1");
        node.put("taskId", receipt.taskId());
        node.put("provisionOperationId", receipt.provisionOperationId());
        node.put("planStageId", receipt.planStageId());
        node.put("planStageGeneration", receipt.planStageGeneration());
        node.put("draftTurnId", receipt.draftTurnId());
        node.put("draftOperationId", receipt.draftOperationId());
        return write(node);
    }

    private DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String value)
    {
        String evidence = value != null && value.startsWith("{")
                ? value
                : write(json.createObjectNode()
                        .put("schema", "PLAN_DELIVERY_V1")
                        .put("result", value));
        return new DispatchTicket.DeliveryReceipt(acceptance, evidence);
    }

    private String write(JsonNode node)
    {
        try {
            return json.writeValueAsString(node);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Plan protocol JSON", e);
        }
    }

    private static void putNullable(ObjectNode node, String field, String value)
    {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        }
        else {
            node.put(field, value);
        }
    }

    private static String systemPrompt(String roleSkill, String purpose)
    {
        String base = "You are the read-only Task Brain for the V2 Plan protocol. "
                + "You may inspect the checked-out Task worktree, but you must not "
                + "modify files or create remote effects. Complete the requested "
                + purpose + " protocol using the provided MCP tools.";
        return roleSkill == null || roleSkill.isBlank()
                ? base
                : base + "\n\nRole skill:\n" + roleSkill;
    }

    public static String id(String namespace, String value)
    {
        return UUID.nameUUIDFromBytes(
                ("bytequay-v2:" + namespace + ":" + value)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(required(value, "digest value")
                                    .getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String required(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
