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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One provider's implementations of every remote hosting port, for the servers of that provider from {@code since} on.
 *
 * @param since the oldest server release these implementations need; empty serves any release, including one not yet known
 * @param capabilities what ByteQuay supports through these implementations beyond the shared ports
 */
public record HostingIntegration(
        HostingProvider provider,
        Optional<HostingVersion> since,
        HostingCapabilities capabilities,
        HostingAccounts accounts,
        HostingRepositories repositories,
        PullRequestReader pullRequests,
        PullRequestContentReader contents,
        PullRequestWriter writer,
        IssueClient issues,
        RepositoryMetadataReader metadata,
        PullRequestDiscovery discovery,
        PullRequestCi ci,
        TeamActivityReader team)
{
    public HostingIntegration
    {
        requireNonNull(provider, "provider is null");
        requireNonNull(since, "since is null");
        requireNonNull(capabilities, "capabilities is null");
        requireNonNull(accounts, "accounts is null");
        requireNonNull(repositories, "repositories is null");
        requireNonNull(pullRequests, "pullRequests is null");
        requireNonNull(contents, "contents is null");
        requireNonNull(writer, "writer is null");
        requireNonNull(issues, "issues is null");
        requireNonNull(metadata, "metadata is null");
        requireNonNull(discovery, "discovery is null");
        requireNonNull(ci, "ci is null");
        requireNonNull(team, "team is null");
    }
}
