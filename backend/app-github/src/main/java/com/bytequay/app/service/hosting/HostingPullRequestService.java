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
import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.store.HostingCredentialStore;
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
    private final HostingCredentialStore credentials;
    private final HostingPullRequestStore snapshots;
    private final Clock clock;
    private final Striped<Lock> refreshLocks = Striped.lock(64);

    public HostingPullRequestService(HostingConnectionService connections, HostingCredentialStore credentials, HostingPullRequestStore snapshots, Clock clock)
    {
        this.connections = requireNonNull(connections, "connections is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<HostingPullRequestSnapshot> cached(long accountId, long repositoryId, int number)
    {
        new Resource(repositoryId, number);
        long generation = generation(accountId);
        return connections.withCurrentCredential(accountId, generation, () -> snapshots.find(accountId, repositoryId, number)
                .filter(snapshot -> snapshot.credentialGeneration() == generation));
    }

    public HostingPullRequestSnapshot refresh(long accountId, long repositoryId, int number)
    {
        // Serializing this resource also prevents two accounts from creating its retained identity twice.
        return withLock(refreshLocks.get(new Resource(repositoryId, number)), () -> refreshLocked(accountId, repositoryId, number));
    }

    public void evict(long accountId, long repositoryId, int number)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        withLock(refreshLocks.get(new Resource(repositoryId, number)), () -> {
            snapshots.evict(accountId, repositoryId, number);
            return null;
        });
    }

    private HostingPullRequestSnapshot refreshLocked(long accountId, long repositoryId, int number)
    {
        long generation = generation(accountId);
        Optional<HostingPullRequestSnapshot> previous = connections.withCurrentCredential(accountId, generation, () -> snapshots.find(accountId, repositoryId, number)
                .filter(snapshot -> snapshot.credentialGeneration() == generation));
        try {
            PullRequestFetch fetched = connections.readPullRequest(accountId, repositoryId, number, generation,
                    previous.map(snapshot -> snapshot.pullRequest().repo()), previous.flatMap(HostingPullRequestSnapshot::etag));
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
            return connections.withCurrentCredential(accountId, generation, () -> snapshots.save(accountId, repositoryId, generation,
                    summary, accepted.detail().orElseThrow(), accepted.etag(), clock.instant()));
        }
        catch (HostingFailure failure) {
            if (failure.kind() == AUTHENTICATION_REQUIRED || failure.kind() == FORBIDDEN || failure.kind() == MISSING_OR_INACCESSIBLE) {
                snapshots.evict(accountId, repositoryId, number);
            }
            throw failure;
        }
    }

    private long generation(long accountId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        return credentials.find(accountId).map(HostingCredential::generation)
                .orElseThrow(() -> new HostingFailure(AUTHENTICATION_REQUIRED, "Reconnect this hosting account to continue"));
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
