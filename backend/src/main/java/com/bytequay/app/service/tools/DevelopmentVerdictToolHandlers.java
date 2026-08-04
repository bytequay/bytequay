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

import com.bytequay.app.developmentflow.AgentBrainResult;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * {@code record_development_verdict} — how a Development Brain review reports
 * its conclusion.
 *
 * <p>It used to write JSON into its final assistant message. A review that had
 * read the whole change and formed a correct opinion was then thrown away for
 * expressing that opinion as prose, which is what a reviewer naturally writes.
 * The verdict is a decision, not a formatting exercise; the schema now belongs
 * to the tool and the final message is free to be whatever the reviewer wants.
 */
@Component
public class DevelopmentVerdictToolHandlers
{
    private final LocalDevelopmentRuntimeCoordinator local;
    private final ActiveAgentContextRegistry activeContexts;

    public DevelopmentVerdictToolHandlers(
            LocalDevelopmentRuntimeCoordinator local,
            ActiveAgentContextRegistry activeContexts)
    {
        this.local = requireNonNull(local, "local is null");
        this.activeContexts = requireNonNull(activeContexts, "activeContexts is null");
    }

    /** Args for {@code record_development_verdict}. */
    public record RecordDevelopmentVerdictArgs(
            @ToolParam(description = "APPROVED or CHANGES_REQUESTED. APPROVED "
                    + "requires an empty findings list; CHANGES_REQUESTED requires "
                    + "at least one finding.", required = true)
            String verdict,
            @ToolParam(description = "What you reviewed and what you concluded, "
                    + "in a sentence or two.", required = true)
            String summary,
            @ToolParam(description = "One entry per change you are asking for. "
                    + "Empty when you approve.", required = true)
            List<String> findings) {}

    @AgentTool(
            name = "record_development_verdict",
            description = "Report your review conclusion. Call this once, as your "
                    + "last act — the review is accepted on this call, and a review "
                    + "that ends without it is discarded. If it comes back rejected, "
                    + "correct the arguments and call it again. Your final message "
                    + "is not read; put the verdict in the tool call, not in prose.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordDevelopmentVerdict(
            RecordDevelopmentVerdictArgs args, ToolCall call)
    {
        if (args == null) {
            return ToolOutcome.Completed.error(
                    "record_development_verdict needs arguments");
        }
        Optional<ActiveAgentContextRegistry.TypedOwner> owner =
                activeContexts.findTypedOwner(call.threadId(), call.runtimeAgentKey());
        if (owner.isEmpty()
                || owner.get().kind() != DispatchTicket.OwnerKind.TASK_TURN) {
            return ToolOutcome.Completed.error(
                    "record_development_verdict only runs on a Brain review Turn");
        }
        if (args.summary() == null || args.summary().isBlank()) {
            return ToolOutcome.Completed.error("summary is required");
        }
        try {
            local.recordDevelopmentVerdict(
                    owner.get().turnId(), owner.get().operationId(),
                    new AgentBrainResult(
                            1, args.verdict(), args.summary(),
                            args.findings() == null ? List.of() : args.findings()));
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ToolOutcome.Completed.error(
                    e.getMessage() == null ? "the verdict was rejected" : e.getMessage());
        }
        return ToolOutcome.Completed.ok("recorded " + args.verdict());
    }
}
