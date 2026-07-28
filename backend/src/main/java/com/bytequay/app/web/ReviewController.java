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
import com.bytequay.app.service.review.ReviewBuildSpawnService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Read/edit/publish surface for historical ReviewPass records. New work is
 * created through the AgentReview API; these endpoints keep old records
 * inspectable and actionable during migration.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController
{
    private final ReviewPassService reviews;
    private final ScheduledReviewService scheduledReviews;
    private final AppSettingsStore appSettings;
    private final ReviewBuildSpawnService buildSpawn;

    public ReviewController(
            ReviewPassService reviews,
            ScheduledReviewService scheduledReviews,
            AppSettingsStore appSettings,
            ReviewBuildSpawnService buildSpawn)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.scheduledReviews = requireNonNull(scheduledReviews, "scheduledReviews is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.buildSpawn = requireNonNull(buildSpawn, "buildSpawn is null");
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

    /**
     * Spawn a build thread from a TERMINATE-d pass to apply its AGREED
     * findings. Gated on at least one AGREED finding at severity ≥ MAJOR
     * (422 {@code no_eligible_findings}); 409 if the pass isn't TERMINATE
     * or already spawned. The {@code workspaceId} is optional — null
     * auto-resolves from the workspace(s) watching the PR's repo (422
     * {@code no_workspace_for_repo} / {@code ambiguous_workspace_picker_required}).
     */
    @PostMapping("/{passId}/spawn-build")
    public ReviewBuildSpawnService.BuildSpawn spawnBuild(
            @PathVariable String passId,
            @RequestBody(required = false) SpawnBuildRequest body)
    {
        String workspaceId = body == null ? null : body.workspaceId();
        String openingTitle = body == null ? null : body.openingTitle();
        List<String> selectedFindingIds = body == null
                ? null : body.selectedFindingIds();
        return buildSpawn.spawn(
                passId, workspaceId, openingTitle, selectedFindingIds);
    }

    /** Spawn-build body — both fields optional. {@code workspaceId} null
     *  auto-resolves; {@code openingTitle} null defaults to "Fix review
     *  findings on PR #N". */
    public record SpawnBuildRequest(
            String workspaceId,
            String openingTitle,
            List<String> selectedFindingIds) {}

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

    /** The active review pass for a PR (by {@code owner/repo} + number),
     *  with its findings. The PR Changes page calls this to overlay the
     *  panel's AGREED findings on the diff at their line positions; 404
     *  when the PR has no review pass. */
    @GetMapping("/for-pr")
    public ResponseEntity<ReviewPassDetail> activeForPr(
            @RequestParam("repo") String repoFullName,
            @RequestParam("number") int prNumber)
    {
        return reviews.findActivePrReviewDetail(repoFullName, prNumber)
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

    @PutMapping("/{passId}/findings/{findingId}")
    public ReviewPassDetail editFinding(
            @PathVariable String passId,
            @PathVariable String findingId,
            @RequestBody EditFindingRequest body)
    {
        if (body == null || body.comment() == null || body.comment().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "comment is required");
        }
        return reviews.editFindingBody(passId, findingId, body.comment());
    }

    public record EditFindingRequest(String comment) {}

    @PostMapping("/{passId}/findings/{findingId}/drop")
    public ReviewPassDetail dropFinding(
            @PathVariable String passId,
            @PathVariable String findingId)
    {
        return reviews.dropFinding(passId, findingId);
    }

    @PostMapping("/{passId}/findings")
    public ReviewPassDetail addFinding(
            @PathVariable String passId,
            @RequestBody AddFindingRequest body)
    {
        if (body == null || body.comment() == null || body.comment().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "comment is required");
        }
        return reviews.addFinding(passId, body.severity(), body.path(), body.line(), body.comment());
    }

    public record AddFindingRequest(String severity, String path, Integer line, String comment) {}

    /** Steer the panel from the review page: inject a human message
     *  addressed to a reviewer or the lead and run that seat's reply
     *  unbudgeted. */
    @PostMapping("/{passId}/steer")
    public ReviewPassDetail steer(
            @PathVariable String passId,
            @RequestBody SteerRequest body)
    {
        if (body == null
                || body.targetParticipantId() == null || body.targetParticipantId().isBlank()
                || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "targetParticipantId and message are required");
        }
        return reviews.steerPass(passId, body.targetParticipantId(), body.message());
    }

    @PostMapping("/{passId}/raise-budget")
    public ReviewPassDetail raiseBudget(
            @PathVariable String passId,
            @RequestBody RaiseBudgetRequest body)
    {
        if (body == null || (body.addCostMilli() <= 0 && body.addRounds() <= 0)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "addCostMilli or addRounds must be positive");
        }
        return reviews.raiseBudget(passId, body.addCostMilli(), body.addRounds());
    }

    @PostMapping("/{passId}/resume")
    public ReviewPassDetail resume(@PathVariable String passId)
    {
        return reviews.resumePass(passId);
    }

    /** Mark a pass completed by hand — finished without posting to
     *  GitHub. Resumable afterwards via {@code /resume}. */
    @PostMapping("/{passId}/complete")
    public ReviewPassDetail complete(@PathVariable String passId)
    {
        return reviews.completePass(passId);
    }

    /** Batch PR title + author for review threads, so a thread list can
     *  label each review thread with the reviewed PR cheaply. */
    @PostMapping("/pr-summaries")
    public List<ReviewPassService.ReviewThreadPrSummary> prSummaries(@RequestBody PrSummariesRequest body)
    {
        return reviews.prSummariesForThreads(
                body == null || body.threadIds() == null ? List.of() : body.threadIds());
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

    public record PublishReviewRequest(String verdict, List<String> findingIds) {}

    public record ArbitrateFindingRequest(String resolution) {}

    public record SteerRequest(String targetParticipantId, String message) {}

    public record RaiseBudgetRequest(long addCostMilli, int addRounds) {}

    public record PrSummariesRequest(List<String> threadIds) {}

    public record ScheduledSettingsRequest(boolean enabled) {}
}
