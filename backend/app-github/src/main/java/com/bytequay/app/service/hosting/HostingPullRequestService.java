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
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.store.HostingPullRequestStore;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Keeps remote snapshots separate from retained request identity and local review state. */
@Service
public class HostingPullRequestService
{
    private final HostingConnectionService connections;
    private final HostingPullRequestStore snapshots;
    private final Clock clock;
    private final Striped<Lock> refreshLocks = Striped.lock(64);

    public HostingPullRequestService(HostingConnectionService connections, HostingPullRequestStore snapshots, Clock clock)
    {
        this.connections = requireNonNull(connections, "connections is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<HostingPullRequestSnapshot> cached(long accountId, long repositoryId, int number)
    {
        return cached(connections.readSession(accountId), repositoryId, number);
    }

    public Optional<HostingPullRequestSnapshot> cached(HostingReadSession session, long repositoryId, int number)
    {
        new Resource(repositoryId, number);
        return session.withCurrent(() -> snapshots.find(session.accountId(), repositoryId, number)
                .filter(snapshot -> snapshot.credentialScope().equals(session.credentialScope()) && snapshot.credentialGeneration() == session.generation()));
    }

    public HostingPullRequestSnapshot refresh(long accountId, long repositoryId, int number)
    {
        return refresh(connections.readSession(accountId), repositoryId, number, false);
    }

    public HostingPullRequestSnapshot refresh(HostingReadSession session, long repositoryId, int number, boolean force)
    {
        // Serializing this resource also prevents two accounts from creating its retained identity twice.
        return withLock(refreshLocks.get(new Resource(repositoryId, number)), () -> refreshLocked(session, repositoryId, number, force));
    }

    public void evict(long accountId, long repositoryId, int number)
    {
        evict(connections.readSession(accountId), repositoryId, number);
    }

    public void evict(HostingReadSession session, long repositoryId, int number)
    {
        withLock(refreshLocks.get(new Resource(repositoryId, number)), () -> session.withCurrent(() -> {
            snapshots.evict(session.accountId(), repositoryId, number, session.credentialScope());
            return null;
        }));
    }

    public void invalidateRepository(long repositoryId, int number)
    {
        withLock(refreshLocks.get(new Resource(repositoryId, number)), () -> {
            snapshots.evictRepository(repositoryId, number);
            return null;
        });
    }

    private HostingPullRequestSnapshot refreshLocked(HostingReadSession session, long repositoryId, int number, boolean force)
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
            FetchedPullRequest summary = fetched.pullRequest().orElseThrow();
            if (summary.number() != number) {
                throw new HostingFailure(INVALID_RESPONSE, "Hosting response identifies another request");
            }
            PullRequestFetch accepted = fetched;
            return session.withCurrent(() -> snapshots.save(session.accountId(), repositoryId, session.credentialScope(), session.generation(),
                    summary, accepted.detail().orElseThrow(), accepted.etag(), clock.instant()));
        }
        catch (HostingFailure failure) {
            if (failure.kind() == AUTHENTICATION_REQUIRED || failure.kind() == FORBIDDEN || failure.kind() == MISSING_OR_INACCESSIBLE) {
                snapshots.evict(session.accountId(), repositoryId, number, session.credentialScope());
            }
            throw failure;
        }
    }

    private record Resource(long repositoryId, int number)
    {
        private Resource
        {
            checkArgument(repositoryId > 0, "repositoryId must be positive");
            checkArgument(number > 0, "request number must be positive");
        }
    }
}
