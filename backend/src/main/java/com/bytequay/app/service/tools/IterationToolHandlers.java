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

import com.bytequay.app.service.stage.IterationService;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The {@code record_iteration_summary} agent tool. A monitor-stage agent
 * calls it at the end of a loop iteration to record a one-line, user-facing
 * summary that lands on the brain feed. The tool is stateless: the
 * iteration it summarises is named by the explicit {@code iteration_id}
 * argument (the orchestrator embeds it in the summary-request prompt), so
 * it doesn't depend on any ambient turn context.
 */
@Component
public class IterationToolHandlers
{
    private final IterationService iterationService;

    public IterationToolHandlers(IterationService iterationService)
    {
        this.iterationService = requireNonNull(iterationService, "iterationService is null");
    }

    /** Args record for {@code record_iteration_summary}. */
    public record RecordIterationSummaryArgs(
            @ToolParam(description = "The iteration id to summarise, as given to you in the "
                    + "summary request.", required = true, wireName = "iteration_id")
            String iterationId,
            @ToolParam(description = "A one-line, user-facing summary of what this iteration did "
                    + "(1-280 chars).", required = true)
            String text) {}

    @AgentTool(
            name = "record_iteration_summary",
            description = "Record a one-line summary (1-280 chars) of what the current "
                    + "monitor-stage loop iteration did. Mandatory at the end of every "
                    + "CI-fixing or review-monitor iteration: the summary lands on the user's "
                    + "brain feed and feeds your own session memory next iteration. Pass the "
                    + "iteration_id you were given in the summary request.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordIterationSummary(RecordIterationSummaryArgs args, ToolCall call)
    {
        String text = args == null ? null : args.text();
        if (text == null || text.isBlank() || text.length() > IterationService.SUMMARY_MAX_CHARS) {
            return ToolOutcome.Completed.error(
                    "Summary must be 1-" + IterationService.SUMMARY_MAX_CHARS + " chars; was "
                            + (text == null ? 0 : text.length()));
        }
        UUID iterationId;
        try {
            iterationId = UUID.fromString(args.iterationId());
        }
        catch (IllegalArgumentException | NullPointerException e) {
            return ToolOutcome.Completed.error("Invalid iteration_id: " + (args == null ? null : args.iterationId()));
        }
        try {
            iterationService.recordSummary(iterationId, text);
        }
        catch (IllegalArgumentException e) {
            return ToolOutcome.Completed.error("No such iteration: " + iterationId);
        }
        return ToolOutcome.Completed.ok("Iteration summary recorded.");
    }
}
