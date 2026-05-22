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

import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewVerdict;
import com.bytequay.app.service.review.ReviewPassService;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the review flow-type. Three endpoints back the
 * Phase 1 panel UI: {@code POST /start} kicks a new pass off,
 * {@code GET /{passId}} returns the aggregated detail for a known
 * pass, {@code GET /by-thread/{threadId}} resolves the latest pass
 * on a review thread (the URL shape mirrors the thread-detail page
 * the panel UI lives in).
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController
{
    private final ReviewPassService reviews;

    public ReviewController(ReviewPassService reviews)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    @PostMapping("/start")
    public ReviewPassDetail start(@RequestBody StartReviewRequest body)
    {
        return reviews.startReviewOnPr(body.repoFullName(), body.prNumber());
    }

    @GetMapping("/{passId}")
    public ResponseEntity<ReviewPassDetail> get(@PathVariable String passId)
    {
        return reviews.findPassWithDetail(passId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-thread/{threadId}")
    public ResponseEntity<ReviewPassDetail> latestForThread(@PathVariable String threadId)
    {
        return reviews.findLatestPassForThread(threadId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Publish the pass to the PR. The frontend hands over the user's
     * confirmed verdict + the subset of finding ids that should
     * actually land on GitHub; the service posts them as one GitHub
     * review and marks the rows POSTED.
     */
    @PostMapping("/{passId}/publish")
    public ReviewPassDetail publish(
            @PathVariable String passId,
            @RequestBody PublishReviewRequest body)
    {
        if (body == null || body.verdict() == null || body.verdict().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "verdict is required");
        }
        ReviewVerdict verdict = ReviewVerdict.fromDbValue(body.verdict());
        if (verdict == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "unknown verdict: " + body.verdict());
        }
        return reviews.publishPass(
                passId,
                verdict,
                body.findingIds() == null ? List.of() : body.findingIds());
    }

    public record StartReviewRequest(String repoFullName, int prNumber) {}

    public record PublishReviewRequest(String verdict, List<String> findingIds) {}
}
