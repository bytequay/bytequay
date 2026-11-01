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
package com.bytequay.core.hosting;

import com.bytequay.core.domain.PullRequestRef;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Requests across everything an account can see, beyond any one watched repository. */
public interface PullRequestDiscovery
{
    /** One-based pages, most recently updated first. */
    PullRequestListPage find(HostingAccess access, DiscoveryQuery query, int page, int pageSize);

    /** Delivers what the account's activity feed reported since the last poll; the provider keeps its own checkpoint. */
    void poll(HostingAccess access, String login, Consumer<ActivityNotice> notices);

    /** The review requests the last poll reported, read without a remote call. */
    Set<PullRequestRef> reviewRequests(String login);

    /** The mentions the last poll reported, read without a remote call. */
    Set<PullRequestRef> mentions(String login);

    /** Card facts for at most {@link #summaryBatchSize()} requests. */
    List<PullRequestCardSummary> summaries(HostingAccess access, List<PullRequestRef> refs, String login);

    int summaryBatchSize();

    /** Whether the author answered the viewer after {@code since}, at the activity version the summary reported. */
    boolean hasAuthorReplyAfter(HostingAccess access, PullRequestRef ref, String viewer, String author, Instant since, String activityVersion);
}
