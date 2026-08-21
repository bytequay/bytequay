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
 * <p>A workspace always clones the selected repository as {@code origin}. A fork has exactly one
 * canonical {@code upstream}; that repository is also the monitored repository used for pull
 * requests, issues, and reference metadata.</p>
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
        Optional<String> upstreamCloneUrl,
        String localRepositoryPath,
        CodeState codeState,
        MetadataState metadataState,
        Optional<Instant> lastCodeSyncAt,
        Optional<Instant> lastMetadataSyncAt,
        Optional<String> codeError,
        Optional<String> metadataError,
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
        requireNonNull(upstreamCloneUrl, "upstreamCloneUrl is null");
        checkText(localRepositoryPath, "localRepositoryPath");
        requireNonNull(codeState, "codeState is null");
        requireNonNull(metadataState, "metadataState is null");
        requireNonNull(lastCodeSyncAt, "lastCodeSyncAt is null");
        requireNonNull(lastMetadataSyncAt, "lastMetadataSyncAt is null");
        requireNonNull(codeError, "codeError is null");
        requireNonNull(metadataError, "metadataError is null");
        checkArgument(displayOrder >= 0, "displayOrder is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");

        if (topology == Topology.DIRECT) {
            checkArgument(upstreamRepositoryId.isEmpty(), "direct workspace has an upstream ID");
            checkArgument(upstreamRepository.isEmpty(), "direct workspace has an upstream repository");
            checkArgument(upstreamCloneUrl.isEmpty(), "direct workspace has an upstream clone URL");
            checkArgument(monitoredRepositoryId == selectedRepositoryId,
                    "direct workspace does not monitor the selected repository");
            checkArgument(monitoredRepository.equals(selectedRepository),
                    "direct workspace monitored coordinates differ from selected coordinates");
        }
        else {
            checkArgument(upstreamRepositoryId.isPresent(), "fork workspace has no upstream ID");
            checkArgument(upstreamRepository.isPresent(), "fork workspace has no upstream repository");
            checkArgument(upstreamCloneUrl.isPresent(), "fork workspace has no upstream clone URL");
            checkArgument(monitoredRepositoryId == upstreamRepositoryId.orElseThrow(),
                    "fork workspace does not monitor its upstream ID");
            checkArgument(monitoredRepository.equals(upstreamRepository.orElseThrow()),
                    "fork workspace monitored coordinates differ from upstream coordinates");
        }
    }

    public Workspace withStates(CodeState nextCodeState, MetadataState nextMetadataState, Instant now)
    {
        return new Workspace(
                id, name, selectedRepositoryId, selectedRepository, upstreamRepositoryId,
                upstreamRepository, monitoredRepositoryId, monitoredRepository, topology,
                defaultBranch, originCloneUrl, upstreamCloneUrl, localRepositoryPath,
                nextCodeState, nextMetadataState, lastCodeSyncAt, lastMetadataSyncAt,
                Optional.empty(), Optional.empty(), displayOrder, createdAt, now);
    }

    public Workspace withCodeState(
            CodeState nextCodeState,
            Optional<Instant> nextLastCodeSyncAt,
            Optional<String> nextCodeError,
            Instant now)
    {
        return new Workspace(
                id, name, selectedRepositoryId, selectedRepository, upstreamRepositoryId,
                upstreamRepository, monitoredRepositoryId, monitoredRepository, topology,
                defaultBranch, originCloneUrl, upstreamCloneUrl, localRepositoryPath,
                nextCodeState, metadataState, nextLastCodeSyncAt, lastMetadataSyncAt,
                nextCodeError, metadataError, displayOrder, createdAt, now);
    }

    public Workspace withRepositoryRouting(
            RepoRef nextSelectedRepository,
            Optional<RepoRef> nextUpstreamRepository,
            RepoRef nextMonitoredRepository,
            String nextDefaultBranch,
            String nextOriginCloneUrl,
            Optional<String> nextUpstreamCloneUrl,
            Instant now)
    {
        return new Workspace(
                id, name, selectedRepositoryId, nextSelectedRepository, upstreamRepositoryId,
                nextUpstreamRepository, monitoredRepositoryId, nextMonitoredRepository, topology,
                nextDefaultBranch, nextOriginCloneUrl, nextUpstreamCloneUrl, localRepositoryPath,
                codeState, metadataState, lastCodeSyncAt, lastMetadataSyncAt,
                codeError, metadataError, displayOrder, createdAt, now);
    }

    public enum Topology
    {
        DIRECT,
        FORK
    }

    public enum CodeState
    {
        PENDING,
        CLONING,
        READY,
        SYNCING,
        FAILED
    }

    public enum MetadataState
    {
        PENDING,
        SYNCING,
        READY,
        FAILED
    }

    private static void checkText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
    }
}
