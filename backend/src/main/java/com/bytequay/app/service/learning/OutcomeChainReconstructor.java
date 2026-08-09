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
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

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
 * <p>The current commit evidence has no per-commit changed paths, so a later
 * author commit cannot prove that it addressed a particular concern. Explicit
 * thread resolution and a later approval by the same reviewer are retained,
 * while addressed-by-commit stays unknown until the bundle can substantiate
 * that linkage.
 */
@Component
public class OutcomeChainReconstructor
{
    public List<OutcomeChain> reconstruct(PrEvidenceBundle bundle)
    {
        return reconstruct(bundle, ImmutableSet.of());
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
            boolean resolved = Boolean.TRUE.equals(message.resolved())
                    || resolvedRootIds.contains(message.githubId());
            chains.add(chain(
                    message.author(),
                    message.filePath(),
                    "comment:" + message.githubId(),
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
            boolean resolved = laterApproved(bundle.reviews(), review);
            chains.add(chain(
                    review.login(),
                    null,
                    "review:" + review.login(),
                    resolved,
                    merged));
        }
        return chains;
    }

    private static OutcomeChain chain(
            String concernAuthor,
            String concernPath,
            String concernRef,
            boolean resolved,
            boolean merged)
    {
        // ponytail: commits do not carry changed paths; keep addressed unknown
        // until the evidence bundle can prove concern-to-change linkage.
        int depth = resolved ? 1 : 0;
        String digest = MergedPrCatalog.sha256(
                concernAuthor + "|" + concernPath + "|" + concernRef + "|"
                        + resolved + "|" + merged);
        return new OutcomeChain(concernAuthor, concernPath, concernRef,
                null, resolved, merged, depth, digest);
    }

    private static boolean laterApproved(List<PrReviewState> reviews, PrReviewState concern)
    {
        if (concern.submittedAt() == null) {
            return false;
        }
        return safe(reviews).stream()
                .anyMatch(review -> "APPROVED".equalsIgnoreCase(review.state())
                        && isAuthor(review.login(), concern.login())
                        && review.submittedAt() != null
                        && review.submittedAt().isAfter(concern.submittedAt()));
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
