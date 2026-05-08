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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;

/**
 * Clears durable and in-memory PR-detail caches after structural GitHub writes.
 */
@Component
public class PullRequestDetailInvalidator
{
    private final PullRequestStore store;
    private final PrDetailStore detailStore;
    private final GitHubResponseCache responseCache;

    public PullRequestDetailInvalidator(
            PullRequestStore store,
            PrDetailStore detailStore,
            GitHubResponseCache responseCache)
    {
        this.store = requireNonNull(store, "store is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.responseCache = requireNonNull(responseCache, "responseCache is null");
    }

    /**
     * Invalidates the SQLite detail snapshot and PR-scoped response caches.
     */
    public void invalidate(String repo, int number)
    {
        PullRequestRef ref = parseRef(repo, number);
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(id -> detailStore.deleteByPrIds(ImmutableSet.of(id)));
        responseCache.invalidatePullRequest(ref);
    }
}
