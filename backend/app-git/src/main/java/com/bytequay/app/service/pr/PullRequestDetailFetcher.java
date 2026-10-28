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

import com.bytequay.app.service.hosting.HostingPullRequestContents;
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
    private final HostingPullRequestContents contents;
    private final Executor ioExecutor;

    PullRequestDetailFetcher(HostingPullRequestContents contents, @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.contents = requireNonNull(contents, "contents is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    String headRef(PullRequestRead route)
    {
        return contents.reader(route.session()).headRef(route.number());
    }

    boolean viewerCanWrite(PullRequestRead route)
    {
        return contents.reader(route.session()).viewerCanWrite(route.session().access(), route.repository());
    }

    Optional<DiscussionProbe> probeDiscussion(PullRequestRead route, Map<String, String> etags)
    {
        return contents.reader(route.session()).probeDiscussion(route.session().access(), route.repository(), route.number(), etags);
    }

    ListenableFuture<StoredPrDetail> fetchDiscussion(PullRequestRead route, StoredPrDetail existing)
    {
        return submit(() -> contents.reader(route.session()).discussion(route.session().access(), route.repository(), route.number(), existing), ioExecutor);
    }

    List<GitHubPullRequestIdentity> identitiesByHead(PullRequestRead route, RepoRef sourceRepository, String branch)
    {
        return contents.reader(route.session()).identitiesByHead(route.session().access(), route.repository(), sourceRepository, branch);
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
