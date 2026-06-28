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

import com.bytequay.app.repository.CiFixingLogQueryMarkerStore;
import com.bytequay.app.service.stage.IterationService;
import com.bytequay.app.service.stage.IterationService.CiFixingSummaryEntry;
import com.bytequay.app.service.tools.IterationToolHandlers.GetNewUpdatedCiFixingLogArgs;
import com.bytequay.app.service.tools.IterationToolHandlers.RecordIterationSummaryArgs;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIterationToolHandlers
{
    private final IterationService iterationService = mock(IterationService.class);
    private final CiFixingLogQueryMarkerStore ciFixingLogMarkers = mock(CiFixingLogQueryMarkerStore.class);
    private final IterationToolHandlers handlers =
            new IterationToolHandlers(iterationService, ciFixingLogMarkers);

    private final ToolCall call = new ToolCall("thread-1", null, AgentRole.TASK);

    @Test
    void recordsSummaryForAValidCall()
    {
        UUID iterationId = UUID.randomUUID();
        ToolOutcome outcome = handlers.recordIterationSummary(
                new RecordIterationSummaryArgs(iterationId.toString(), "bumped retry default 3->5"), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(iterationService).recordSummary(eq(iterationId), eq("bumped retry default 3->5"));
    }

    @Test
    void rejectsEmptyText()
    {
        ToolOutcome outcome = handlers.recordIterationSummary(
                new RecordIterationSummaryArgs(UUID.randomUUID().toString(), "  "), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(iterationService, never()).recordSummary(any(), any());
    }

    @Test
    void rejectsTextOver280Chars()
    {
        String tooLong = "x".repeat(IterationService.SUMMARY_MAX_CHARS + 1);
        ToolOutcome outcome = handlers.recordIterationSummary(
                new RecordIterationSummaryArgs(UUID.randomUUID().toString(), tooLong), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(iterationService, never()).recordSummary(any(), any());
    }

    @Test
    void rejectsAMalformedIterationId()
    {
        ToolOutcome outcome = handlers.recordIterationSummary(
                new RecordIterationSummaryArgs("not-a-uuid", "valid text"), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(iterationService, never()).recordSummary(any(), any());
    }

    private final ToolCall taskCall =
            new ToolCall("thread-1", null, AgentRole.TASK, "task-1", null);

    @Test
    void ciFixingLogReturnsOnlySummariesNewerThanTheMarkerAndAdvancesIt()
    {
        Instant marker = Instant.parse("2026-06-01T00:00:00Z");
        when(ciFixingLogMarkers.find("task-1")).thenReturn(Optional.of(marker));
        Instant older = marker.minusSeconds(10);
        Instant newer = marker.plusSeconds(30);
        when(iterationService.latestCiFixingSummaryEntries("task-1")).thenReturn(List.of(
                new CiFixingSummaryEntry(1, "already seen", older),
                new CiFixingSummaryEntry(2, "fresh fix", newer)));

        ToolOutcome outcome = handlers.getNewUpdatedCiFixingLog(
                new GetNewUpdatedCiFixingLogArgs(), taskCall);

        ToolOutcome.Completed completed = (ToolOutcome.Completed) outcome;
        assertThat(completed.isError()).isFalse();
        assertThat(completed.text()).contains("fresh fix").doesNotContain("already seen");
        verify(ciFixingLogMarkers).mark("task-1", newer);
    }

    @Test
    void ciFixingLogReportsNothingNewAndLeavesTheMarkerUntouched()
    {
        Instant marker = Instant.parse("2026-06-01T00:00:00Z");
        when(ciFixingLogMarkers.find("task-1")).thenReturn(Optional.of(marker));
        when(iterationService.latestCiFixingSummaryEntries("task-1")).thenReturn(List.of(
                new CiFixingSummaryEntry(1, "already seen", marker.minusSeconds(10))));

        ToolOutcome outcome = handlers.getNewUpdatedCiFixingLog(
                new GetNewUpdatedCiFixingLogArgs(), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).text()).contains("No new CI-fixing");
        verify(ciFixingLogMarkers, never()).mark(any(), any());
    }

    @Test
    void ciFixingLogRefusesWhenNoTaskIsInScope()
    {
        ToolOutcome outcome = handlers.getNewUpdatedCiFixingLog(
                new GetNewUpdatedCiFixingLogArgs(), call);

        assertThat(((ToolOutcome.Completed) outcome).text()).contains("No task is in scope");
        verify(iterationService, never()).latestCiFixingSummaryEntries(any());
    }
}
