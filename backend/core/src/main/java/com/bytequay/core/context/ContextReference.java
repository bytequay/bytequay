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
package com.bytequay.core.context;

import com.bytequay.core.domain.RepoRef;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Closed source identities; repository and code coordinates never come from display titles. */
public sealed interface ContextReference
        permits ContextReference.RepositoryFile, ContextReference.PullRequest, ContextReference.Issue,
                ContextReference.Task, ContextReference.Review, ContextReference.Document
{
    String getKind();

    record RepositoryFile(long repositoryId, RepoRef repository, String path, String commit)
            implements ContextReference
    {
        public RepositoryFile
        {
            checkArgument(repositoryId > 0, "repositoryId must be positive");
            requireNonNull(repository, "repository is null");
            ContextLimits.text(path, "path", 1000);
            ContextLimits.pathPrefix(path + "/");
            checkSha(commit, "commit");
        }

        @Override
        public String getKind()
        {
            return "REPOSITORY_FILE";
        }
    }

    record PullRequest(long repositoryId, RepoRef repository, int number, Optional<String> commentId)
            implements ContextReference
    {
        public PullRequest
        {
            checkArgument(repositoryId > 0 && number > 0, "Invalid pull request identity");
            requireNonNull(repository, "repository is null");
            checkOptionalText(commentId, "commentId", 200);
        }

        @Override
        public String getKind()
        {
            return "PR";
        }
    }

    record Issue(long repositoryId, RepoRef repository, int number, Optional<String> commentId)
            implements ContextReference
    {
        public Issue
        {
            checkArgument(repositoryId > 0 && number > 0, "Invalid issue identity");
            requireNonNull(repository, "repository is null");
            checkOptionalText(commentId, "commentId", 200);
        }

        @Override
        public String getKind()
        {
            return "ISSUE";
        }
    }

    record Task(String workspaceId, String taskId, Optional<String> turnId)
            implements ContextReference
    {
        public Task
        {
            ContextLimits.text(workspaceId, "workspaceId", 200);
            ContextLimits.text(taskId, "taskId", 200);
            checkOptionalText(turnId, "turnId", 200);
        }

        @Override
        public String getKind()
        {
            return "TASK";
        }
    }

    record Review(String reviewId, String runId, Optional<String> evidenceRef, Optional<String> messageId)
            implements ContextReference
    {
        public Review
        {
            ContextLimits.text(reviewId, "reviewId", 200);
            ContextLimits.text(runId, "runId", 200);
            checkOptionalText(evidenceRef, "evidenceRef", 200);
            checkOptionalText(messageId, "messageId", 200);
            checkArgument(evidenceRef.isPresent() != messageId.isPresent(), "Review source must identify one evidence item or message");
        }

        @Override
        public String getKind()
        {
            return "REVIEW";
        }
    }

    record Document(String sourceId, String url)
            implements ContextReference
    {
        public Document
        {
            ContextLimits.text(sourceId, "sourceId", 200);
            ContextLimits.documentationUrl(url);
        }

        @Override
        public String getKind()
        {
            return "DOCUMENT";
        }
    }
}
