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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One agent-proposed, evidenced discovery result. It is not active repository knowledge. */
public record ContextDiscoveryReport(
        String id, String workspaceId, long repositoryId, RepoRef repository, String baselineSha, String outline,
        List<Topic> topics, List<Source> sources, List<Evidence> evidence, List<String> gaps, List<String> remaining,
        boolean partial, Instant createdAt)
{
    public ContextDiscoveryReport
    {
        ContextLimits.text(id, "discovery id", 100);
        ContextLimits.text(workspaceId, "workspaceId", 200);
        checkArgument(repositoryId > 0, "Invalid discovery repository ID");
        requireNonNull(repository, "repository is null");
        checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid discovery baseline");
        ContextLimits.text(outline, "project outline", 4096);
        topics = ImmutableList.copyOf(topics);
        sources = ImmutableList.copyOf(sources);
        evidence = ImmutableList.copyOf(evidence);
        gaps = ImmutableList.copyOf(gaps);
        remaining = ImmutableList.copyOf(remaining);
        ContextLimits.unique(topics.stream().map(Topic::id).toList(), "discovery topics", 20);
        ContextLimits.unique(sources.stream().map(Source::id).toList(), "discovery sources", 20);
        ContextLimits.unique(evidence.stream().map(Evidence::readId).toList(), "discovery evidence", 40);
        checkArgument(gaps.size() <= 40 && remaining.size() <= 20, "Too many discovery limitations");
        gaps.forEach(value -> ContextLimits.text(value, "discovery gap", 1000));
        remaining.forEach(value -> ContextLimits.text(value, "remaining work", 1000));
        checkArgument(remaining.isEmpty() || partial, "Remaining discovery work requires a partial report");
        List<String> sourceIds = sources.stream().map(Source::id).toList();
        List<String> readIds = evidence.stream().map(Evidence::readId).toList();
        topics.forEach(topic -> {
            checkArgument(sourceIds.containsAll(topic.sourceIds()), "Topic references an unknown proposed source");
            checkArgument(readIds.containsAll(topic.evidenceReadIds()), "Topic references missing source evidence");
        });
        sources.forEach(source -> checkArgument(readIds.containsAll(source.evidenceReadIds()), "Proposed source references missing evidence"));
        requireNonNull(createdAt, "createdAt is null");
    }

    public record Topic(String id, String title, String question, List<String> paths, List<String> sourceIds, List<String> evidenceReadIds, String why)
    {
        public Topic
        {
            checkArgument(id.matches("topic:[1-9][0-9]*"), "Invalid topic ID");
            ContextLimits.text(title, "topic title", 200);
            ContextLimits.text(question, "learning question", 1000);
            ContextLimits.text(why, "topic rationale", 1000);
            paths = ImmutableList.copyOf(paths);
            sourceIds = ImmutableList.copyOf(sourceIds);
            evidenceReadIds = ImmutableList.copyOf(evidenceReadIds);
            ContextLimits.unique(paths, "topic paths", 8);
            paths.forEach(path -> {
                checkArgument(path.endsWith("/"), "Topic paths must be directory prefixes ending in /");
                ContextLimits.pathPrefix(path);
            });
            ContextLimits.unique(sourceIds, "topic source IDs", 8);
            ContextLimits.unique(evidenceReadIds, "topic read IDs", 8);
            checkArgument(!evidenceReadIds.isEmpty(), "A suggested topic needs repository evidence");
        }
    }

    public record Source(String id, Kind kind, String location, String title, List<String> evidenceReadIds, String why)
    {
        public Source
        {
            checkArgument(id.matches("source:[1-9][0-9]*"), "Invalid proposed source ID");
            requireNonNull(kind, "source kind is null");
            ContextLimits.text(title, "proposed source title", 200);
            ContextLimits.text(why, "proposed source rationale", 1000);
            if (kind == Kind.REPOSITORY_FILE) {
                ContextLimits.pathPrefix(location + "/");
            }
            else {
                ContextLimits.documentationUrl(location);
                checkArgument(kind != Kind.DOCUMENTATION_ROOT || location.endsWith("/"), "Documentation root must end in /");
            }
            evidenceReadIds = ImmutableList.copyOf(evidenceReadIds);
            ContextLimits.unique(evidenceReadIds, "source read IDs", 8);
            checkArgument(!evidenceReadIds.isEmpty(), "A proposed source needs repository evidence");
        }

        public enum Kind
        {
            REPOSITORY_FILE,
            DOCUMENTATION_ROOT,
            DOCUMENTATION_PAGE
        }
    }

    public record Evidence(String readId, String path, String blobId, int startLine, int endLine, String excerpt, boolean truncated)
    {
        public Evidence
        {
            ContextLimits.text(readId, "evidence read ID", 100);
            ContextLimits.pathPrefix(path + "/");
            checkArgument(blobId.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid evidence blob");
            checkArgument(startLine > 0 && endLine >= startLine, "Invalid evidence line range");
            ContextLimits.text(excerpt, "source excerpt", 4096);
        }
    }
}
