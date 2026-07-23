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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.Paged;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Learning-owned evidence assembler. Fetches fully-paginated (or explicitly
 * partial) review/file/commit/inline-thread/timeline evidence for one merged
 * PR, marks per-source completeness, reconstructs the outcome chains, maps the
 * changed files to current code, and snapshot-pins every stable ref.
 *
 * <p>Never touches the dashboard PrDetailStore cache. No bulk diff is kept —
 * only stable refs (GitHub ids, commit SHAs, file spans) and content digests.
 * Every ref is pinned to one of the bundle's snapshot SHAs; a candidate SHA
 * outside that set is dropped to null rather than allowed to cross the pinned
 * repository snapshot.
 */
@Component
public class PrEvidenceFetcher
{
    /** Fixed source order so the roll-up marker names sources deterministically. */
    private static final List<String> SOURCE_ORDER =
            List.of("reviews", "files", "commits", "comments", "timeline");

    private final PullRequestRepository gitHub;
    private final OutcomeChainReconstructor reconstructor;
    private final EvidenceCodeGraphMapper codeGraphMapper;

    public PrEvidenceFetcher(
            PullRequestRepository gitHub,
            OutcomeChainReconstructor reconstructor,
            EvidenceCodeGraphMapper codeGraphMapper)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.reconstructor = requireNonNull(reconstructor, "reconstructor is null");
        this.codeGraphMapper = requireNonNull(codeGraphMapper, "codeGraphMapper is null");
    }

    /**
     * Assemble the snapshot-pinned bundle. {@code repoSha} pins the "current
     * code" mapping; {@code checkout} is the verified local clone (null when
     * absent — the mapper then emits path-only refs).
     */
    public PrEvidenceBundle fetch(
            String pat,
            String workspaceId,
            String repoFullName,
            int prNumber,
            String author,
            Path checkout,
            String repoSha)
    {
        RepoRef repo = RepoRef.parse(repoFullName);
        PullRequestRef ref = PullRequestRef.of(repo.owner(), repo.repo(), prNumber);

        PrRawDetail detail = gitHub.fetchPrDetail(pat, ref);
        if (detail == null) {
            return unavailable(workspaceId, repoFullName, prNumber, author, repoSha);
        }

        Paged<PrReviewState> reviews = gitHub.fetchAllPrReviews(pat, ref);
        Paged<PullRequestDetail.ChangedFile> files = gitHub.fetchAllPrFiles(pat, ref);
        Paged<PullRequestCommit> commits = gitHub.fetchAllPrCommits(pat, ref);
        Paged<PrReviewThreadMessage> comments = gitHub.fetchAllPrReviewComments(pat, ref);
        Paged<PrTimelineEvent> timeline = gitHub.fetchAllPrTimeline(pat, ref);

        Map<String, String> completeness = new LinkedHashMap<>();
        completeness.put("reviews", marker("reviews", reviews.complete()));
        completeness.put("files", marker("files", files.complete()));
        completeness.put("commits", marker("commits", commits.complete()));
        completeness.put("comments", marker("comments", comments.complete()));
        completeness.put("timeline", marker("timeline", timeline.complete()));
        String overall = overall(completeness);

        PrEvidenceBundle bundle = new PrEvidenceBundle(
                workspaceId,
                repoFullName,
                prNumber,
                author,
                detail.baseSha(),
                detail.headSha(),
                detail.mergeCommitSha(),
                repoSha,
                reviews.items(),
                files.items(),
                commits.items(),
                comments.items(),
                timeline.items(),
                completeness,
                overall,
                List.of(),
                List.of());

        List<OutcomeChain> chains = reconstructor.reconstruct(bundle, resolvedThreadRoots(pat, ref));
        List<PrEvidenceBundle.EvidenceRef> refs = buildRefs(bundle, checkout, repoSha);

        return withChainsAndRefs(bundle, chains, refs);
    }

    /**
     * Root-comment ids GitHub marks resolved, joined from the GraphQL
     * review-thread query the REST comments endpoint can't supply. Degrades to
     * "none" when GraphQL is unavailable rather than failing the whole bundle.
     */
    private Set<Long> resolvedThreadRoots(String pat, PullRequestRef ref)
    {
        try {
            Set<Long> resolved = new HashSet<>();
            for (PullRequestRepository.ReviewThreadMeta meta
                    : gitHub.fetchReviewThreadResolution(pat, ref)) {
                if (meta.resolved()) {
                    resolved.add(meta.rootCommentDatabaseId());
                }
            }
            return resolved;
        }
        catch (RuntimeException e) {
            return Set.of();
        }
    }

    private List<PrEvidenceBundle.EvidenceRef> buildRefs(
            PrEvidenceBundle bundle, Path checkout, String repoSha)
    {
        Set<String> pinned = bundle.pinnedShas();
        List<PrEvidenceBundle.EvidenceRef> refs = new ArrayList<>();

        for (PullRequestCommit commit : bundle.commits()) {
            refs.add(new PrEvidenceBundle.EvidenceRef(
                    "commit", commit.sha(), null, pin(commit.sha(), pinned),
                    null, null, null,
                    MergedPrCatalog.sha256("commit|" + commit.sha() + "|" + commit.message())));
        }
        for (PrReviewThreadMessage message : bundle.reviewComments()) {
            if (message.inReplyTo() != null) {
                continue;                       // one ref per thread root
            }
            refs.add(new PrEvidenceBundle.EvidenceRef(
                    "thread", String.valueOf(message.githubId()), null,
                    pin(message.commitId(), pinned), message.filePath(),
                    message.lineNumber(), message.lineNumber(),
                    MergedPrCatalog.sha256("thread|" + message.githubId() + "|" + message.body())));
        }
        for (PrReviewState review : bundle.reviews()) {
            refs.add(new PrEvidenceBundle.EvidenceRef(
                    "review", null, null, null, null, null, null,
                    MergedPrCatalog.sha256(
                            "review|" + review.login() + "|" + review.state() + "|" + review.submittedAt())));
        }
        // Stable, queryable timeline events (those carrying a GitHub id); a
        // force-push's after-SHA is pinned when it lands in the snapshot set.
        for (PrTimelineEvent event : bundle.timeline()) {
            if (event.githubId() == null) {
                continue;
            }
            refs.add(new PrEvidenceBundle.EvidenceRef(
                    "timeline", String.valueOf(event.githubId()), null,
                    pin(event.afterSha(), pinned), null, null, null,
                    MergedPrCatalog.sha256(
                            "timeline|" + event.githubId() + "|" + event.event() + "|" + event.timestamp())));
        }
        // Current-code file/symbol refs, all pinned to repoSha.
        refs.addAll(codeGraphMapper.attach(bundle, checkout, repoSha));
        return refs;
    }

    /** Keep the SHA only when it is one of the bundle's pinned snapshots. */
    private static String pin(String sha, Set<String> pinned)
    {
        return sha != null && pinned.contains(sha) ? sha : null;
    }

    private static String marker(String source, boolean complete)
    {
        return complete ? "complete" : "partial:" + source;
    }

    private static String overall(Map<String, String> completeness)
    {
        for (String source : SOURCE_ORDER) {
            if (!"complete".equals(completeness.get(source))) {
                return "partial:" + source;
            }
        }
        return "complete";
    }

    private PrEvidenceBundle unavailable(
            String workspaceId, String repo, int prNumber, String author, String repoSha)
    {
        Map<String, String> completeness = new LinkedHashMap<>();
        SOURCE_ORDER.forEach(s -> completeness.put(s, "unavailable"));
        return new PrEvidenceBundle(
                workspaceId, repo, prNumber, author, null, null, null, repoSha,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                completeness, "unavailable", List.of(), List.of());
    }

    private static PrEvidenceBundle withChainsAndRefs(
            PrEvidenceBundle b, List<OutcomeChain> chains, List<PrEvidenceBundle.EvidenceRef> refs)
    {
        return new PrEvidenceBundle(
                b.workspaceId(), b.repo(), b.prNumber(), b.author(),
                b.baseSha(), b.headSha(), b.mergeSha(), b.repoSha(),
                b.reviews(), b.files(), b.commits(), b.reviewComments(), b.timeline(),
                b.completeness(), b.overallCompleteness(), refs, chains);
    }
}
