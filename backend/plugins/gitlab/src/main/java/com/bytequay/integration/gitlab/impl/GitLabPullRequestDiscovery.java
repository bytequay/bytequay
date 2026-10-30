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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.hosting.ActivityNotice;
import com.bytequay.core.hosting.DiscoveryQuery;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.PullRequestCardSummary;
import com.bytequay.core.hosting.PullRequestDiscovery;
import com.bytequay.core.hosting.PullRequestListPage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** GitLab accounts are discovered through their workspaces only, so account-wide discovery finds nothing yet. */
public final class GitLabPullRequestDiscovery
        implements PullRequestDiscovery
{
    @Override
    public PullRequestListPage find(HostingAccess access, DiscoveryQuery query, int page, int pageSize)
    {
        return new PullRequestListPage(ImmutableList.of(), false);
    }

    @Override
    public void poll(HostingAccess access, String login, Consumer<ActivityNotice> notices) {}

    @Override
    public Set<PullRequestRef> reviewRequests(String login)
    {
        return ImmutableSet.of();
    }

    @Override
    public Set<PullRequestRef> mentions(String login)
    {
        return ImmutableSet.of();
    }

    @Override
    public List<PullRequestCardSummary> summaries(HostingAccess access, List<PullRequestRef> refs, String login)
    {
        return ImmutableList.of();
    }

    @Override
    public int summaryBatchSize()
    {
        return 1;
    }

    @Override
    public boolean hasAuthorReplyAfter(HostingAccess access, PullRequestRef ref, String viewer, String author, Instant since, String activityVersion)
    {
        return false;
    }
}
