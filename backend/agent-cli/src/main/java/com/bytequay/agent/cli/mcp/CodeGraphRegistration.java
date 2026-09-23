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
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.google.common.collect.ImmutableList;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** A task-local tool token and the endpoint it authenticates against; closing forgets the token. */
public final class CodeGraphRegistration
        implements AutoCloseable
{
    private final String token;
    private final URI endpoint;
    private final Optional<CodeGraphBridge> codeGraph;
    private final Optional<ReviewBridge> review;
    private final Optional<DevelopmentResultBridge> development;
    private final Optional<LocalReviewBridge> localReview;
    private final Optional<PullRequestCiBridge> pullRequestCi;
    private final List<String> toolNames;
    private final Runnable release;

    CodeGraphRegistration(
            String token, URI endpoint, Optional<CodeGraphBridge> codeGraph, Optional<ReviewBridge> review,
            Optional<DevelopmentResultBridge> development, Optional<LocalReviewBridge> localReview, Optional<PullRequestCiBridge> pullRequestCi,
            List<String> toolNames, Runnable release)
    {
        this.token = requireNonNull(token, "token is null");
        this.endpoint = requireNonNull(endpoint, "endpoint is null");
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.review = requireNonNull(review, "review is null");
        this.development = requireNonNull(development, "development is null");
        this.localReview = requireNonNull(localReview, "localReview is null");
        this.pullRequestCi = requireNonNull(pullRequestCi, "pullRequestCi is null");
        this.toolNames = ImmutableList.copyOf(toolNames);
        this.release = requireNonNull(release, "release is null");
    }

    public Optional<DevelopmentResultBridge> development()
    {
        return development;
    }

    /** The review capability this token exposes, resolved from the task when it registered. */
    public Optional<ReviewBridge> review()
    {
        return review;
    }

    /** The managed guidance for the launch's instruction channel; empty only for a token without any guidance. */
    public String instruction()
    {
        return PermissionBroker.managedInstruction(codeGraph, review, development, localReview, pullRequestCi).orElse("");
    }

    public String token()
    {
        return token;
    }

    /** The exact fixed inventory exposed by this token, used for provider-specific tool approval configuration. */
    public List<String> toolNames()
    {
        return toolNames;
    }

    public URI endpoint()
    {
        return endpoint;
    }

    @Override
    public void close()
    {
        release.run();
    }
}
