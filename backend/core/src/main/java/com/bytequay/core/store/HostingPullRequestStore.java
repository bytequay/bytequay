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
package com.bytequay.core.store;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;

import java.time.Instant;
import java.util.Optional;

public interface HostingPullRequestStore
{
    /** The caller must verify the credential scope before reusing metadata or its validator. */
    Optional<HostingPullRequestSnapshot> find(long accountId, long repositoryId, int number);

    HostingPullRequestSnapshot save(long accountId, long repositoryId, String credentialScope, FetchedPullRequest pullRequest, PrRawDetail detail,
            Optional<String> etag, Instant validatedAt);

    /** Removes only a matching account and credential scope's snapshot, retaining request identity and local work. */
    void evict(long accountId, long repositoryId, int number, String credentialScope);

    /** Invalidates every account's snapshot after a local action without deleting retained request identity. */
    void evictRepository(long repositoryId, int number);
}
