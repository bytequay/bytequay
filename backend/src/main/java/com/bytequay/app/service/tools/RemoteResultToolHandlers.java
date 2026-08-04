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
package com.bytequay.app.service.tools;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator.ReplyResult;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * How a Remote repair StageTurn reports its result.
 *
 * <p>Both of these used to be strict JSON formatted into the Turn's final
 * assistant message, and a Turn that wrote prose instead had its committed
 * repair thrown away. The payload is a tool call now: the arguments are
 * serialised by the harness against a published schema, and a rejection comes
 * back while the agent is still running and can correct it.
 *
 * <p>Two tools rather than one with an optional list. They write the same table
 * — a feedback repair is a summary plus reply drafts — but a CI repair's
 * delivery reads only the summary, so a single tool would let it submit replies
 * that are accepted and silently discarded. That is the failure mode this whole
 * contract change exists to remove, not one to reintroduce for a shorter file.
 */
@Component
public class RemoteResultToolHandlers
{
    private final RemoteRepairTurnRuntime repairs;
    private final RemoteFeedbackRuntimeCoordinator feedback;
    private final ActiveAgentContextRegistry activeContexts;

    public RemoteResultToolHandlers(
            RemoteRepairTurnRuntime repairs,
            RemoteFeedbackRuntimeCoordinator feedback,
            ActiveAgentContextRegistry activeContexts)
    {
        this.repairs = requireNonNull(repairs, "repairs is null");
        this.feedback = requireNonNull(feedback, "feedback is null");
        this.activeContexts = requireNonNull(activeContexts, "activeContexts is null");
    }

    /** Args for {@code record_repair_summary}. */
    public record RecordRepairSummaryArgs(
            @ToolParam(description = "What you repaired and how, in a sentence or "
                    + "two. Say so plainly if you concluded no change was needed.",
                    required = true)
            String summary) {}

    @AgentTool(
            name = "record_repair_summary",
            description = "Report the result of this Remote repair Turn — the CI "
                    + "fix or the conflict resolution you just committed. Call it "
                    + "once, as your last act: the Turn is accepted on this call, "
                    + "and one that ends without it is discarded. If it comes back "
                    + "rejected, correct the argument and call it again. Your final "
                    + "message is not read.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordRepairSummary(
            RecordRepairSummaryArgs args, ToolCall call)
    {
        if (args == null || args.summary() == null || args.summary().isBlank()) {
            return ToolOutcome.Completed.error("summary is required");
        }
        Optional<ActiveAgentContextRegistry.TypedOwner> owner = stageOwner(call);
        if (owner.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "record_repair_summary only runs on a Remote repair Turn");
        }
        try {
            repairs.recordRepairSummary(
                    owner.get().turnId(), owner.get().operationId(), args.summary());
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ToolOutcome.Completed.error(
                    e.getMessage() == null ? "the result was rejected" : e.getMessage());
        }
        return ToolOutcome.Completed.ok("recorded the repair result for this Turn");
    }

    /**
     * One reply draft. Published to the model as an untyped object — the schema
     * generator has no nested-record support — so the field list lives in the
     * enclosing argument's description, where the model will actually read it.
     */
    public record ReplyDraftArgs(
            Integer ordinal,
            Integer batchItemOrdinal,
            String kind,
            String body,
            String externalTarget) {}

    /** Args for {@code record_feedback_repair}. */
    public record RecordFeedbackRepairArgs(
            @ToolParam(description = "What you changed to address this feedback "
                    + "batch, in a sentence or two.", required = true)
            String summary,
            @ToolParam(description = "One draft per batch item you are answering. "
                    + "Each entry is an object with: batchItemOrdinal (integer, the "
                    + "#N of the item), kind (POST_INLINE_REPLY, POST_TOP_LEVEL_REPLY "
                    + "or RESOLVE_THREAD), body (the reply text; must be null for "
                    + "RESOLVE_THREAD and non-empty otherwise), externalTarget (the "
                    + "item's target string) and an optional ordinal (integer, "
                    + "defaults to batchItemOrdinal). Pass an empty list when you "
                    + "have no replies to draft.", required = true)
            List<ReplyDraftArgs> replies) {}

    @AgentTool(
            name = "record_feedback_repair",
            description = "Report the result of this Remote feedback repair Turn: "
                    + "what you changed, and the reply drafts the user will "
                    + "authorize. Call it once, as your last act — the Turn is "
                    + "accepted on this call, and one that ends without it is "
                    + "discarded. Nothing here is posted to GitHub; the drafts wait "
                    + "for the user. If it comes back rejected, correct the "
                    + "arguments and call it again. Your final message is not read.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordFeedbackRepair(
            RecordFeedbackRepairArgs args, ToolCall call)
    {
        if (args == null || args.summary() == null || args.summary().isBlank()) {
            return ToolOutcome.Completed.error("summary is required");
        }
        Optional<ActiveAgentContextRegistry.TypedOwner> owner = stageOwner(call);
        if (owner.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "record_feedback_repair only runs on a Remote feedback repair Turn");
        }
        List<ReplyResult> replies = args.replies() == null ? List.of()
                : args.replies().stream()
                        .map(RemoteResultToolHandlers::reply)
                        .toList();
        try {
            feedback.recordFeedbackRepair(
                    owner.get().turnId(), owner.get().operationId(),
                    args.summary(), replies);
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ToolOutcome.Completed.error(
                    e.getMessage() == null ? "the result was rejected" : e.getMessage());
        }
        return ToolOutcome.Completed.ok(
                "recorded the feedback repair with " + replies.size() + " reply draft(s)");
    }

    private static ReplyResult reply(ReplyDraftArgs draft)
    {
        if (draft.batchItemOrdinal() == null) {
            throw new IllegalArgumentException(
                    "every reply needs a batchItemOrdinal");
        }
        return new ReplyResult(
                draft.ordinal(), draft.batchItemOrdinal(), draft.kind(),
                draft.body(), draft.externalTarget());
    }

    private Optional<ActiveAgentContextRegistry.TypedOwner> stageOwner(ToolCall call)
    {
        return activeContexts.findTypedOwner(call.threadId(), call.runtimeAgentKey())
                .filter(owner -> owner.kind() == DispatchTicket.OwnerKind.STAGE_TURN);
    }
}
