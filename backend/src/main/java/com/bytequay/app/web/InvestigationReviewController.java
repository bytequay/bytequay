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

import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.service.review.InvestigationReviewMcpService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.review.InvestigationReviewService.FindingMutation;
import com.bytequay.app.service.review.InvestigationReviewService.PlanDraft;
import com.bytequay.app.service.review.InvestigationReviewService.ReviewerDefInput;
import com.bytequay.app.service.review.InvestigationReviewService.StartOptions;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Locked P0-P2 review-session REST contract. */
@RestController
public class InvestigationReviewController
{
    private final InvestigationReviewService reviews;
    private final InvestigationReviewMcpService mcp;

    public InvestigationReviewController(
            InvestigationReviewService reviews, InvestigationReviewMcpService mcp)
    {
        this.reviews = reviews;
        this.mcp = mcp;
    }

    @PostMapping("/api/review-plan/preflight")
    public PlanDraft preflight(@RequestBody PreflightRequest body)
    {
        if (body == null || body.prId() == null || body.prId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prId is required");
        }
        return reviews.preflight(body.prId());
    }

    @PostMapping("/api/prs/{prId}/review-session")
    public InvestigationReviewData start(
            @PathVariable String prId, @RequestBody(required = false) StartOptions body)
    {
        return reviews.start(prId, body);
    }

    @GetMapping("/api/prs/{prId}/review-session")
    public InvestigationReviewData get(@PathVariable String prId)
    {
        return reviews.findByPr(prId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "PR has no review session"));
    }

    @PostMapping("/api/review-sessions/{sessionId}/rounds")
    public InvestigationReviewData createRound(
            @PathVariable String sessionId, @RequestBody(required = false) RoundRequest body)
    {
        String kind = body == null ? "continue" : body.kind();
        List<String> findingIds = body == null || body.findingIds() == null
                ? List.of() : body.findingIds();
        return reviews.createRound(sessionId, kind, findingIds,
                body == null ? null : new StartOptions(body.runner(), body.providerId()));
    }

    @PostMapping("/api/findings/{findingId}/answer")
    public InvestigationReviewData answer(
            @PathVariable String findingId, @RequestBody AnswerRequest body)
    {
        return reviews.answer(findingId, body == null ? null : body.text());
    }

    @PostMapping("/api/findings/{findingId}")
    public InvestigationReviewData mutate(
            @PathVariable String findingId, @RequestBody FindingMutation body)
    {
        return reviews.mutateFinding(findingId, body);
    }

    @GetMapping("/api/review-rounds/{roundId}/log")
    public InvestigationReviewData roundLog(@PathVariable String roundId)
    {
        return reviews.roundLog(roundId);
    }

    @PostMapping("/api/review-rounds/{roundId}/cancel")
    public InvestigationReviewData cancelRound(@PathVariable String roundId)
    {
        return reviews.cancelRound(roundId);
    }

    @GetMapping("/api/reviewer-defs")
    public List<ReviewerDefRow> reviewerDefs()
    {
        return reviews.reviewerDefs();
    }

    @PostMapping("/api/reviewer-defs")
    public ReviewerDefRow createReviewerDef(@RequestBody ReviewerDefInput body)
    {
        return reviews.saveReviewerDef(null, body);
    }

    @PutMapping("/api/reviewer-defs/{reviewerDefId}")
    public ReviewerDefRow updateReviewerDef(
            @PathVariable String reviewerDefId, @RequestBody ReviewerDefInput body)
    {
        return reviews.saveReviewerDef(reviewerDefId, body);
    }

    @DeleteMapping("/api/reviewer-defs/{reviewerDefId}")
    public void disableReviewerDef(@PathVariable String reviewerDefId)
    {
        if (!reviews.disableReviewerDef(reviewerDefId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown reviewer definition");
        }
    }

    @PostMapping("/api/review-sessions/{sessionId}/assignments/{assignmentId}/mcp")
    public JsonNode mcp(
            @PathVariable String sessionId, @PathVariable String assignmentId,
            @RequestBody JsonNode request)
    {
        return mcp.handle(sessionId, assignmentId, request);
    }

    public record PreflightRequest(String prId) {}

    public record RoundRequest(
            String kind, List<String> findingIds, String runner, String providerId) {}

    public record AnswerRequest(String text) {}
}
