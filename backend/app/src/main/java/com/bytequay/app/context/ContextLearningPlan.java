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
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One user-accepted set of bounded learning questions, independent of the latest task's lifecycle. */
public record ContextLearningPlan(
        String id, String workspaceId, String reportId, String acceptedRequestKey, String acceptedRequestDigest,
        long acceptedNotebookRevision, long initialBuildSequence, List<String> acceptedTopicIds,
        long repositoryId, RepoRef repository, String baselineSha, List<Topic> topics,
        List<RegisteredContextSource> documentationSources, ContextDocumentInventory documentInventory,
        List<String> gaps, Instant acceptedAt)
{
    public ContextLearningPlan
    {
        ContextLimits.text(id, "planId", 100);
        ContextLimits.text(workspaceId, "workspaceId", 200);
        ContextLimits.text(reportId, "reportId", 100);
        ContextLimits.text(acceptedRequestKey, "accepted request key", 100);
        checkArgument(acceptedRequestDigest.matches("sha256:[0-9a-f]{64}"), "Invalid accepted request digest");
        checkArgument(acceptedNotebookRevision >= 0 && initialBuildSequence >= 0, "Invalid accepted revision");
        acceptedTopicIds = ImmutableList.copyOf(acceptedTopicIds);
        ContextLimits.unique(acceptedTopicIds, "accepted topic IDs", 20);
        checkArgument(!acceptedTopicIds.isEmpty(), "Select at least one learning topic");
        checkArgument(repositoryId > 0, "Invalid learning repository ID");
        requireNonNull(repository, "repository is null");
        checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid learning baseline");
        topics = ImmutableList.copyOf(topics);
        ContextLimits.unique(topics.stream().map(Topic::id).toList(), "plan topics", 20);
        checkArgument(topics.stream().map(Topic::id).toList().equals(acceptedTopicIds), "Plan topics differ from the accepted selection");
        documentationSources = ImmutableList.copyOf(documentationSources);
        ContextLimits.unique(documentationSources.stream().map(RegisteredContextSource::id).toList(), "plan documentation", 20);
        requireNonNull(documentInventory, "documentInventory is null");
        checkArgument(documentInventory.sources().equals(documentationSources), "Documentation inventory differs from approved source snapshots");
        List<String> approvedSources = documentationSources.stream().map(RegisteredContextSource::id).toList();
        topics.forEach(topic -> checkArgument(approvedSources.containsAll(topic.sourceIds()), "Topic uses an unapproved documentation source"));
        gaps = ImmutableList.copyOf(gaps);
        checkArgument(gaps.size() <= 40, "Too many learning gaps");
        gaps.forEach(gap -> ContextLimits.text(gap, "learning gap", 1000));
        requireNonNull(acceptedAt, "acceptedAt is null");
    }

    public ContextLearningPlan withInventory(ContextDocumentInventory updated)
    {
        return new ContextLearningPlan(id, workspaceId, reportId, acceptedRequestKey, acceptedRequestDigest,
                acceptedNotebookRevision, initialBuildSequence, acceptedTopicIds, repositoryId, repository, baselineSha,
                topics, documentationSources, updated, gaps, acceptedAt);
    }

    public ContextLearningPlan withProgress(List<Topic> updated, List<String> updatedGaps)
    {
        return new ContextLearningPlan(id, workspaceId, reportId, acceptedRequestKey, acceptedRequestDigest,
                acceptedNotebookRevision, initialBuildSequence, acceptedTopicIds, repositoryId, repository, baselineSha,
                updated, documentationSources, documentInventory, updatedGaps, acceptedAt);
    }

    public enum Status
    {
        PENDING,
        COMPLETED
    }

    public record Topic(String id, String title, String question, String scopeId, String scopeMatchDigest, List<String> paths, List<String> repositoryFiles,
            List<String> sourceIds, Status status, String remainingQuestion)
    {
        public Topic
        {
            ContextLimits.text(id, "topic id", 100);
            ContextLimits.text(title, "topic title", 200);
            ContextLimits.text(question, "topic question", 1000);
            ContextLimits.text(scopeId, "topic scope", 1100);
            checkArgument(scopeMatchDigest.matches("sha256:[0-9a-f]{64}"), "Invalid accepted scope match");
            paths = ImmutableList.copyOf(paths);
            repositoryFiles = ImmutableList.copyOf(repositoryFiles);
            sourceIds = ImmutableList.copyOf(sourceIds);
            ContextLimits.unique(paths, "topic paths", 8);
            paths.forEach(ContextLimits::pathPrefix);
            ContextLimits.unique(repositoryFiles, "topic source files", 40);
            repositoryFiles.forEach(path -> ContextLimits.pathPrefix(path + "/"));
            ContextLimits.unique(sourceIds, "topic documentation sources", 20);
            requireNonNull(status, "topic status is null");
            ContextLimits.text(remainingQuestion, "remaining learning question", 2000);
        }

        public Topic progress(String remaining, boolean completed)
        {
            return new Topic(id, title, question, scopeId, scopeMatchDigest, paths, repositoryFiles, sourceIds,
                    completed ? Status.COMPLETED : Status.PENDING, remaining);
        }
    }
}
