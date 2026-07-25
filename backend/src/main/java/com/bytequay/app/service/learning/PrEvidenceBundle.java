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
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A snapshot-pinned, completeness-marked evidence bundle for one merged PR —
 * the deterministic Phase 2 artifact that Phase 3 extraction will later read.
 * Holds the fetched review/file/commit/timeline/inline-thread evidence, the
 * reconstructed outcome chains, and the stable refs, all pinned to a fixed
 * repository snapshot ({@code baseSha}/{@code headSha}/{@code mergeSha} plus
 * the default-branch {@code repoSha} the "current code" mapping ran against).
 *
 * <p>No bulk diff lives here: the diff is fetched during analysis, mapped to
 * files/symbols, and discarded — only span coordinates and content digests
 * survive as {@link EvidenceRef}s. Never written into the dashboard
 * PrDetailStore cache.
 *
 * @param completeness per-source markers (keys: reviews, files, commits,
 * comments, timeline), each {@code complete} or {@code partial:<source>} or
 * {@code unavailable}.
 * @param overallCompleteness the single roll-up marker: {@code complete}, or
 * {@code partial:<source>} naming the first incomplete source, or
 * {@code unavailable} when the PR detail itself could not be read.
 */
public record PrEvidenceBundle(
        String workspaceId,
        String repo,
        int prNumber,
        String author,
        String title,
        String bodyText,
        String baseSha,
        String headSha,
        String mergeSha,
        String repoSha,
        List<PrReviewState> reviews,
        List<PullRequestDetail.ChangedFile> files,
        List<PullRequestCommit> commits,
        List<PrReviewThreadMessage> reviewComments,
        List<PrTimelineEvent> timeline,
        Map<String, String> completeness,
        String overallCompleteness,
        List<EvidenceRef> refs,
        List<OutcomeChain> chains)
{
    /** True when the PR landed (has a merge commit SHA). */
    public boolean merged()
    {
        return mergeSha != null && !mergeSha.isBlank();
    }

    /**
     * The set of commit SHAs a stable ref may legitimately be pinned to: the
     * base/head/merge SHAs, the pinned default-branch snapshot, and every
     * commit the PR itself contributed. A ref carrying any other SHA would
     * cross the pinned repository snapshot and is rejected at persist time.
     */
    public Set<String> pinnedShas()
    {
        Set<String> shas = new LinkedHashSet<>();
        addIfPresent(shas, baseSha);
        addIfPresent(shas, headSha);
        addIfPresent(shas, mergeSha);
        addIfPresent(shas, repoSha);
        if (commits != null) {
            commits.forEach(c -> addIfPresent(shas, c.sha()));
        }
        return shas;
    }

    private static void addIfPresent(Set<String> shas, String sha)
    {
        if (sha != null && !sha.isBlank()) {
            shas.add(sha);
        }
    }

    /**
     * One stable reference into a bundle: a GitHub id, URL, pinned commit SHA,
     * and/or file span with a content digest — never a bulk diff. {@code kind}
     * is one of review | thread | commit | file | symbol | test | timeline.
     */
    public record EvidenceRef(
            String kind,
            String githubId,
            String url,
            String commitSha,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String contentDigest) {}
}
