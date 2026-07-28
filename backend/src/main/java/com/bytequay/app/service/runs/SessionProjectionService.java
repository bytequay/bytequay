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
package com.bytequay.app.service.runs;

import com.bytequay.app.beans.session.SessionDto;
import com.bytequay.app.developmentflow.compatibility.V2AgentRunProjection;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Builds the durable workspace Session projection from raw AgentRuns. */
@Service
public class SessionProjectionService
{
    private final AgentRunService runs;
    private final InvestigationReviewStore reviews;
    private V2AgentRunProjection v2Runs;

    public SessionProjectionService(
            AgentRunService runs,
            InvestigationReviewStore reviews)
    {
        this.runs = requireNonNull(runs, "runs is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    @Autowired(required = false)
    void setV2Runs(V2AgentRunProjection v2Runs)
    {
        this.v2Runs = requireNonNull(v2Runs, "v2Runs is null");
    }

    public List<SessionProjection> list(String workspaceId)
    {
        List<SessionProjection> sessions = new ArrayList<>();
        Map<String, ReviewProjection> latestReviewByPr = new LinkedHashMap<>();
        for (AgentRun run : runs.findByWorkspace(workspaceId)) {
            if (!SessionDto.isPublic(run)) {
                continue;
            }
            ReviewLink link = reviewLink(run, workspaceId);
            if (link.internalRun()) {
                continue;
            }
            if (link.review() == null) {
                sessions.add(new SessionProjection(run.id(), run, false));
                continue;
            }
            ReviewProjection candidate = new ReviewProjection(link.review(), run);
            latestReviewByPr.merge(link.review().prId(), candidate,
                    (current, next) -> current.run().startedAt()
                            .isAfter(next.run().startedAt()) ? current : next);
        }
        latestReviewByPr.values().forEach(review -> sessions.add(
                new SessionProjection(review.review().id(), review.run(), true)));
        if (v2Runs != null) {
            v2Runs.listByWorkspace(workspaceId).stream()
                    .filter(SessionDto::isPublic)
                    // PR review seats remain children of one stable
                    // ReviewSession; do not leak each typed seat Turn as a
                    // second workspace Session.
                    .filter(run -> !AgentRun.KIND_PANEL_REVIEW.equals(run.kind()))
                    .map(run -> new SessionProjection(
                            run.id(), run, false, true))
                    .forEach(sessions::add);
        }
        return List.copyOf(sessions);
    }

    public int countLive(String workspaceId)
    {
        return (int) list(workspaceId).stream()
                .filter(session -> session.run().isLive())
                .count();
    }

    public SessionProjection require(String id)
    {
        if (V2AgentRunProjection.isV2Id(id)) {
            if (v2Runs == null) {
                throw new NoSuchElementException("no session: " + id);
            }
            AgentRun run = v2Runs.findById(id)
                    .filter(SessionDto::isPublic)
                    .filter(candidate -> !AgentRun.KIND_PANEL_REVIEW.equals(
                            candidate.kind()))
                    .orElseThrow(() -> new NoSuchElementException(
                            "no session: " + id));
            return new SessionProjection(run.id(), run, false, true);
        }
        Optional<SessionProjection> review = reviewSession(id);
        if (review.isPresent()) {
            return review.get();
        }
        AgentRun run = runs.findById(id)
                .filter(SessionDto::isPublic)
                .orElseThrow(() -> new NoSuchElementException("no session: " + id));
        ReviewLink link = reviewLink(run, run.workspaceId());
        if (link.internalRun()) {
            throw new NoSuchElementException("no session: " + id);
        }
        if (link.review() != null) {
            return new SessionProjection(link.review().id(), run, true);
        }
        return new SessionProjection(run.id(), run, false);
    }

    private Optional<SessionProjection> reviewSession(String reviewId)
    {
        AgentReviewRow review = reviews.findReview(reviewId).orElse(null);
        if (review == null || review.workspaceId() == null) {
            return Optional.empty();
        }
        List<ReviewRoundRow> rounds = reviews.rounds(reviewId);
        for (int index = rounds.size() - 1; index >= 0; index--) {
            ReviewRoundRow round = rounds.get(index);
            Optional<AgentRun> run = runs.findById(round.agentRunId())
                    .filter(SessionDto::isPublic)
                    .filter(candidate -> AgentRun.KIND_PANEL_REVIEW.equals(candidate.kind()))
                    .filter(candidate -> review.workspaceId().equals(candidate.workspaceId()));
            if (run.isPresent()) {
                return Optional.of(new SessionProjection(review.id(), run.get(), true));
            }
        }
        return Optional.empty();
    }

    /** A panel run linked to an AgentReview round is either that round's
     * canonical Session run or an internal verifier/raw child. A panel run
     * with no such link is an ordinary task Session and keeps its controls. */
    private ReviewLink reviewLink(AgentRun run, String workspaceId)
    {
        if (!AgentRun.KIND_PANEL_REVIEW.equals(run.kind())
                || run.reviewRoundId() == null || workspaceId == null) {
            return ReviewLink.ORDINARY;
        }
        ReviewRoundRow round = reviews.findRound(run.reviewRoundId()).orElse(null);
        if (round == null) {
            return ReviewLink.ORDINARY;
        }
        if (!run.id().equals(round.agentRunId())) {
            return ReviewLink.INTERNAL;
        }
        AgentReviewRow review = reviews.findReview(round.reviewId()).orElse(null);
        if (review == null || !workspaceId.equals(review.workspaceId())) {
            return ReviewLink.INTERNAL;
        }
        return new ReviewLink(review, false);
    }

    public record SessionProjection(
            String id, AgentRun run, boolean durableReview, boolean typedV2)
    {
        public SessionProjection(String id, AgentRun run, boolean durableReview)
        {
            this(id, run, durableReview, false);
        }

        public SessionProjection
        {
            requireNonNull(id, "id is null");
            requireNonNull(run, "run is null");
        }
    }

    private record ReviewProjection(AgentReviewRow review, AgentRun run) {}

    private record ReviewLink(AgentReviewRow review, boolean internalRun)
    {
        private static final ReviewLink ORDINARY = new ReviewLink(null, false);
        private static final ReviewLink INTERNAL = new ReviewLink(null, true);
    }
}
