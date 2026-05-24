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
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.service.review.ReviewPassService;
import com.bytequay.app.service.review.ScheduledReviewService;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

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
    private final ScheduledReviewService scheduledReviews;
    private final AppSettingsStore appSettings;

    public ReviewController(
            ReviewPassService reviews,
            ScheduledReviewService scheduledReviews,
            AppSettingsStore appSettings)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.scheduledReviews = requireNonNull(scheduledReviews, "scheduledReviews is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    /** Read the workspace-level reviewer persona — a user-editable
     *  nudge prepended to every panel reviewer's skill-context at
     *  request time. Empty when unset. */
    @GetMapping("/persona")
    public Map<String, String> getPersona()
    {
        return Map.of("persona", appSettings.get(Key.REVIEW_PERSONA).orElse(""));
    }

    /** Save the workspace-level reviewer persona. Blank or null
     *  body clears the nudge. */
    @PutMapping("/persona")
    public Map<String, String> setPersona(@RequestBody PersonaRequest body)
    {
        String value = body == null || body.persona() == null ? "" : body.persona().strip();
        appSettings.set(Key.REVIEW_PERSONA, value);
        return Map.of("persona", value);
    }

    public record PersonaRequest(String persona) {}

    @PostMapping("/start")
    public ReviewPassDetail start(@RequestBody StartReviewRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        ReviewPassService.StartOptions opts = body.toOptions();
        return reviews.startReviewOnPr(body.repoFullName(), body.prNumber(), opts);
    }

    /** Roster of LLM reviewers the dialog renders as panel chips.
     *  Configured ones come first; unconfigured ones surface so the
     *  user sees the option but the chip stays disabled until they
     *  add a key in Settings → AI review. */
    @GetMapping("/roster")
    public List<ReviewPassService.RosterEntry> roster()
    {
        return reviews.roster();
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

    /** Resolve one disputed finding via the arbitration ballot.
     *  {@code resolution} is {@code "include"} (status →
     *  ARBITRATED) or {@code "drop"} (status → DROPPED). Once every
     *  DISPUTED finding on the pass is resolved the pass transitions
     *  to TERMINATE and the publish form unlocks. */
    @PostMapping("/{passId}/findings/{findingId}/arbitrate")
    public ReviewPassDetail arbitrate(
            @PathVariable String passId,
            @PathVariable String findingId,
            @RequestBody ArbitrateFindingRequest body)
    {
        if (body == null || body.resolution() == null || body.resolution().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "resolution is required ('include' or 'drop')");
        }
        return reviews.arbitrateFinding(passId, findingId, body.resolution());
    }

    /** Read the scheduled-reviews opt-in toggle. The settings UI
     *  polls this to render the on/off state. */
    @GetMapping("/scheduled-settings")
    public Map<String, Boolean> getScheduledSettings()
    {
        return Map.of("enabled", scheduledReviews.isEnabled());
    }

    /** Flip the toggle. Stored in AppSettings; the @Scheduled loop
     *  reads it each tick so a flip takes effect on the next tick
     *  without restart. */
    @PutMapping("/scheduled-settings")
    public Map<String, Boolean> setScheduledSettings(@RequestBody ScheduledSettingsRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "request body is required");
        }
        scheduledReviews.setEnabled(body.enabled());
        return Map.of("enabled", scheduledReviews.isEnabled());
    }

    /**
     * Start-review body. {@code panelProviderIds}, {@code roundCap},
     * {@code costCapMilli}, and {@code independentFirst} are optional
     * — null/zero means "use the registry defaults" so the older
     * one-click callers don't need to change. Today only the
     * assign-review-task dialog populates them.
     */
    public record StartReviewRequest(
            String repoFullName,
            int prNumber,
            List<String> panelProviderIds,
            Integer roundCap,
            Long costCapMilli,
            Boolean independentFirst)
    {
        public ReviewPassService.StartOptions toOptions()
        {
            ReviewPassService.StartOptions defaults = ReviewPassService.StartOptions.DEFAULT;
            return new ReviewPassService.StartOptions(
                    panelProviderIds == null ? List.of() : panelProviderIds,
                    roundCap == null || roundCap <= 0 ? defaults.roundCap() : roundCap,
                    costCapMilli == null || costCapMilli <= 0 ? defaults.costCapMilli() : costCapMilli,
                    independentFirst == null ? defaults.independentFirst() : independentFirst);
        }
    }

    public record PublishReviewRequest(String verdict, List<String> findingIds) {}

    public record ArbitrateFindingRequest(String resolution) {}

    public record ScheduledSettingsRequest(boolean enabled) {}
}
