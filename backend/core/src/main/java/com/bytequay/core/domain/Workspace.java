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
package com.bytequay.core.domain;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Durable identity and routing for one watched GitHub repository.
 *
 * <p>A workspace always clones the selected repository as {@code origin}. An optional user-selected
 * GitHub repository supplies the read-only {@code upstream}; that repository is also monitored for
 * pull requests, issues, and reference metadata. GitHub fork topology is only descriptive and never
 * establishes this link.</p>
 */
public record Workspace(
        String id,
        String name,
        long selectedRepositoryId,
        RepoRef selectedRepository,
        Optional<Long> upstreamRepositoryId,
        Optional<RepoRef> upstreamRepository,
        long monitoredRepositoryId,
        RepoRef monitoredRepository,
        Topology topology,
        String defaultBranch,
        String originCloneUrl,
        Optional<String> upstreamDefaultBranch,
        Optional<String> upstreamCloneUrl,
        String localRepositoryPath,
        Optional<Instant> lastCodeSyncAt,
        Optional<Instant> lastMetadataSyncAt,
        int displayOrder,
        Instant createdAt,
        Instant updatedAt)
{
    public Workspace
    {
        checkText(id, "id");
        checkText(name, "name");
        checkArgument(selectedRepositoryId > 0, "selectedRepositoryId must be positive");
        requireNonNull(selectedRepository, "selectedRepository is null");
        requireNonNull(upstreamRepositoryId, "upstreamRepositoryId is null");
        requireNonNull(upstreamRepository, "upstreamRepository is null");
        checkArgument(monitoredRepositoryId > 0, "monitoredRepositoryId must be positive");
        requireNonNull(monitoredRepository, "monitoredRepository is null");
        requireNonNull(topology, "topology is null");
        checkText(defaultBranch, "defaultBranch");
        checkText(originCloneUrl, "originCloneUrl");
        requireNonNull(upstreamDefaultBranch, "upstreamDefaultBranch is null");
        requireNonNull(upstreamCloneUrl, "upstreamCloneUrl is null");
        checkText(localRepositoryPath, "localRepositoryPath");
        requireNonNull(lastCodeSyncAt, "lastCodeSyncAt is null");
        requireNonNull(lastMetadataSyncAt, "lastMetadataSyncAt is null");
        checkArgument(displayOrder >= 0, "displayOrder is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        upstreamDefaultBranch.ifPresent(value -> checkText(value, "upstreamDefaultBranch"));
        upstreamCloneUrl.ifPresent(value -> checkText(value, "upstreamCloneUrl"));

        if (upstreamRepositoryId.isEmpty()) {
            checkArgument(upstreamRepository.isEmpty(), "unlinked workspace has an upstream repository");
            checkArgument(upstreamDefaultBranch.isEmpty(), "unlinked workspace has an upstream default branch");
            checkArgument(upstreamCloneUrl.isEmpty(), "unlinked workspace has an upstream clone URL");
            checkArgument(monitoredRepositoryId == selectedRepositoryId, "unlinked workspace does not monitor itself");
            checkArgument(monitoredRepository.equals(selectedRepository),
                    "unlinked workspace monitored coordinates differ from selected coordinates");
        }
        else {
            checkArgument(upstreamRepositoryId.orElseThrow() > 0, "linked workspace upstream ID is not positive");
            checkArgument(upstreamRepositoryId.orElseThrow() != selectedRepositoryId, "workspace uses itself as upstream");
            checkArgument(upstreamRepository.isPresent(), "linked workspace has no upstream repository");
            checkArgument(upstreamDefaultBranch.isPresent(), "linked workspace has no upstream default branch");
            checkArgument(upstreamCloneUrl.isPresent(), "linked workspace has no upstream clone URL");
            checkArgument(monitoredRepositoryId == upstreamRepositoryId.orElseThrow(),
                    "linked workspace does not monitor its upstream ID");
            checkArgument(monitoredRepository.equals(upstreamRepository.orElseThrow()),
                    "linked workspace monitored coordinates differ from upstream coordinates");
        }
    }

    public Workspace withName(String nextName, Instant now)
    {
        return new Workspace(
                id, nextName, selectedRepositoryId, selectedRepository, upstreamRepositoryId,
                upstreamRepository, monitoredRepositoryId, monitoredRepository, topology,
                defaultBranch, originCloneUrl, upstreamDefaultBranch, upstreamCloneUrl, localRepositoryPath,
                lastCodeSyncAt, lastMetadataSyncAt, displayOrder, createdAt, now);
    }

    public Workspace withCodeSyncAt(
            Optional<Instant> nextLastCodeSyncAt,
            Instant now)
    {
        return new Workspace(
                id, name, selectedRepositoryId, selectedRepository, upstreamRepositoryId,
                upstreamRepository, monitoredRepositoryId, monitoredRepository, topology,
                defaultBranch, originCloneUrl, upstreamDefaultBranch, upstreamCloneUrl, localRepositoryPath,
                nextLastCodeSyncAt, lastMetadataSyncAt, displayOrder, createdAt, now);
    }

    public Workspace withMetadataSyncAt(
            Optional<Instant> nextLastMetadataSyncAt,
            Instant now)
    {
        return new Workspace(
                id, name, selectedRepositoryId, selectedRepository, upstreamRepositoryId,
                upstreamRepository, monitoredRepositoryId, monitoredRepository, topology,
                defaultBranch, originCloneUrl, upstreamDefaultBranch, upstreamCloneUrl, localRepositoryPath,
                lastCodeSyncAt, nextLastMetadataSyncAt, displayOrder, createdAt, now);
    }

    public Workspace withRepositoryRouting(
            RepoRef nextSelectedRepository,
            Optional<Long> nextUpstreamRepositoryId,
            Optional<RepoRef> nextUpstreamRepository,
            long nextMonitoredRepositoryId,
            RepoRef nextMonitoredRepository,
            String nextDefaultBranch,
            String nextOriginCloneUrl,
            Optional<String> nextUpstreamDefaultBranch,
            Optional<String> nextUpstreamCloneUrl,
            Instant now)
    {
        return new Workspace(
                id, name, selectedRepositoryId, nextSelectedRepository, nextUpstreamRepositoryId,
                nextUpstreamRepository, nextMonitoredRepositoryId,
                nextMonitoredRepository, topology, nextDefaultBranch, nextOriginCloneUrl,
                nextUpstreamDefaultBranch, nextUpstreamCloneUrl, localRepositoryPath,
                lastCodeSyncAt, lastMetadataSyncAt, displayOrder, createdAt, now);
    }

    public enum Topology
    {
        DIRECT,
        FORK
    }

    private static void checkText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
    }
}
