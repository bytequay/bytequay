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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.repository.GitHubPullRequestReadRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;

/**
 * Per-pass cache of the reviewed PR's unified diff, so the seat / lead
 * read tools ({@code get_pr_diff}, {@code search_code}) and the seat
 * context assembly never re-fetch from GitHub mid-pass. Seeded once
 * at kickoff (the pass body already fetched the diff); falls back to
 * a fresh fetch for passes resumed without a seed (e.g. arbitration
 * reads after a backend restart). Entries are dropped when the pass
 * run finishes — this is working state, not a persistence layer.
 */
@Component
public class ReviewDiffCache
{
    private final ConcurrentHashMap<String, String> diffByPassId = new ConcurrentHashMap<>();
    private final GitHubPullRequestReadRepository pullRequests;
    private final PatResolver patResolver;

    public ReviewDiffCache(GitHubPullRequestReadRepository pullRequests, PatResolver patResolver)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
    }

    public void seed(String passId, String diff)
    {
        if (diff != null) {
            diffByPassId.put(passId, diff);
        }
    }

    /** The pass's unified diff — seeded copy when present, otherwise
     *  fetched once and cached. Never null; empty string when the
     *  fetch returns nothing. */
    public String diffFor(ReviewPass pass)
    {
        return diffByPassId.computeIfAbsent(pass.id(), id -> {
            String pat = patResolver.resolve(pass.repoFullName());
            String fetched = pullRequests.fetchPrDiff(
                    pat, parseRef(pass.repoFullName(), pass.prNumber()));
            return fetched == null ? "" : fetched;
        });
    }

    public void drop(String passId)
    {
        diffByPassId.remove(passId);
    }
}
