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
package com.bytequay.app.service.hosting;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.HostingPullRequestStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Keeps remote snapshots separate from retained request identity and local review state. */
@Service
public class HostingPullRequestService
{
    private final HostingConnectionService connections;
    private final HostingPullRequestStore snapshots;
    private final HostingIdentityStore identities;
    private final Clock clock;

    public HostingPullRequestService(HostingConnectionService connections, HostingPullRequestStore snapshots, HostingIdentityStore identities, Clock clock)
    {
        this.connections = requireNonNull(connections, "connections is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<HostingPullRequestSnapshot> cached(long accountId, long repositoryId, int number)
    {
        return cached(connections.readSession(accountId), repositoryId, number);
    }

    public Optional<HostingPullRequestSnapshot> cached(HostingReadSession session, long repositoryId, int number)
    {
        return snapshots.find(session.accountId(), repositoryId, number).filter(snapshot -> snapshot.credentialScope().equals(session.credentialScope()));
    }

    public HostingPullRequestSnapshot refresh(long accountId, long repositoryId, int number)
    {
        return refresh(connections.readSession(accountId), repositoryId, number, false);
    }

    public PullRequestListPage refreshPage(HostingReadSession session, long repositoryId, boolean openOnly, int page, int pageSize)
    {
        PullRequestListPage fetched = connections.listPullRequests(session, repositoryId, openOnly, page, pageSize);
        String login = connections.account(session).login();
        PullRequestListPage result = new PullRequestListPage(fetched.items().stream().map(row -> row.withViewerOrigin(login)).collect(toImmutableList()), fetched.hasNext());
        snapshots.savePage(session.accountId(), repositoryId, session.credentialScope(), result.items());
        return result;
    }

    public PullRequestListPage cachedPage(HostingReadSession session, long repositoryId, int page, int pageSize)
    {
        return snapshots.findPage(session.accountId(), repositoryId, session.credentialScope(), page, pageSize);
    }

    public void invalidateRepository(long repositoryId, int number)
    {
        snapshots.evictRepository(repositoryId, number);
    }

    public void invalidate(HostingServer server, String fullPath, int number)
    {
        identities.findRepositories(server, fullPath).forEach(repository -> invalidateRepository(repository.id(), number));
    }

    public HostingPullRequestSnapshot refresh(HostingReadSession session, long repositoryId, int number, boolean force)
    {
        Optional<HostingPullRequestSnapshot> previous = cached(session, repositoryId, number);
        try {
            PullRequestFetch fetched = connections.readPullRequest(session, repositoryId, number,
                    previous.map(snapshot -> snapshot.pullRequest().repo()), force ? Optional.empty() : previous.flatMap(HostingPullRequestSnapshot::etag));
            if (fetched.pullRequest().isPresent() != fetched.detail().isPresent()) {
                throw new HostingFailure(INVALID_RESPONSE, "Hosting response contains incomplete request metadata");
            }
            if (fetched.pullRequest().isEmpty()) {
                HostingPullRequestSnapshot unchanged = previous.filter(snapshot -> snapshot.etag().isPresent())
                        .orElseThrow(() -> new HostingFailure(INVALID_RESPONSE, "Hosting response has no matching cached metadata"));
                fetched = new PullRequestFetch(Optional.of(unchanged.pullRequest()), fetched.etag().or(unchanged::etag), Optional.of(unchanged.detail()));
            }
            FetchedPullRequest summary = fetched.pullRequest().orElseThrow().withViewerOrigin(connections.account(session).login());
            if (summary.number() != number) {
                throw new HostingFailure(INVALID_RESPONSE, "Hosting response identifies another request");
            }
            return snapshots.save(session.accountId(), repositoryId, session.credentialScope(),
                    summary, fetched.detail().orElseThrow(), fetched.etag(), clock.instant());
        }
        catch (HostingFailure failure) {
            if (failure.kind() == AUTHENTICATION_REQUIRED || failure.kind() == FORBIDDEN || failure.kind() == MISSING_OR_INACCESSIBLE) {
                snapshots.evict(session.accountId(), repositoryId, number, session.credentialScope());
            }
            throw failure;
        }
    }
}
