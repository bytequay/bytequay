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
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.domain.Skill;
import com.bytequay.app.repository.AiReviewDraftStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.skills.SkillService;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * Orchestrates a single AI review run: fetches the PR's raw detail and
 * unified diff from GitHub, hands it to the active {@link LlmReviewer}, and
 * persists the resulting draft. First slice is non-streaming.
 */
@Service
public class AiReviewService
{
    private static final String QUICK_REVIEW_AUTHOR = "ai-reviewer";
    private static final Pattern DIFF_HUNK_HEADER = Pattern.compile(
            "^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");
    private static final String QUICK_REVIEW_SCOPE = """
            The following quick-review rules override all other review guidance in this prompt.
            Quick-review scope: Review only the pull-request description and complete unified diff supplied in this request.
            You have no repository exploration, file-reading, search, history, test, or code-navigation tools.
            Do not claim anything about code outside the supplied diff; call out uncertainty instead.

            Quick-review output policy:
            - Emit only critical, error-level defects that must be fixed before merge and warrant REQUEST_CHANGES.
            - Use severity "blocker" for every emitted comment. Never emit info, suggestion, warning, nit, praise, or optional-improvement comments.
            - Prefer an empty comments array over a weak or speculative finding.
            - Keep the summary to at most two short sentences and mention only retained merge-blocking defects; do not hide lower-severity feedback in it.
            - Keep each comment precise and ADHD-friendly: at most three short sentences or 80 words. Lead with the concrete impact, then the smallest actionable fix. Use Markdown only where it improves scanning.
            """;

    private final PRService prs;
    private final PullRequestRepository gitHub;
    private final LlmReviewerRegistry registry;
    private final GlobalReviewRunner globalReview;
    private final AiReviewDraftStore draftStore;
    private final SkillService skillService;
    private final PatResolver patResolver;

