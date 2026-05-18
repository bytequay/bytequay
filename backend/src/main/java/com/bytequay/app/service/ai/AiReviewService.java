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
package com.bytequay.app.service.ai;

import com.bytequay.app.domain.AiReviewDraft;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.domain.ReviewSkill;
import com.bytequay.app.repository.AiReviewDraftStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.service.pr.PullRequestDetailInvalidator;
import com.bytequay.app.service.skills.ReviewSkillService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Orchestrates a single AI review run: fetches the PR's raw detail and
 * unified diff from GitHub, hands it to the active {@link LlmReviewer}, and
 * persists the resulting draft. First slice is non-streaming.
 */
@Service
public class AiReviewService
{
    private final PullRequestStore pullRequestStore;
    private final PullRequestRepository gitHub;
    private final LlmReviewerRegistry registry;
    private final AiReviewDraftStore draftStore;
    private final ReviewSkillService skillService;
    private final PullRequestDetailInvalidator detailInvalidator;

    public AiReviewService(
            PullRequestStore pullRequestStore,
            PullRequestRepository gitHub,
            LlmReviewerRegistry registry,
            AiReviewDraftStore draftStore,
            ReviewSkillService skillService,
            PullRequestDetailInvalidator detailInvalidator)
    {
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.draftStore = requireNonNull(draftStore, "draftStore is null");
        this.skillService = requireNonNull(skillService, "skillService is null");
        this.detailInvalidator = requireNonNull(detailInvalidator, "detailInvalidator is null");
    }

    /**
     * Resolves which reviewer to use for {@code repo}: prefers the
     * provider locked to the repo's review skill (if any) over the
     * globally-active provider. Returns the active reviewer when no
     * skill matches or the skill is provider-agnostic.
     */
    private LlmReviewer reviewerFor(ReviewSkill skill)
    {
        if (skill != null && skill.llmProvider() != null && !skill.llmProvider().isBlank()) {
            return registry.byId(skill.llmProvider()).orElseGet(registry::active);
        }
        return registry.active();
    }

    /**
     * Runs a review against the active LLM and stores the result. The caller
     * passes the GitHub PR id directly so the run works on PRs that aren't
     * in the local pull_requests table (watched-repo browse, team filter,
     * external links). repo + number are persisted on the draft so publish
     * doesn't need a second lookup either.
     */
    public AiReviewDraft runReview(String pat, long prId, String repo, int number)
    {
        ReviewSkill skill = skillService.forRepo(repo).orElse(null);
        LlmReviewer reviewer = reviewerFor(skill);
        if (!reviewer.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "The active LLM provider (" + reviewer.displayName() + ") has no API key configured. "
                            + "Add it in Settings → Credentials.");
        }

