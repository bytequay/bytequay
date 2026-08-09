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
import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.domain.Skill;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.repository.AiReviewDraftStore;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.SkillStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.GitHubResponseCache;
import com.bytequay.app.service.pr.PullRequestDetailInvalidator;
import com.bytequay.app.service.skills.SkillService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAiReviewService
{
    @Test
    void testQuickReviewUsesUnifiedExternalPrAndExplicitNoToolsScope()
    {
        PRService prs = mock(PRService.class);
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        LlmReviewerRegistry registry = mock(LlmReviewerRegistry.class);
        GlobalReviewRunner globalReview = mock(GlobalReviewRunner.class);
        AiReviewDraftStore draftStore = mock(AiReviewDraftStore.class);
        SkillService skills = mock(SkillService.class);
        PatResolver pats = mock(PatResolver.class);
        PullRequestDetailInvalidator invalidator = mock(PullRequestDetailInvalidator.class);
        PR pr = externalPr("unified-pr");
        PrRawDetail raw = rawDetail();
        String existingBody = "**Crash:** this path can loop forever.";
        String newBody = "**Data loss:** return before mutating the shared state.";
        ReviewOutput output = new ReviewOutput("Warnings worth considering: rename a helper.", List.of(
                new ReviewOutput.LineComment("src/Existing.java", 9, existingBody, "critical"),
                new ReviewOutput.LineComment("src/New.java", 14, newBody, "REQUEST CHANGES"),
                new ReviewOutput.LineComment("src/Error.java", 15, "Null input crashes.", "ERROR"),
                new ReviewOutput.LineComment("src/Warning.java", 3, "Optional cleanup.", "warning"),
                new ReviewOutput.LineComment("src/Suggestion.java", 4, "Consider renaming this.", "suggestion"),
                new ReviewOutput.LineComment("src/Info.java", 4, "Context only.", "info"),
                new ReviewOutput.LineComment("src/NotInDiff.java", 99, "Hallucinated anchor.", "critical"),
                new ReviewOutput.LineComment("", 5, "Missing anchor.", "error"),
                new ReviewOutput.LineComment("src/BadLine.java", 0, "Bad line.", "blocker")),
                "claude", "model");
        String diff = diffAt("src/Existing.java", 9)
                + diffAt("src/New.java", 14)
                + diffAt("src/Error.java", 15)
                + diffAt("src/Warning.java", 3)
                + diffAt("src/Suggestion.java", 4)
                + diffAt("src/Info.java", 4);
        AiReviewDraft expected = draft("COMPLETE");
        PRComment existing = new PRComment(
                "comment-1", "unified-pr", PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE, "src/Existing.java", 9, "you", existingBody,
                Instant.parse("2026-05-08T00:00:00Z"), null, null, null, null, null,
                "RIGHT", null, null);
        PRComment staleQuickReview = new PRComment(
                "comment-stale", "unified-pr", PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE, "src/Stale.java", 2, "ai-reviewer", "Stale finding.",
                Instant.parse("2026-05-08T00:00:00Z"), null, null, null, null, null,
                "RIGHT", null, null);

        when(prs.findById("unified-pr")).thenReturn(Optional.of(pr));
        when(prs.comments("unified-pr")).thenReturn(List.of(existing, staleQuickReview));
        when(pats.resolve("owner/repo")).thenReturn("pat");
        when(gitHub.fetchPrDetail("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(raw);
        when(gitHub.fetchPrDiff("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(diff);
        when(skills.forRepo("owner/repo")).thenReturn(Optional.empty());
        when(globalReview.review(any(ReviewRequest.class))).thenReturn(output);
        when(draftStore.saveForUnifiedPr(
                eq("unified-pr"), eq("owner/repo"), eq(7), eq("abc123"), any(ReviewOutput.class)))
                .thenReturn(expected);

        AiReviewService service = new AiReviewService(
                mock(PullRequestStore.class), prs, gitHub, registry, globalReview, draftStore,
                skills, invalidator, pats);

        assertThat(service.runQuickReview("unified-pr")).isSameAs(expected);
        ArgumentCaptor<ReviewRequest> request = ArgumentCaptor.forClass(ReviewRequest.class);
        verify(globalReview).review(request.capture());
        assertThat(request.getValue().diff()).isEqualTo(diff);
        assertThat(request.getValue().skillContext())
                .contains("Review only the pull-request description and complete unified diff")
                .contains("no repository exploration")
                .contains("Never emit info, suggestion, warning")
                .contains("precise and ADHD-friendly");
        ArgumentCaptor<ReviewOutput> savedOutput = ArgumentCaptor.forClass(ReviewOutput.class);
        verify(draftStore).saveForUnifiedPr(
                eq("unified-pr"), eq("owner/repo"), eq(7), eq("abc123"), savedOutput.capture());
        assertThat(savedOutput.getValue().comments())
                .extracting(ReviewOutput.LineComment::file)
                .containsExactly("src/Existing.java", "src/New.java", "src/Error.java");
        assertThat(savedOutput.getValue().comments())
                .extracting(ReviewOutput.LineComment::severity)
                .containsOnly("blocker");
        assertThat(savedOutput.getValue().summary()).isEqualTo(
                "Quick review found 3 merge-blocking findings in the supplied diff.");
        verify(prs).deleteDraftComment("comment-stale");
        verify(prs).addComment(
                "unified-pr", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/New.java", 14, "RIGHT", null, null, "ai-reviewer", newBody, null);
        verify(prs).addComment(
                "unified-pr", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Error.java", 15, "RIGHT", null, null, "ai-reviewer",
                "Null input crashes.", null);
        var completion = inOrder(prs, draftStore);
        completion.verify(prs).addComment(
                "unified-pr", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/New.java", 14, "RIGHT", null, null, "ai-reviewer", newBody, null);
        completion.verify(prs).addComment(
                "unified-pr", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Error.java", 15, "RIGHT", null, null, "ai-reviewer",
                "Null input crashes.", null);
        completion.verify(draftStore).saveForUnifiedPr(
                eq("unified-pr"), eq("owner/repo"), eq(7), eq("abc123"), any(ReviewOutput.class));
    }

    @Test
    void testQuickReviewRejectsOversizedDiffInsteadOfTruncating()
    {
        PRService prs = mock(PRService.class);
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        LlmReviewerRegistry registry = mock(LlmReviewerRegistry.class);
        GlobalReviewRunner globalReview = mock(GlobalReviewRunner.class);
        AiReviewDraftStore draftStore = mock(AiReviewDraftStore.class);
        SkillService skills = mock(SkillService.class);
        PatResolver pats = mock(PatResolver.class);

        when(prs.findById("unified-pr")).thenReturn(Optional.of(externalPr("unified-pr")));
        when(pats.resolve("owner/repo")).thenReturn("pat");
        when(gitHub.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(rawDetail());
        when(gitHub.fetchPrDiff(eq("pat"), any(PullRequestRef.class)))
                .thenReturn("x".repeat(ReviewPrompt.MAX_DIFF_CHARS + 1));

        AiReviewService service = new AiReviewService(
                mock(PullRequestStore.class), prs, gitHub, registry, globalReview, draftStore,
                skills, mock(PullRequestDetailInvalidator.class), pats);

        assertThatThrownBy(() -> service.runQuickReview("unified-pr"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(413);
                    assertThat(error.getReason()).contains("Watch the repo and run a full review");
                });
        verify(globalReview, never()).review(any());
        verify(draftStore, never()).saveForUnifiedPr(any(), any(), eq(7), any(), any());
    }

    @Test
    void testQuickReviewRetriesTaskPrWhenExternalPrFoldsDuringMaterialisation()
    {
        PRService prs = mock(PRService.class);
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        LlmReviewerRegistry registry = mock(LlmReviewerRegistry.class);
        GlobalReviewRunner globalReview = mock(GlobalReviewRunner.class);
        AiReviewDraftStore draftStore = mock(AiReviewDraftStore.class);
        SkillService skills = mock(SkillService.class);
        PatResolver pats = mock(PatResolver.class);
        PR external = externalPr("external-pr");
        PR survivor = PR.create(
                        "task-pr", "task-1", "head", "main", "A title", "description",
                        Instant.parse("2026-05-08T00:00:00Z"))
                .withRemote("owner/repo", 7, "https://github.com/owner/repo/pull/7",
                        Instant.parse("2026-05-08T00:00:00Z"));
        ReviewOutput output = new ReviewOutput("summary", List.of(
                new ReviewOutput.LineComment(
                        "src/Folded.java", 12, "**Crash:** guard the null input.", "blocker")),
                "claude", "model");
        AiReviewDraft reparented = draft("COMPLETE");

        when(prs.findById("external-pr"))
                .thenReturn(Optional.of(external), Optional.of(external), Optional.empty());
        when(prs.findTaskByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(survivor));
        when(prs.comments("external-pr"))
                .thenThrow(new IllegalArgumentException("external PR was folded"));
        when(pats.resolve("owner/repo")).thenReturn("pat");
        when(gitHub.fetchPrDetail("pat", PullRequestRef.of("owner", "repo", 7)))
                .thenReturn(rawDetail());
        when(gitHub.fetchPrDiff("pat", PullRequestRef.of("owner", "repo", 7)))
                .thenReturn(diffAt("src/Folded.java", 12));
        when(skills.forRepo("owner/repo")).thenReturn(Optional.empty());
        when(globalReview.review(any(ReviewRequest.class))).thenReturn(output);
        when(draftStore.saveForUnifiedPr(
                eq("task-pr"), eq("owner/repo"), eq(7), eq("abc123"), any(ReviewOutput.class)))
                .thenReturn(reparented);

        AiReviewService service = new AiReviewService(
                mock(PullRequestStore.class), prs, gitHub, registry, globalReview, draftStore,
                skills, mock(PullRequestDetailInvalidator.class), pats);

        assertThat(service.runQuickReview("external-pr")).isSameAs(reparented);
        verify(prs).addComment(
                "task-pr", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Folded.java", 12, "RIGHT", null, null, "ai-reviewer",
                "**Crash:** guard the null input.", null);
    }

    @Test
    void testLatestQuickReviewFiltersLegacyBlockersWithoutWriting()
    {
        PRService prs = mock(PRService.class);
        AiReviewDraftStore draftStore = mock(AiReviewDraftStore.class);
        AiReviewDraft legacy = new AiReviewDraft(
                9L, 0L, "owner/repo", 7, "legacy summary", "provider", "model", "abc123",
                "COMPLETE", Instant.parse("2026-05-08T00:00:00Z"),
                Instant.parse("2026-05-08T00:00:00Z"), List.of(
                        new AiReviewDraft.DraftComment(
                                1, "src/Critical.java", 8, "**Crash:** guard null.", null,
                                "blocker", false, "AI", "RIGHT", null, null),
                        new AiReviewDraft.DraftComment(
                                2, "src/Warning.java", 9, "Optional cleanup.", null,
                                "warning", false, "AI", "RIGHT", null, null),
                        new AiReviewDraft.DraftComment(
                                3, "src/Human.java", 10, "Human draft.", null,
                                "blocker", false, "HUMAN", "RIGHT", null, null)));
        when(draftStore.latestForUnifiedPr("unified-pr")).thenReturn(Optional.of(legacy));

        AiReviewService service = new AiReviewService(
                mock(PullRequestStore.class), prs, mock(PullRequestRepository.class),
                mock(LlmReviewerRegistry.class), mock(GlobalReviewRunner.class), draftStore,
                mock(SkillService.class), mock(PullRequestDetailInvalidator.class),
                mock(PatResolver.class));

        AiReviewDraft result = service.latestQuickReview("unified-pr").orElseThrow();

        assertThat(result.comments())
                .extracting(AiReviewDraft.DraftComment::filePath)
                .containsExactly("src/Critical.java");
        assertThat(result.summary()).isEqualTo(
                "Quick review found 1 merge-blocking finding in the supplied diff.");
        verifyNoInteractions(prs);
    }

    @Test
    void testQuickReviewRejectsLocalTaskPrBeforeCallingGitHub()
    {
        PRService prs = mock(PRService.class);
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        PR local = PR.create(
                "local-pr", "task-1", "feature", "main", "Title", "Description",
                Instant.parse("2026-05-08T00:00:00Z"));
        when(prs.findById("local-pr")).thenReturn(Optional.of(local));
        AiReviewService service = new AiReviewService(
                mock(PullRequestStore.class), prs, gitHub, mock(LlmReviewerRegistry.class),
                mock(GlobalReviewRunner.class),
                mock(AiReviewDraftStore.class), mock(SkillService.class),
                mock(PullRequestDetailInvalidator.class), mock(PatResolver.class));

        assertThatThrownBy(() -> service.runQuickReview("local-pr"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode().value()).isEqualTo(422));
        verifyNoInteractions(gitHub);
    }

    private static PR externalPr(String id)
    {
        return PR.createExternal(
                id, "owner/repo", 7, "https://github.com/owner/repo/pull/7", "author",
                "head", "main", "A title", "description", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-05-08T00:00:00Z"), null, null);
    }

    private static PrRawDetail rawDetail()
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1, 0, List.of(),
                "abc123", "head", "owner/repo", "main", "owner/repo");
    }

    private static String diffAt(String path, int line)
    {
        return """
                diff --git a/%1$s b/%1$s
                --- a/%1$s
                +++ b/%1$s
                @@ -%2$d +%2$d @@
                -old
                +new
                """.formatted(path, line);
    }

    private static AiReviewDraft draft(String status)
    {
        return new AiReviewDraft(
                5L,
                123L,
                "owner/repo",
                7,
                "summary",
                "provider",
                "model",
                "abc123",
                status,
                Instant.parse("2026-05-08T00:00:00Z"),
                Instant.parse("2026-05-08T00:00:00Z"),
                ImmutableList.of());
    }

    private static final class RecordingGitHub
            implements PullRequestRepository
    {
        private final List<String> events;

        private RecordingGitHub(List<String> events)
        {
            this.events = events;
        }

        @Override
        public PullRequestReview createReview(String pat, PullRequestRef pr, CreateReviewCommand command)
        {
            events.add("github");
            return null;
        }
    }

    private static final class RecordingDraftStore
            implements AiReviewDraftStore
    {
        private final List<String> events;
        private final Map<Long, AiReviewDraft> drafts = new HashMap<>();
        private final AiReviewDraft published;

        private RecordingDraftStore(List<String> events, AiReviewDraft draft, AiReviewDraft published)
        {
            this.events = events;
            this.published = published;
            drafts.put(draft.id(), draft);
        }

        @Override
        public Optional<AiReviewDraft> byId(long draftId)
        {
            return Optional.ofNullable(drafts.get(draftId));
        }

        @Override
        public AiReviewDraft markPublished(long draftId)
        {
            events.add("publish");
            drafts.put(draftId, published);
            return published;
        }

        @Override public AiReviewDraft save(long prId, String repo, int number, String headSha, ReviewOutput output)
        {
            throw new UnsupportedOperationException();
        }
        @Override public AiReviewDraft saveForUnifiedPr(String prId, String repo, int number, String headSha, ReviewOutput output) { throw new UnsupportedOperationException(); }
        @Override public Optional<AiReviewDraft> latestForPr(long prId) { throw new UnsupportedOperationException(); }
        @Override public Optional<AiReviewDraft> latestForUnifiedPr(String prId) { throw new UnsupportedOperationException(); }
        @Override public void reparentUnifiedPr(String fromPrId, String toPrId) { throw new UnsupportedOperationException(); }
        @Override public List<AiReviewDraft> historyForPr(long prId) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft updateCommentBody(long draftId, long commentId, String editedBody) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft deleteComment(long draftId, long commentId) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft setCommentDismissed(long draftId, long commentId, boolean dismissed) { throw new UnsupportedOperationException(); }
        @Override public void delete(long draftId) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft findOrCreateActive(long prId, String repo, int number, String headSha) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft stageHumanComment(long draftId, String filePath, int lineNumber, String side, Integer startLine, String startSide, String body) { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingInvalidator
            extends PullRequestDetailInvalidator
    {
        private final List<String> events;

        private RecordingInvalidator(List<String> events)
        {
            super(new UnsupportedPullRequestStore(), new UnsupportedPrDetailStore(), new GitHubResponseCache());
            this.events = events;
        }

        @Override
        public void invalidate(String repo, int number)
        {
            events.add("invalidate");
        }
    }

    private static final class EmptyAppSettingsStore
            implements AppSettingsStore
    {
        @Override public Optional<String> get(String key) { return Optional.empty(); }
        @Override public void set(String key, String value) { throw new UnsupportedOperationException(); }
    }

    private static final class EmptySkillStore
            implements SkillStore
    {
        @Override public List<Skill> list() { return List.of(); }
        @Override public Optional<Skill> byId(long id) { return Optional.empty(); }
        @Override public Optional<Skill> byName(String name) { return Optional.empty(); }
        @Override public List<Skill> findGlobal() { return List.of(); }
        @Override public List<Skill> findByRepo(String repo) { return List.of(); }
        @Override public Optional<Skill> findRubricForRepo(String repo) { return Optional.empty(); }
        @Override public Skill create(String scope, String repo, String threadId, String name, String description, String body, String kind, String usage, String roleTag, boolean isDefault, String source, String provenance) { throw new UnsupportedOperationException(); }
        @Override public Skill update(long id, String scope, String repo, String threadId, String name, String description, String body, String kind, String usage, String roleTag, boolean isDefault) { throw new UnsupportedOperationException(); }
        @Override public void delete(long id) { throw new UnsupportedOperationException(); }
        @Override public Skill setEnabled(long id, boolean enabled) { throw new UnsupportedOperationException(); }
        @Override public Skill setDefault(long id) { throw new UnsupportedOperationException(); }
    }

    private static final class UnsupportedPullRequestStore
            implements PullRequestStore
    {
        @Override public List<PullRequest> findAll() { throw new UnsupportedOperationException(); }
        @Override public void replaceAll(List<PullRequest> pullRequests) { throw new UnsupportedOperationException(); }
        @Override public Optional<Instant> lastSyncedAt() { throw new UnsupportedOperationException(); }
        @Override public Map<Long, Instant> findUpdatedAtMap() { throw new UnsupportedOperationException(); }
        @Override public Set<Long> findIdsMissingEnrichment() { throw new UnsupportedOperationException(); }
        @Override public Optional<Long> findIdByRepoAndNumber(String repo, int number) { throw new UnsupportedOperationException(); }
        @Override public Optional<PullRequest> findById(long prId) { throw new UnsupportedOperationException(); }
        @Override public void updateEnrichment(long prId, PullRequestDetail.CiStatus ciStatus, int additions, int deletions, int commentCount, AttentionReason attentionReason, Boolean mergeable, String mergeableState, Instant headPushedAt, Map<String, String> reviewerVerdicts, String headRef) { throw new UnsupportedOperationException(); }
        @Override public void updateCiStatus(long prId, PullRequestDetail.CiStatus ciStatus) { throw new UnsupportedOperationException(); }
    }

    private static final class UnsupportedPrDetailStore
            implements PrDetailStore
    {
        @Override public Optional<StoredPrDetail> find(long prId) { throw new UnsupportedOperationException(); }
        @Override public void save(long prId, StoredPrDetail detail) { throw new UnsupportedOperationException(); }
        @Override public void deleteByPrIds(Set<Long> prIds) { throw new UnsupportedOperationException(); }
    }

    private static final class FixedPatResolver
            extends PatResolver
    {
        private final String token;

        private FixedPatResolver(String token)
        {
            super(Mockito.mock(CredentialService.class), Mockito.mock(GhCliService.class));
            this.token = token;
        }

        @Override
        public String resolve(String repoFullName)
        {
            return token;
        }

        @Override
        public String resolve()
        {
            return token;
        }
    }
}
