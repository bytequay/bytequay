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

import com.bytequay.app.repository.sqlite.SqlitePrDetailStore;
import com.bytequay.app.repository.sqlite.SqlitePullRequestStore;
import org.springframework.stereotype.Component;

import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Clears the cached PR detail after a GitHub write.
 */
@Component
public class PullRequestDetailInvalidator
{
    private final SqlitePullRequestStore store;
    private final SqlitePrDetailStore detailStore;

    public PullRequestDetailInvalidator(
            SqlitePullRequestStore store,
            SqlitePrDetailStore detailStore)
    {
        this.store = requireNonNull(store, "store is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
    }

    /**
     * Invalidates the SQLite detail snapshot.
     */
    public void invalidate(String repo, int number)
    {
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(id -> detailStore.deleteByPrIds(Set.of(id)));
    }
}
