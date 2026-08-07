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

import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.sqlite.CiFixingLogQueryMarkerStore;
import com.bytequay.app.service.stage.IterationService;
import com.bytequay.app.service.stage.IterationService.CiFixingSummaryEntry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The {@code record_iteration_summary} agent tool. A Task agent on a monitor stage
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
    private final CiFixingLogQueryMarkerStore ciFixingLogMarkers;

    public IterationToolHandlers(
            IterationService iterationService,
            CiFixingLogQueryMarkerStore ciFixingLogMarkers)
    {
        this.iterationService = requireNonNull(iterationService, "iterationService is null");
        this.ciFixingLogMarkers = requireNonNull(ciFixingLogMarkers, "ciFixingLogMarkers is null");
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

    /** Args record for {@code get_new_updated_ci_fixing_log} — no args. */
    public record GetNewUpdatedCiFixingLogArgs() {}

    @AgentTool(
            name = "get_new_updated_ci_fixing_log",
            description = "Read the CI-fixing iteration summaries recorded since you last "
                    + "called this tool. Use it from the comments-addressing stage to stay "
                    + "in sync with what the CI-fixing loop is doing. Returns only summaries "
                    + "newer than your previous call (or all of them on the first call); says "
                    + "so explicitly when nothing is new.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome getNewUpdatedCiFixingLog(GetNewUpdatedCiFixingLogArgs args, ToolCall call)
    {
        if (call.scope() == ThreadScope.TRUNK) {
            return ToolOutcome.Completed.ok(
                    "No task is in scope for this turn — cannot read the CI-fixing log.");
        }
        String taskId = call.requireTaskId();
        Instant since = ciFixingLogMarkers.find(taskId).orElse(Instant.EPOCH);
        List<CiFixingSummaryEntry> entries = iterationService.latestCiFixingSummaryEntries(taskId);
        Instant newest = since;
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (CiFixingSummaryEntry entry : entries) {
            if (!entry.summarizedAt().isAfter(since)) {
                continue;
            }
            out.append("- iteration #").append(entry.iterationNumber())
                    .append(": ").append(entry.text()).append('\n');
            count++;
            if (entry.summarizedAt().isAfter(newest)) {
                newest = entry.summarizedAt();
            }
        }
        if (count == 0) {
            return ToolOutcome.Completed.ok("No new CI-fixing iterations since your last check.");
        }
        // Advance the marker so the next call only returns rows newer than
        // the newest one we just handed back.
        ciFixingLogMarkers.mark(taskId, newest);
        return ToolOutcome.Completed.ok(
                count + " new CI-fixing iteration summar" + (count == 1 ? "y" : "ies") + ":\n" + out);
    }
}
