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
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.DevelopmentReport;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * {@code record_development_result} — how a Local Development Turn reports what
 * it did.
 *
 * <p>This used to be the Turn's final assistant message, parsed as strict JSON.
 * That made the report a natural-language artifact the runtime then refused: a
 * Turn that had already edited and committed real code was discarded for opening
 * its last sentence with "The". Moving the report onto a tool call means the
 * arguments are serialised by the harness against a published schema, and a
 * rejection arrives while the agent is still running and can fix it.
 *
 * <p>Every argument is a plain string on purpose. {@code AgentToolRegistry}
 * publishes {@code {"type":"object"}} for any {@code List} field, so a list
 * argument would hand the model a schema that lies about the shape it wants.
 */
@Component
public class LocalDevelopmentResultToolHandlers
{
    private final LocalDevelopmentRuntimeCoordinator local;
    private final ActiveAgentContextRegistry activeContexts;

    public LocalDevelopmentResultToolHandlers(
            LocalDevelopmentRuntimeCoordinator local,
            ActiveAgentContextRegistry activeContexts)
    {
        this.local = requireNonNull(local, "local is null");
        this.activeContexts = requireNonNull(activeContexts, "activeContexts is null");
    }

    /** Args for {@code record_development_result} — the DevelopmentReport shape. */
    public record RecordDevelopmentResultArgs(
            @ToolParam(description = "What you actually implemented, in your own words. "
                    + "Not the plan restated — what the code now does.",
                    required = true, wireName = "implemented_intent")
            String implementedIntent,
            @ToolParam(description = "The commits you made, one short line each. Leave "
                    + "blank if you deliberately changed nothing.",
                    required = false, wireName = "commit_summary")
            String commitSummary,
            @ToolParam(description = "The files you touched and what changed in each.",
                    required = true, wireName = "file_summary")
            String fileSummary,
            @ToolParam(description = "The validation you ran and its outcome. Say so "
                    + "plainly if you could not run it.",
                    required = true, wireName = "validation_summary")
            String validationSummary,
            @ToolParam(description = "Risks a reviewer should know about, or 'none'.",
                    required = true, wireName = "known_risks")
            String knownRisks,
            @ToolParam(description = "What you could not resolve and left open, or 'none'.",
                    required = true, wireName = "unresolved_concerns")
            String unresolvedConcerns,
            @ToolParam(description = "Files, symbols, or docs the next reader needs.",
                    required = true, wireName = "context_refs")
            String contextRefs,
            @ToolParam(description = "The pull-request body, following the repository's "
                    + "own template when it has one. Only the Turn that opens the pull "
                    + "request uses this; later Turns may leave it blank.",
                    required = false, wireName = "pr_description")
            String prDescription) {}

    @AgentTool(
            name = "record_development_result",
            description = "Report the result of this Local Development Turn. Call it "
                    + "once, as your last act before ending the turn — the Turn is "
                    + "accepted on this call, and one that ends without it is discarded. "
                    + "If it comes back rejected, correct the arguments and call it "
                    + "again; the turn is still yours. Your final message is not read.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordDevelopmentResult(
            RecordDevelopmentResultArgs args, ToolCall call)
    {
        if (args == null) {
            return ToolOutcome.Completed.error("record_development_result needs arguments");
        }
        Optional<ActiveAgentContextRegistry.TypedOwner> owner =
                activeContexts.findTypedOwner(call.threadId(), call.runtimeAgentKey());
        if (owner.isEmpty()
                || owner.get().kind() != DispatchTicket.OwnerKind.STAGE_TURN) {
            return ToolOutcome.Completed.error(
                    "record_development_result only runs on a Local Development Turn");
        }
        for (String[] field : new String[][] {
                {args.implementedIntent(), "implemented_intent"},
                {args.fileSummary(), "file_summary"},
                {args.validationSummary(), "validation_summary"},
                {args.knownRisks(), "known_risks"},
                {args.unresolvedConcerns(), "unresolved_concerns"},
                {args.contextRefs(), "context_refs"}}) {
            if (field[0] == null || field[0].isBlank()) {
                return ToolOutcome.Completed.error(field[1] + " is required and must not be blank");
            }
        }
        DevelopmentReport report = new DevelopmentReport(
                args.implementedIntent(),
                blankIfNull(args.commitSummary()),
                args.fileSummary(),
                args.validationSummary(),
                args.knownRisks(),
                args.unresolvedConcerns(),
                args.contextRefs(),
                blankIfNull(args.prDescription()));
        try {
            local.recordDevelopmentResult(
                    owner.get().turnId(), owner.get().operationId(), report);
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ToolOutcome.Completed.error(
                    e.getMessage() == null ? "the result was rejected" : e.getMessage());
        }
        return ToolOutcome.Completed.ok("recorded the development result for this Turn");
    }

    private static String blankIfNull(String value)
    {
        return value == null ? "" : value;
    }
}
