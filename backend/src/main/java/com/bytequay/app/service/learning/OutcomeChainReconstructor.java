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

import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PullRequestCommit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reconstructs reviewer-concern -> author-change -> resolution -> merge chains
 * from an evidence bundle, deterministically. Two concern sources:
 *
 * <ul>
 *   <li>each inline review-comment thread <em>root</em> raised by someone other
 *       than the PR author (a line-anchored concern), and</li>
 *   <li>each {@code CHANGES_REQUESTED} review (a review-level concern).</li>
 * </ul>
 *
 * <p>A concern is "addressed" when the PR author pushed a follow-up commit
 * <em>after</em> the concern was raised. Depth counts the real linkage —
 * addressed, resolved, merged-after-change — never the raw comment volume, so
 * a long naming debate that changed nothing stays at depth 0.
 */
@Component
public class OutcomeChainReconstructor
{
    public List<OutcomeChain> reconstruct(PrEvidenceBundle bundle)
    {
        return reconstruct(bundle, Set.of());
    }

    /**
     * @param resolvedRootIds REST database ids of inline-thread roots GitHub
     * reports as resolved. The REST comments endpoint can't carry thread
     * resolution (it's GraphQL-only), so the fetcher joins it and passes it in
     * here; without it the resolution leg of an inline chain never populates.
     */
    public List<OutcomeChain> reconstruct(PrEvidenceBundle bundle, Set<Long> resolvedRootIds)
    {
        List<OutcomeChain> chains = new ArrayList<>();
        String author = bundle.author();
        boolean merged = bundle.merged();

        // Line-anchored concerns: inline review-thread roots by a reviewer.
        for (PrReviewThreadMessage message : safe(bundle.reviewComments())) {
            if (message.inReplyTo() != null) {
                continue;                       // only thread roots seed a concern
            }
            if (isAuthor(message.author(), author)) {
                continue;                       // the author's own note is not a concern
            }
            String commit = laterAuthorCommit(bundle.commits(), author, message.createdAt());
            boolean resolved = Boolean.TRUE.equals(message.resolved())
                    || resolvedRootIds.contains(message.githubId());
            chains.add(chain(
                    message.author(),
                    message.filePath(),
                    "comment:" + message.githubId(),
                    commit,
                    resolved,
                    merged));
        }

        // Review-level concerns: an explicit CHANGES_REQUESTED verdict.
        for (PrReviewState review : safe(bundle.reviews())) {
            if (!"CHANGES_REQUESTED".equalsIgnoreCase(review.state())) {
                continue;
            }
            if (isAuthor(review.login(), author)) {
                continue;
            }
            String commit = laterAuthorCommit(bundle.commits(), author, review.submittedAt());
            // A dismissed/re-reviewed CHANGES_REQUESTED that later approved is
            // treated as resolved when a follow-up commit exists and the PR
            // merged; the linkage (not the toggle) is what matters here.
            boolean resolved = commit != null && merged;
            chains.add(chain(
                    review.login(),
                    null,
                    "review:" + review.login(),
                    commit,
                    resolved,
                    merged));
        }
        return chains;
    }

    private static OutcomeChain chain(
            String concernAuthor,
            String concernPath,
            String concernRef,
            String addressedByCommit,
            boolean resolved,
            boolean merged)
    {
        boolean addressed = addressedByCommit != null;
        int depth = (addressed ? 1 : 0)
                + (resolved ? 1 : 0)
                + (merged && addressed ? 1 : 0);
        String digest = MergedPrCatalog.sha256(
                concernAuthor + "|" + concernPath + "|" + concernRef + "|"
                        + addressedByCommit + "|" + resolved + "|" + merged);
        return new OutcomeChain(concernAuthor, concernPath, concernRef,
                addressedByCommit, resolved, merged, depth, digest);
    }

    /** SHA of the earliest author commit authored after {@code raisedAt}. */
    private static String laterAuthorCommit(
            List<PullRequestCommit> commits, String author, Instant raisedAt)
    {
        if (commits == null || raisedAt == null) {
            return null;
        }
        String match = null;
        Instant earliest = null;
        for (PullRequestCommit commit : commits) {
            Instant authored = commit.authoredAt();
            if (authored == null || !authored.isAfter(raisedAt)) {
                continue;
            }
            if (!isAuthor(commit.authorLogin(), author) && !isAuthor(commit.authorName(), author)) {
                continue;
            }
            if (earliest == null || authored.isBefore(earliest)) {
                earliest = authored;
                match = commit.sha();
            }
        }
        return match;
    }

    private static boolean isAuthor(String login, String author)
    {
        return login != null && author != null && login.equalsIgnoreCase(author);
    }

    private static <T> List<T> safe(List<T> list)
    {
        return list == null ? List.of() : list;
    }
}
