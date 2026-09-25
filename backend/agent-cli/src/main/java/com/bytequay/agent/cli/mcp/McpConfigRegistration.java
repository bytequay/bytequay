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
package com.bytequay.agent.cli.mcp;

import com.bytequay.agent.spi.CodeGraphBridge;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.RepositoryContextBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** A task token whose MCP configuration and sandbox settings ByteQuay wrote for the provider; closing forgets the token and deletes both files. */
public final class McpConfigRegistration
        implements AutoCloseable
{
    private final Path configFile;
    private final Path settingsFile;
    private final String token;
    private final List<String> toolNames;
    private final Optional<CodeGraphBridge> codeGraph;
    private final Optional<ReviewBridge> review;
    private final Optional<DevelopmentResultBridge> development;
    private final Optional<LocalReviewBridge> localReview;
    private final Optional<PullRequestCiBridge> pullRequestCi;
    private final Optional<IssueContextBridge> issueContext;
    private final Optional<RepositoryContextBridge> notebook;
    private final Runnable release;

    McpConfigRegistration(
            Path configFile,
            Path settingsFile,
            String token,
            List<String> toolNames,
            Optional<CodeGraphBridge> codeGraph,
            Optional<ReviewBridge> review,
            Optional<DevelopmentResultBridge> development,
            Optional<LocalReviewBridge> localReview,
            Optional<PullRequestCiBridge> pullRequestCi,
            Optional<IssueContextBridge> issueContext,
            Optional<RepositoryContextBridge> notebook,
            Runnable release)
    {
        this.configFile = requireNonNull(configFile, "configFile is null");
        this.settingsFile = requireNonNull(settingsFile, "settingsFile is null");
        this.token = requireNonNull(token, "token is null");
        this.toolNames = ImmutableList.copyOf(toolNames);
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.review = requireNonNull(review, "review is null");
        this.development = requireNonNull(development, "development is null");
        this.localReview = requireNonNull(localReview, "localReview is null");
        this.pullRequestCi = requireNonNull(pullRequestCi, "pullRequestCi is null");
        this.issueContext = requireNonNull(issueContext, "issueContext is null");
        this.notebook = requireNonNull(notebook, "notebook is null");
        this.release = requireNonNull(release, "release is null");
    }

    public Optional<DevelopmentResultBridge> development()
    {
        return development;
    }

    public Optional<PullRequestCiBridge> pullRequestCi()
    {
        return pullRequestCi;
    }

    public Optional<LocalReviewBridge> localReview()
    {
        return localReview;
    }

    public Optional<RepositoryContextBridge> notebook()
    {
        return notebook;
    }

    /** The review capability this token exposes, resolved from the task when it registered. */
    public Optional<ReviewBridge> review()
    {
        return review;
    }

    /** The managed guidance this session's instruction channel carries, if any tool needs one. */
    public Optional<String> instruction()
    {
        return PermissionBroker.managedInstruction(codeGraph, review, development, localReview, pullRequestCi, issueContext, notebook);
    }

    public Path configFile()
    {
        return configFile;
    }

    public Path settingsFile()
    {
        return settingsFile;
    }

    public String token()
    {
        return token;
    }

    /** Agent-facing tools exposed by this token, excluding the provider's permission callback. */
    public List<String> toolNames()
    {
        return toolNames;
    }

    @Override
    public void close()
    {
        release.run();
    }
}