    public AiReviewService(
            PRService prs,
            PullRequestRepository gitHub,
            LlmReviewerRegistry registry,
            GlobalReviewRunner globalReview,
            AiReviewDraftStore draftStore,
            SkillService skillService,
            PatResolver patResolver)
    {
        this.prs = requireNonNull(prs, "prs is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.globalReview = requireNonNull(globalReview, "globalReview is null");
        this.draftStore = requireNonNull(draftStore, "draftStore is null");
        this.skillService = requireNonNull(skillService, "skillService is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
    }

    /**
     * Runs the deliberately bounded, no-tools review offered for an external
     * PR whose repository is not watched locally. This path owns no agent
     * session: it sends one complete GitHub diff to one configured reviewer
     * and stores the result directly against the unified PR id.
     */
    public AiReviewDraft runQuickReview(String prId)
    {
        if (prId == null || prId.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "prId must not be empty");
        }
        PR pr = prs.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "PR " + prId + " not found"));
        if (!PR.ORIGIN_EXTERNAL.equals(pr.origin()) || pr.repo() == null || pr.remotePrNumber() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(422),
                    "Quick review is only available for external GitHub PRs; watch the repo for a full review");
        }

        String repo = pr.repo();
        int number = pr.remotePrNumber();
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        PrRawDetail raw = gitHub.fetchPrDetail(pat, ref);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PR detail");
        }
        String diff = requireNonNullElse(gitHub.fetchPrDiff(pat, ref), "");
        if (diff.length() > ReviewPrompt.MAX_DIFF_CHARS) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "This PR diff is too large for quick review (" + diff.length() + " characters). "
                            + "Watch the repo and run a full review instead.");
        }

        String skillContext = skillService.forRepo(repo)
                .map(Skill::body)
                .filter(body -> !body.isBlank())
                .map(body -> QUICK_REVIEW_SCOPE + "\n" + body)
                .orElse(QUICK_REVIEW_SCOPE);
        ReviewRequest request = new ReviewRequest(
                repo, number, pr.title(), raw.body(), raw.headSha(), diff, skillContext);
        ReviewOutput output = filterQuickReviewOutput(globalReview.review(request), diff);

        PR target = materialiseOnCurrentQuickReviewTarget(
                prId, repo, number, output.comments());

        // Save COMPLETE only after canonical pending comments are durable. If
        // reconciliation fails, no persisted result can disable Retry after a
        // restart; a retry safely converges the provenance-owned comment set.
        AiReviewDraft saved = draftStore.saveForUnifiedPr(
                target.id(), repo, number, raw.headSha(), output);

        // Close the final save-vs-fold race. The fold transaction moves both
        // comments and any draft already present; reparent is idempotent for
        // the case where the draft insert landed just after that transaction.
        PR finalTarget = quickReviewTarget(prId, repo, number)
                .orElseThrow(() -> quickReviewTargetMissing(prId));
        if (!finalTarget.id().equals(target.id())) {
            draftStore.reparentUnifiedPr(target.id(), finalTarget.id());
            return draftStore.latestForUnifiedPr(finalTarget.id()).orElse(saved);
        }
        return saved;
    }

    /**
     * Reconciles against the external row or its task-owned fold survivor.
     * If the fold lands between reading the target and writing comments, retry
     * once on the survivor instead of leaving a partial result behind.
     */
    private PR materialiseOnCurrentQuickReviewTarget(
            String sourcePrId,
            String repo,
            int number,
            List<ReviewOutput.LineComment> comments)
    {
        PR target = quickReviewTarget(sourcePrId, repo, number)
                .orElseThrow(() -> quickReviewTargetMissing(sourcePrId));
        try {
            materialiseQuickReviewComments(target.id(), comments);
        }
        catch (RuntimeException failure) {
            Optional<PR> moved = quickReviewTarget(sourcePrId, repo, number)
                    .filter(candidate -> !candidate.id().equals(target.id()));
            if (moved.isEmpty()) {
                throw failure;
            }
            materialiseQuickReviewComments(moved.get().id(), comments);
            return moved.get();
        }

        // Covers a successful empty-result reconciliation and a fold that
        // moved all inserted rows immediately after the final comment write.
        PR settled = quickReviewTarget(sourcePrId, repo, number)
                .orElseThrow(() -> quickReviewTargetMissing(sourcePrId));
        if (!settled.id().equals(target.id())) {
            materialiseQuickReviewComments(settled.id(), comments);
        }
        return settled;
    }

    /**
     * Copies the kept one-shot findings into the canonical local PR review
     * draft stream. Changes, the submit drawer, and the PR timeline all read
     * this stream, so the generated comments behave exactly like review text
     * staged locally. Replacing only open quick-review drafts makes
     * a failed/retried materialisation safe while preserving every human,
     * resolved, dismissed, or already-published comment.
     */
    private void materialiseQuickReviewComments(
            String prId, List<ReviewOutput.LineComment> comments)
    {
        List<PRComment> allComments = prs.comments(prId);
        Set<String> repliedRoots = allComments.stream()
                .map(PRComment::parentCommentId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        List<PRComment> openComments = allComments.stream()
                .filter(comment -> PRComment.ORIGIN_LOCAL.equals(comment.origin()))
                .filter(comment -> PRComment.SCOPE_FILE_LINE.equals(comment.scope()))
                .filter(comment -> comment.parentCommentId() == null)
                .filter(comment -> comment.publishedAt() == null
                        && comment.strippedOnPushAt() == null
                        && comment.resolvedAt() == null
                        && comment.dismissedAt() == null)
                .toList();

        // A quick-review thread with a reply is now user-owned conversation
        // history. Never delete it during replacement; its key also prevents
        // the latest model output from creating a duplicate beside it.
        List<PRComment> replaceable = openComments.stream()
                .filter(comment -> QUICK_REVIEW_AUTHOR.equals(comment.author()))
                .filter(comment -> !repliedRoots.contains(comment.id()))
                .toList();
        Set<QuickReviewCommentKey> retained = new HashSet<>();
        openComments.stream()
                .filter(comment -> !replaceable.contains(comment))
                .map(comment -> new QuickReviewCommentKey(
                        comment.filePath(), comment.lineNumber(), comment.body()))
                .forEach(retained::add);

        Map<QuickReviewCommentKey, ReviewOutput.LineComment> desired = new LinkedHashMap<>();
        for (ReviewOutput.LineComment comment : comments) {
            if (comment.file() == null || comment.file().isBlank()
                    || comment.line() < 1 || comment.body() == null || comment.body().isBlank()) {
                continue;
            }
            QuickReviewCommentKey key = new QuickReviewCommentKey(
                    comment.file(), comment.line(), comment.body());
            if (retained.add(key)) {
                desired.putIfAbsent(key, comment);
            }
        }

        Set<QuickReviewCommentKey> current = replaceable.stream()
                .map(comment -> new QuickReviewCommentKey(
                        comment.filePath(), comment.lineNumber(), comment.body()))
                .collect(Collectors.toSet());
        if (current.equals(desired.keySet()) && replaceable.size() == desired.size()) {
            return;
        }
        replaceable.forEach(comment -> prs.deleteDraftComment(comment.id()));
        for (ReviewOutput.LineComment comment : desired.values()) {
            prs.addComment(
                    prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                    comment.file(), comment.line(), "RIGHT", null, null,
                    QUICK_REVIEW_AUTHOR, comment.body(), null);
        }
    }

    /**
     * Treat the model response as untrusted: quick review has a narrower
     * contract than the shared global runner, so enforce it before either
     * result store can expose the response to the UI.
     */
    private static ReviewOutput filterQuickReviewOutput(ReviewOutput output, String diff)
    {
        Set<QuickReviewAnchor> anchors = quickReviewAnchors(diff);
        List<ReviewOutput.LineComment> comments = Optional.ofNullable(output.comments())
                .orElse(List.of()).stream()
                .filter(AiReviewService::isValidQuickReviewComment)
                .filter(comment -> anchors.contains(
                        new QuickReviewAnchor(comment.file(), comment.line())))
                .map(comment -> new ReviewOutput.LineComment(
                        comment.file(), comment.line(), comment.body(), "blocker"))
                .distinct()
                .toList();
        return new ReviewOutput(
                quickReviewSummary(comments.size()), comments,
                output.providerId(), output.modelName());
    }

    private static boolean isValidQuickReviewComment(ReviewOutput.LineComment comment)
    {
        if (comment == null || comment.file() == null || comment.file().isBlank()
                || comment.line() < 1 || comment.body() == null || comment.body().isBlank()) {
            return false;
        }
        return isCriticalSeverity(comment.severity());
    }

    /** Every new-side line GitHub can accept as a RIGHT-side review anchor. */
    private static Set<QuickReviewAnchor> quickReviewAnchors(String diff)
    {
        Set<QuickReviewAnchor> anchors = new HashSet<>();
        String path = null;
        int newLine = -1;
        for (String raw : requireNonNullElse(diff, "").split("\\R", -1)) {
            if (raw.startsWith("diff --git ")) {
                path = null;
                newLine = -1;
                continue;
            }
            if (newLine < 0 && raw.startsWith("+++ b/")) {
                path = raw.substring("+++ b/".length());
                continue;
            }
            Matcher hunk = DIFF_HUNK_HEADER.matcher(raw);
            if (hunk.matches()) {
                newLine = Integer.parseInt(hunk.group(1));
                continue;
            }
            if (path == null || newLine < 0 || raw.startsWith("\\ No newline")) {
                continue;
            }
            if (raw.startsWith("-")) {
                continue;
            }
            if (raw.startsWith("+") || raw.startsWith(" ")) {
                anchors.add(new QuickReviewAnchor(path, newLine++));
            }
        }
        return ImmutableSet.copyOf(anchors);
    }

    private Optional<PR> quickReviewTarget(String sourcePrId, String repo, int number)
    {
        return prs.findById(sourcePrId)
                .or(() -> prs.findTaskByRepoAndNumber(repo, number));
    }

    private static ResponseStatusException quickReviewTargetMissing(String prId)
    {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "PR " + prId + " changed while quick review was running; retry the review");
    }

    private record QuickReviewCommentKey(String file, Integer line, String body) {}

    private record QuickReviewAnchor(String file, int line) {}

    public Optional<AiReviewDraft> latestQuickReview(String prId)
    {
        return draftStore.latestForUnifiedPr(prId).map(AiReviewService::filterStoredQuickReview);
    }

    /** Keeps older stored results inside the current blocker-only contract. */
    private static AiReviewDraft filterStoredQuickReview(AiReviewDraft draft)
    {
        List<AiReviewDraft.DraftComment> comments = draft.comments().stream()
                .filter(comment -> "AI".equals(comment.source()))
                .filter(comment -> !comment.dismissed())
                .filter(comment -> isCriticalSeverity(comment.severity()))
                .filter(comment -> comment.filePath() != null && !comment.filePath().isBlank()
                        && comment.lineNumber() > 0 && comment.body() != null && !comment.body().isBlank())
                .map(comment -> new AiReviewDraft.DraftComment(
                        comment.id(), comment.filePath(), comment.lineNumber(), comment.body(),
                        comment.editedBody(), "blocker", false, comment.source(),
                        comment.side(), comment.startLine(), comment.startSide()))
                .toList();
        return new AiReviewDraft(
                draft.id(), draft.prId(), draft.repo(), draft.number(), quickReviewSummary(comments.size()),
                draft.providerId(), draft.model(), draft.headSha(), draft.status(),
                draft.createdAt(), draft.updatedAt(), comments);
    }

    private static String quickReviewSummary(int findingCount)
    {
        if (findingCount == 0) {
            return "No merge-blocking findings in the supplied diff.";
        }
        return "Quick review found %d merge-blocking %s in the supplied diff."
                .formatted(findingCount, findingCount == 1 ? "finding" : "findings");
    }

    private static boolean isCriticalSeverity(String raw)
    {
        if (raw == null) {
            return false;
        }
        String severity = raw.strip().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (severity) {
            case "blocker", "critical", "error", "request_changes" -> true;
            default -> false;
        };
    }

    /**
     * Runs a review against the active LLM and stores the result. The caller
     * passes the GitHub PR id directly so the run works on PRs that aren't
     * in the local pull_requests table (watched-repo browse, team filter,
     * external links). repo + number are persisted on the draft so publish
     * doesn't need a second lookup either.
     */
    public AiReviewDraft runReview(long prId, String repo, int number)
    {
        Optional<Skill> skill = skillService.forRepo(repo);
        LlmReviewer reviewer = registry.active();
        if (!reviewer.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "The active LLM provider (" + reviewer.displayName() + ") has no API key configured. "
                            + "Add it in Settings → Credentials.");
        }

        String pat = patResolver.resolve(repo);
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
                skill.map(Skill::body).orElse(null));
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
            long prId,
            String repo,
            int number,
            Consumer<String> onDelta)
    {
        requireNonNull(onDelta, "onDelta is null");
        Optional<Skill> skill = skillService.forRepo(repo);
        LlmReviewer reviewer = registry.active();
        if (!reviewer.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "The active LLM provider (" + reviewer.displayName() + ") has no API key configured. "
                            + "Add it in Settings → AI review.");
        }

        String pat = patResolver.resolve(repo);
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
                skill.map(Skill::body).orElse(null));
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

}
