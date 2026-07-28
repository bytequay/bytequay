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
package com.bytequay.app.web;

import com.bytequay.app.beans.session.SessionDto;
import com.bytequay.app.developmentflow.compatibility.V2AgentRunProjection;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.runs.SessionControlService;
import com.bytequay.app.service.runs.SessionProjectionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSessionController
{
    @Test
    void typedReviewSeatTurnsDoNotBecomeWorkspaceSessions()
    {
        AgentRunService runs = mock(AgentRunService.class);
        InvestigationReviewStore reviews = mock(InvestigationReviewStore.class);
        SessionControlService controls = mock(SessionControlService.class);
        V2AgentRunProjection v2 = mock(V2AgentRunProjection.class);
        AgentRun seat = new AgentRun(
                "v2-ticket:review-seat", "task-1",
                AgentRun.KIND_PANEL_REVIEW, null, null, "round-1", null,
                AgentRun.STATUS_RUNNING, 1, null, "Investigate", null,
                Instant.EPOCH, null, "workspace-1", "trunk-1", "openai",
                null, 0, 0, 0, 1, "Review", null, null);
        when(v2.listByWorkspace("workspace-1")).thenReturn(List.of(seat));
        when(v2.findById(seat.id())).thenReturn(Optional.of(seat));
        SessionProjectionService projections =
                new SessionProjectionService(runs, reviews, v2);
        SessionController controller = new SessionController(
                projections, controls);

        assertThat(controller.list("workspace-1")).isEmpty();
        assertThatThrownBy(() -> controller.get(seat.id()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no session");
    }

    @Test
    void v2SessionDetailNeverReadsOrMutatesLegacyAgentRuns()
    {
        AgentRunService runs = mock(AgentRunService.class);
        InvestigationReviewStore reviews = mock(InvestigationReviewStore.class);
        SessionControlService controls = mock(SessionControlService.class);
        V2AgentRunProjection v2 = mock(V2AgentRunProjection.class);
        AgentRun typed = new AgentRun(
                "v2-ticket:ticket-1", "task-1", AgentRun.KIND_DEV,
                AgentRun.SOURCE_LOCAL, "stage-1", null, "stage-1",
                AgentRun.STATUS_RUNNING, 1, null, "Implement", null,
                Instant.EPOCH, null, "workspace-1", "trunk-1", "openai",
                null, 0, 0, 0, 1, "Implement", null, null);
        when(v2.findById(typed.id())).thenReturn(Optional.of(typed));
        SessionProjectionService projections =
                new SessionProjectionService(runs, reviews, v2);
        SessionController controller = new SessionController(
                projections, controls);

        SessionDto detail = controller.get(typed.id());
        assertThat(detail.id()).isEqualTo(typed.id());
        assertThat(detail.controls().pause()).isFalse();
        assertThat(detail.controls().resume()).isFalse();
        assertThat(detail.controls().stop()).isFalse();
        assertThat(detail.controls().restart()).isFalse();
        assertThatThrownBy(() -> controller.pause(typed.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owning task or stage");

        verifyNoInteractions(runs, reviews, controls);
    }

    @Test
    void oneStableReviewSessionRepresentsTheLatestRoundAndHidesVerifierRuns()
    {
        AgentRunService runs = mock(AgentRunService.class);
        InvestigationReviewStore reviews = mock(InvestigationReviewStore.class);
        AgentRun first = reviewRun("run-1", "round-1", 1);
        AgentRun latest = reviewRun("run-2", "round-2", 2);
        AgentRun verifier = reviewRun("verifier", "round-2", 3);
        AgentRun dev = devRun();
        AgentRun taskReview = taskReviewRun();
        ReviewRoundRow firstRound = round("round-1", "run-1");
        ReviewRoundRow latestRound = round("round-2", "run-2");
        AgentReviewRow review = new AgentReviewRow(
                "review-1", "acme/widget", "pr-1", "base", "head",
                "ACTIVE", "workspace-1", null, null);

        when(runs.findByWorkspace("workspace-1"))
                .thenReturn(List.of(verifier, latest, first, taskReview, dev));
        when(runs.findById("run-2")).thenReturn(Optional.of(latest));
        when(reviews.findRound("round-1")).thenReturn(Optional.of(firstRound));
        when(reviews.findRound("round-2")).thenReturn(Optional.of(latestRound));
        when(reviews.findReview("review-1")).thenReturn(Optional.of(review));
        when(reviews.rounds("review-1")).thenReturn(List.of(firstRound, latestRound));
        SessionControlService controls = mock(SessionControlService.class);
        V2AgentRunProjection v2 = mock(V2AgentRunProjection.class);
        SessionProjectionService projections =
                new SessionProjectionService(runs, reviews, v2);
        SessionController controller = new SessionController(projections, controls);

        List<SessionDto> sessions = controller.list("workspace-1");

        assertThat(sessions).hasSize(3);
        SessionDto reviewSession = sessions.stream()
                .filter(session -> "review-1".equals(session.id()))
                .findFirst()
                .orElseThrow();
        assertThat(reviewSession.id()).isEqualTo("review-1");
        assertThat(reviewSession.reviewRoundId()).isEqualTo("round-2");
        assertThat(reviewSession.durableReview()).isTrue();
        assertThat(sessions.stream()
                .filter(session -> "task-review".equals(session.id()))
                .findFirst().orElseThrow().durableReview()).isFalse();
        assertThat(controller.get("review-1").id()).isEqualTo("review-1");
        assertThat(projections.countLive("workspace-1")).isEqualTo(2);

        when(runs.findById("task-review")).thenReturn(Optional.of(taskReview));
        when(controls.pause("task-review")).thenReturn(taskReview.paused("paused by user"));
        assertThat(controller.pause("task-review").trunkId()).isEqualTo("thread-1");
        verify(controls).pause("task-review");

        assertThatThrownBy(() -> controller.pause("review-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pull request review panel");
        when(runs.findById("verifier")).thenReturn(Optional.of(verifier));
        assertThatThrownBy(() -> controller.get("verifier"))
                .hasMessageContaining("no session");
    }

    private static AgentRun reviewRun(String id, String roundId, long second)
    {
        return new AgentRun(
                id, null, AgentRun.KIND_PANEL_REVIEW, null, null, roundId,
                null, AgentRun.STATUS_RUNNING, 0, 50, null, null,
                Instant.EPOCH.plusSeconds(second), null, "workspace-1", null,
                "agent-review", "review-model", 0, 0, 0, 0,
                "Review acme/widget#1", null, null);
    }

    private static AgentRun devRun()
    {
        return new AgentRun(
                "dev-1", "task-1", AgentRun.KIND_DEV, null, null, null,
                "stage-1", AgentRun.STATUS_SUCCEEDED, 1, 10, "Done", null,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "workspace-1",
                "thread-1", "codex", "dev-model", 0, 0, 0, 1,
                "Implement", null, "completed");
    }

    private static AgentRun taskReviewRun()
    {
        return new AgentRun(
                "task-review", "task-1", AgentRun.KIND_PANEL_REVIEW, null,
                "stage-1", null, "stage-1", AgentRun.STATUS_RUNNING, 1, 10,
                "Reviewing", null, Instant.EPOCH.plusSeconds(4), null,
                "workspace-1", "thread-1", "codex", "review-model",
                0, 0, 0, 1, "Review task", null, null);
    }

    private static ReviewRoundRow round(String id, String runId)
    {
        return new ReviewRoundRow(
                id, "review-1", runId, "initial", "full", "head", null,
                "RUNNING", new RoundBudget(50, 10), 0);
    }
}
