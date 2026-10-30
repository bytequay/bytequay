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
package com.bytequay.app.service.pr;

import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.core.domain.GitHubPullRequestIdentity;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.hosting.DiscussionProbe;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.util.concurrent.Futures.submit;
import static java.util.Objects.requireNonNull;

@Component
class PullRequestDetailFetcher
{
    private final HostingIntegrations integrations;
    private final Executor ioExecutor;

    PullRequestDetailFetcher(HostingIntegrations integrations, @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.integrations = requireNonNull(integrations, "integrations is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    String headRef(PullRequestRead route)
    {
        return integrations.of(route.target()).contents().headRef(route.number());
    }

    boolean viewerCanWrite(PullRequestRead route)
    {
        return integrations.of(route.target()).contents().viewerCanWrite(route.target());
    }

    boolean reviewRequested(PullRequestRead route, String login)
    {
        return integrations.of(route.target()).contents().reviewRequested(route.target(), route.number(), login);
    }

    Optional<DiscussionProbe> probeDiscussion(PullRequestRead route, Map<String, String> etags)
    {
        return integrations.of(route.target()).contents().probeDiscussion(route.target(), route.number(), etags);
    }

    ListenableFuture<StoredPrDetail> fetchDiscussion(PullRequestRead route, StoredPrDetail existing)
    {
        return submit(() -> integrations.of(route.target()).contents().discussion(route.target(), route.number(), existing), ioExecutor);
    }

    List<GitHubPullRequestIdentity> identitiesByHead(PullRequestRead route, RepoRef sourceRepository, String branch)
    {
        return integrations.of(route.target()).contents().identitiesByHead(route.target(), sourceRepository, branch);
    }

    static <T> T await(ListenableFuture<T> future)
    {
        try {
            return future.get();
        }
        catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pull request discussion refresh was interrupted");
        }
        catch (ExecutionException e) {
            throwIfUnchecked(e.getCause());
            throw new UncheckedExecutionException(e.getCause());
        }
    }
}
