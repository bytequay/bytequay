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

import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Owns connection selection and retained identity; remote adapters never select stored credentials. */
@Service
public class HostingConnectionService
{
    private final HostingIdentityStore identities;
    private final HostingCredentialStore credentials;
    private final Map<HostingProvider, HostingIntegration> integrations;
    private final Map<HostingServer, Object> serverLocks = new ConcurrentHashMap<>();

    public HostingConnectionService(HostingIdentityStore identities, HostingCredentialStore credentials, List<HostingIntegration> integrations)
    {
        this.identities = requireNonNull(identities, "identities is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.integrations = integrations.stream().collect(toImmutableMap(HostingIntegration::provider, integration -> integration));
    }

    public HostingConnection connect(HostingProvider provider, URI webBaseUrl, URI apiBaseUrl, String token)
    {
        checkText(token, "token");
        HostingServer server = new HostingServer(provider, webBaseUrl, apiBaseUrl);
        HostingIntegration integration = integration(provider);
        Map<String, Long> generations;
        synchronized (lock(server)) {
            generations = findInstance(server).stream().flatMap(instance -> identities.listAccounts(instance.id()).stream())
                    .collect(toImmutableMap(HostingAccount::remoteUserId, account -> credentials.generation(account.id())));
        }
        // A failed verification must not reserve an immutable instance with a mistyped API address.
        RemoteHostingAccount remote = integration.accounts().verify(new HostingAccess(server, () -> token));
        synchronized (lock(server)) {
            Optional<HostingAccount> previous = findInstance(server).stream().flatMap(instance -> identities.listAccounts(instance.id()).stream())
                    .filter(account -> account.remoteUserId().equals(remote.remoteId())).findFirst();
            long currentGeneration = previous.map(account -> credentials.generation(account.id())).orElse(0L);
            if (currentGeneration != generations.getOrDefault(remote.remoteId(), 0L)) {
                throw new HostingFailure(REVISION_CONFLICT, "This connection changed during verification; reconnect to try again");
            }
            HostingInstance instance = identities.upsertInstance(server);
            HostingAccount account = identities.upsertAccount(instance.id(), remote);
            credentials.save(account.id(), token);
            return new HostingConnection(account, instance, true);
        }
    }

    public List<HostingConnection> connections()
    {
        return identities.listInstances().stream()
                .flatMap(instance -> identities.listAccounts(instance.id()).stream()
                        .map(account -> new HostingConnection(account, instance, credentials.find(account.id()).isPresent())))
                .collect(toImmutableList());
    }

    public void disconnect(long accountId)
    {
        HostingServer server = server(account(accountId));
        synchronized (lock(server)) {
            credentials.delete(accountId);
        }
    }

    public HostingRepository resolveRepository(long accountId, String fullPath)
    {
        HostingAccess access = access(accountId);
        return saveRepository(access, integration(access.server().provider()).repositories().findByPath(access, fullPath));
    }

    public HostingRepository resolveRepositoryById(long accountId, String remoteId)
    {
        HostingAccess access = access(accountId);
        return saveRepository(access, integration(access.server().provider()).repositories().findById(access, remoteId));
    }

    public HostingReadSession readSession(long accountId)
    {
        synchronized (lock(server(account(accountId)))) {
            HostingCredential selected = credential(accountId);
            return new HostingReadSession(accountId, CONNECTED_ACCOUNT, selected.generation(), access(accountId), new HostingReadSession.Guard()
            {
                @Override
                public <T> T withCurrent(Supplier<T> action)
                {
                    return withCurrentCredential(accountId, selected.generation(), action);
                }
            });
        }
    }

    /** Verifies an externally owned credential without replacing a separately connected account's token. */
    public HostingReadSession verifySession(HostingServer server, String scope, long generation, Supplier<String> token, HostingReadSession.Guard guard)
    {
        requireNonNull(server, "server is null");
        checkText(scope, "scope");
        checkArgument(generation > 0, "generation must be positive");
        requireNonNull(token, "token is null");
        requireNonNull(guard, "guard is null");
        Supplier<String> currentToken = () -> guard.withCurrent(token);
        RemoteHostingAccount remote = integration(server.provider()).accounts().verify(new HostingAccess(server, currentToken));
        return guard.withCurrent(() -> {
            synchronized (lock(server)) {
                HostingInstance instance = identities.upsertInstance(server);
                HostingAccount account = identities.upsertAccount(instance.id(), remote);
                return new HostingReadSession(account.id(), scope, generation, new HostingAccess(instance, currentToken), guard);
            }
        });
    }

    public HostingAccount account(HostingReadSession session)
    {
        return session.withCurrent(() -> {
            HostingAccount account = account(session.accountId());
            checkArgument(account.instanceId() == session.access().instanceId().orElseThrow(), "Hosting account belongs to another instance");
            return account;
        });
    }

    public Optional<HostingRepository> findRepository(HostingReadSession session, String fullPath)
    {
        return session.withCurrent(() -> {
            List<HostingRepository> matches = identities.findRepositories(session.access().server(), fullPath);
            if (matches.size() != 1) {
                return Optional.empty();
            }
            HostingRepository repository = matches.getFirst();
            checkArgument(repository.instanceId() == session.access().instanceId().orElseThrow(), "Repository belongs to another hosting instance");
            return Optional.of(repository);
        });
    }

    public HostingRepository resolveRepository(HostingReadSession session, String fullPath)
    {
        HostingAccess access = session.access();
        RemoteHostingRepository remote = integration(access.server().provider()).repositories().findByPath(access, fullPath);
        return session.withCurrent(() -> identities.upsertRepository(access.instanceId().orElseThrow(), remote));
    }

    public HostingRepository resolveRepositoryById(HostingReadSession session, String remoteId)
    {
        HostingAccess access = session.access();
        RemoteHostingRepository remote = integration(access.server().provider()).repositories().findById(access, remoteId);
        checkArgument(remote.remoteId().equals(remoteId), "Hosting repository identity changed");
        return session.withCurrent(() -> identities.upsertRepository(access.instanceId().orElseThrow(), remote));
    }

    /** The action may accept or persist local results, but must not perform remote I/O while holding the credential lock. */
    public <T> T withCurrentCredential(long accountId, long generation, Supplier<T> action)
    {
        checkArgument(generation > 0, "generation must be positive");
        requireNonNull(action, "action is null");
        synchronized (lock(server(account(accountId)))) {
            if (credential(accountId).generation() != generation) {
                throw new HostingFailure(REVISION_CONFLICT, "The hosting credential changed during this request; refresh to retry");
            }
            return action.get();
        }
    }

    private HostingRepository saveRepository(HostingAccess access, RemoteHostingRepository remote)
    {
        synchronized (lock(access.server())) {
            // The bound accessor rechecks credential generation before accepting remote data.
            access.token();
            return identities.upsertRepository(access.instanceId().orElseThrow(), remote);
        }
    }

    public PullRequestFetch readPullRequest(long accountId, long repositoryId, int number)
    {
        checkArgument(number > 0, "request number must be positive");
        return readPullRequest(readSession(accountId), repositoryId, number, Optional.empty(), Optional.empty());
    }

    public PullRequestFetch readPullRequest(long accountId, long repositoryId, int number, long credentialGeneration, Optional<String> repositoryPath, Optional<String> etag)
    {
        return readPullRequest(withCurrentCredential(accountId, credentialGeneration, () -> readSession(accountId)), repositoryId, number, repositoryPath, etag);
    }

    public PullRequestFetch readPullRequest(HostingReadSession session, long repositoryId, int number, Optional<String> repositoryPath, Optional<String> etag)
    {
        checkArgument(number > 0, "request number must be positive");
        checkOptionalText(repositoryPath, "repositoryPath");
        requireNonNull(etag, "etag is null");
        HostingRepository repository = session.withCurrent(() -> repository(session.access(), repositoryId));
        return readPullRequest(session, repository, number, repositoryPath.filter(repository.fullPath()::equals).isPresent() ? etag : Optional.empty());
    }

    private HostingRepository repository(HostingAccess access, long repositoryId)
    {
        HostingRepository repository = identities.findRepository(repositoryId)
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Repository identity is unavailable"));
        checkArgument(repository.instanceId() == access.instanceId().orElseThrow(), "repository belongs to a different hosting instance");
        return repository;
    }

    private PullRequestFetch readPullRequest(HostingReadSession session, HostingRepository repository, int number, Optional<String> etag)
    {
        HostingAccess access = session.access();
        PullRequestFetch result = integration(access.server().provider()).pullRequests().fetch(access, repository, number, etag);
        return session.withCurrent(() -> {
            if (etag.isEmpty() && result.pullRequest().isEmpty() && result.detail().isEmpty()) {
                throw new HostingFailure(INVALID_RESPONSE, "Hosting provider returned an unchanged response without a validator");
            }
            return result;
        });
    }

    private HostingAccess access(long accountId)
    {
        HostingAccount account = account(accountId);
        HostingInstance instance = identities.findInstance(account.instanceId())
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting instance is unavailable"));
        synchronized (lock(new HostingServer(instance.provider(), instance.webBaseUrl(), instance.apiBaseUrl()))) {
            HostingCredential credential = credential(accountId);
            return new HostingAccess(instance, () -> {
                HostingCredential current = credential(accountId);
                if (current.generation() != credential.generation()) {
                    throw new HostingFailure(REVISION_CONFLICT, "The hosting credential changed during this request; refresh to retry");
                }
                return current.secret();
            });
        }
    }

    private HostingCredential credential(long accountId)
    {
        return credentials.find(accountId).orElseThrow(() -> new HostingFailure(AUTHENTICATION_REQUIRED, "Reconnect this hosting account to continue"));
    }

    private HostingAccount account(long accountId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        return identities.findAccount(accountId).orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting account is unavailable"));
    }

    private HostingIntegration integration(HostingProvider provider)
    {
        HostingIntegration integration = integrations.get(requireNonNull(provider, "provider is null"));
        checkArgument(integration != null, "unsupported hosting provider");
        return integration;
    }

    private Optional<HostingInstance> findInstance(HostingServer server)
    {
        Optional<HostingInstance> instance = identities.listInstances().stream()
                .filter(value -> value.provider() == server.provider() && value.webBaseUrl().equals(server.webBaseUrl())).findFirst();
        checkArgument(instance.isEmpty() || instance.orElseThrow().apiBaseUrl().equals(server.apiBaseUrl()), "An existing instance cannot change its API endpoint");
        return instance;
    }

    private HostingServer server(HostingAccount account)
    {
        HostingInstance instance = identities.findInstance(account.instanceId())
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting instance is unavailable"));
        return new HostingServer(instance.provider(), instance.webBaseUrl(), instance.apiBaseUrl());
    }

    private Object lock(HostingServer server)
    {
        return serverLocks.computeIfAbsent(server, ignored -> new Object());
    }
}