        PullRequestRef ref = parseRef(repo, number);
        PrRawDetail raw = gitHub.fetchPrDetail(pat, ref);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PR detail");
        }
        String diff = gitHub.fetchPrDiff(pat, ref);

        ReviewRequest request = new ReviewRequest(
                repo,
                number,
                null,
                raw.body(),
                raw.headSha(),
                diff,
                skill != null ? skill.context() : null);
        ReviewOutput output = reviewer.review(request);
        return draftStore.save(prId, repo, number, raw.headSha(), output);
    }

    /**
     * Streaming variant of {@link #runReview}. Forwards token deltas to
     * {@code onDelta} as they arrive from the model and persists the parsed
     * draft once streaming finishes. The returned draft is identical in shape
     * to the non-streaming path — callers that already render
     * {@link AiReviewDraft} need no special-casing.
     */
    public AiReviewDraft streamReview(
            String pat,
            long prId,
            String repo,
            int number,
            Consumer<String> onDelta)
    {
        requireNonNull(onDelta, "onDelta is null");
        ReviewSkill skill = skillService.forRepo(repo).orElse(null);
        LlmReviewer reviewer = reviewerFor(skill);
        if (!reviewer.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "The active LLM provider (" + reviewer.displayName() + ") has no API key configured. "
                            + "Add it in Settings → AI review.");
        }

        PullRequestRef ref = parseRef(repo, number);
        PrRawDetail raw = gitHub.fetchPrDetail(pat, ref);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PR detail");
        }
        String diff = gitHub.fetchPrDiff(pat, ref);

        ReviewRequest request = new ReviewRequest(
                repo,
                number,
                null,
                raw.body(),
                raw.headSha(),
                diff,
                skill != null ? skill.context() : null);
        ReviewOutput output = reviewer.reviewStream(request, onDelta);
        return draftStore.save(prId, repo, number, raw.headSha(), output);
    }

    public Optional<AiReviewDraft> latest(long prId)
    {
        return draftStore.latestForPr(prId);
    }

    public List<AiReviewDraft> history(long prId)
    {
        return draftStore.historyForPr(prId);
    }

    public void delete(long draftId)
    {
        draftStore.delete(draftId);
    }

    /**
     * Appends a human-authored inline comment to the active review draft
     * for the given PR, creating a draft if none exists. The comment is
     * staged locally — nothing is sent to GitHub until the user calls
     * {@link #publish}. Mirrors the inline-comment flow's parameters so the
     * frontend can reuse its composer without per-button reshaping.
     */
    public AiReviewDraft stageHumanComment(
            long prId,
            String repo,
            int number,
            String headSha,
            String filePath,
            int line,
            String side,
            Integer startLine,
            String startSide,
            String body)
    {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "comment body must not be empty");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "file path must not be empty");
        }
        if (startLine != null && startLine > line) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "startLine must be <= line");
        }
        AiReviewDraft draft = draftStore.findOrCreateActive(prId, repo, number, headSha);
        return draftStore.stageHumanComment(
                draft.id(), filePath, line, side, startLine, startSide, body);
    }

    /**
     * Replaces a single comment's edited_body. Pass null to clear the edit
     * and revert to the AI's original. Refuses to edit comments on a draft
     * that has already been published — that draft is frozen.
     */
    public AiReviewDraft updateCommentBody(long draftId, long commentId, String editedBody)
    {
        AiReviewDraft draft = draftStore.byId(draftId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "draft " + draftId + " not found"));
        if ("PUBLISHED".equals(draft.status())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "draft " + draftId + " is already published — edits no longer apply");
        }
        return draftStore.updateCommentBody(draftId, commentId, editedBody);
    }

    /**
     * Toggles the dismissed flag on a comment. Dismissed comments are
     * excluded from the publish payload but kept on the row so the user can
     * restore them. Same publish-frozen check as {@link #updateCommentBody}.
     */
    public AiReviewDraft setCommentDismissed(long draftId, long commentId, boolean dismissed)
    {
        AiReviewDraft draft = draftStore.byId(draftId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "draft " + draftId + " not found"));
        if ("PUBLISHED".equals(draft.status())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "draft " + draftId + " is already published — comments are frozen");
        }
        return draftStore.setCommentDismissed(draftId, commentId, dismissed);
    }

    /**
     * Drops a single comment from a draft. Same publish-frozen check as
     * {@link #updateCommentBody} — once a draft is published it's a record
     * of what was sent and shouldn't change.
     */
    public AiReviewDraft deleteComment(long draftId, long commentId)
    {
        AiReviewDraft draft = draftStore.byId(draftId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "draft " + draftId + " not found"));
        if ("PUBLISHED".equals(draft.status())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "draft " + draftId + " is already published — comments are frozen");
        }
        return draftStore.deleteComment(draftId, commentId);
    }

    /**
     * Publishes a stored draft to GitHub as a single review. {@code event}
     * controls the GitHub action — {@code "COMMENT"}, {@code "APPROVE"}, or
     * {@code "REQUEST_CHANGES"}. Inline comments are attached as line-anchored
     * review comments on the PR's most recent commit; severities flow into
     * the comment body since GitHub has no native severity field.
     *
     * <p>On success the local draft flips to {@code PUBLISHED} so the UI can
     * stop offering the publish action a second time.
     */
    /**
     * Verdict-only / mixed publish keyed by PR id rather than draft id.
     * Finds-or-creates the active review draft for the PR (so the user
     * can ship a body-only Approve / Comment without first staging a
     * comment) and forwards to {@link #publish}. Returns the published
     * draft so the frontend can clear its tray.
     */
    public AiReviewDraft publishForPr(
            Function<String, String> patForRepo,
            long prId,
            String repo,
            int number,
            String headSha,
            String event,
            String bodyOverride)
    {
        AiReviewDraft active = draftStore.findOrCreateActive(prId, repo, number, headSha);
        return publish(patForRepo, active.id(), event, bodyOverride);
    }

    public AiReviewDraft publish(Function<String, String> patForRepo, long draftId, String event, String bodyOverride)
    {
        requireNonNull(patForRepo, "patForRepo is null");
        AiReviewDraft draft = draftStore.byId(draftId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "draft " + draftId + " not found"));
        if ("PUBLISHED".equals(draft.status())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409), "draft " + draftId + " has already been published");
        }
        // Prefer the draft's own repo+number (set at run time, V15+); fall
        // back to the local PR store for legacy rows that pre-date the
        // columns. Both empty means we genuinely can't address the PR.
        String repo;
        int number;
        if (draft.repo() != null && draft.number() != null) {
            repo = draft.repo();
            number = draft.number();
        }
        else {
            PullRequest pr = pullRequestStore.findById(draft.prId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "PR for draft " + draftId + " no longer in local DB"));
            repo = pr.repo();
            number = pr.number();
        }
        String pat = patForRepo.apply(repo);

        // Dismissed comments stay on the row (so the user can restore them)
        // but never make it into the GitHub payload. Both AI and HUMAN
        // source comments are submitted in the same review.
        List<ReviewLineComment> inline = draft.comments().stream()
                .filter(c -> !c.dismissed())
                .map(c -> new ReviewLineComment(
                        c.filePath(),
                        Optional.empty(),
                        Optional.of(c.lineNumber()),
                        c.side() != null ? c.side() : "RIGHT",
                        formatCommentBody(c),
                        c.startLine() == null ? Optional.empty() : Optional.of(c.startLine()),
                        c.startSide() == null ? Optional.empty() : Optional.of(c.startSide())))
                .collect(toImmutableList());

        String reviewEvent = normaliseEvent(event);
        // Three cases:
        //   • null override         → frontend didn't open the "Finish your
        //                              review" panel; fall back to the AI
        //                              summary so an Approve / Comment from
        //                              the older code path still ships some
        //                              context.
        //   • blank-string override → the user opened the panel and cleared
        //                              the body on purpose. Respect that and
        //                              send no body — do NOT silently
        //                              substitute the AI summary they just
        //                              deleted.
        //   • non-blank override    → use it verbatim.
        String body;
        if (bodyOverride == null) {
            body = draft.summary();
        }
        else if (bodyOverride.isBlank()) {
            body = null;
        }
        else {
            body = bodyOverride.strip();
        }
        CreateReviewCommand command = new CreateReviewCommand(
                draft.headSha() == null ? Optional.empty() : Optional.of(draft.headSha()),
                Optional.ofNullable(body),
                reviewEvent,
                inline);

        gitHub.createReview(pat, parseRef(repo, number), command);
        detailInvalidator.invalidate(repo, number);
        return draftStore.markPublished(draftId);
    }

    private static String formatCommentBody(AiReviewDraft.DraftComment c)
    {
        // editedBody is the user's revision; falls back to the AI's original
        // when the user hasn't touched it.
        String text = c.editedBody() != null && !c.editedBody().isBlank() ? c.editedBody() : c.body();
        // Human-authored comments are sent verbatim — no severity prefix.
        if ("HUMAN".equals(c.source())) {
            return text;
        }
        if (c.severity() == null || c.severity().isBlank() || "suggestion".equals(c.severity())) {
            return text;
        }
        return "**[" + c.severity() + "]** " + text;
    }

    private static String normaliseEvent(String event)
    {
        if (event == null) {
            return "COMMENT";
        }
        String upper = event.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "APPROVE", "REQUEST_CHANGES", "COMMENT" -> upper;
            default -> "COMMENT";
        };
    }
}
