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
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.hosting.HostingCredential.Source;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingPermissions;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.HostingVersion;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.hosting.RepositoryVisibility;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.core.hosting.HostingPermissions.Status.MISSING;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Owns connection selection and retained identity; remote adapters never select stored credentials. */
@Service
public class HostingConnectionService
{
    private final HostingIdentityStore identities;
    private final HostingCredentialStore credentials;
    private final HostingIntegrations integrations;
    private final List<HostingCli> clis;
    /** The CLI token each account last proved is its own, so a CLI-backed account verifies again only after its token changes. */
    private final Map<Long, String> verifiedCliTokens = new ConcurrentHashMap<>();

    public HostingConnectionService(HostingIdentityStore identities, HostingCredentialStore credentials, HostingIntegrations integrations, List<HostingCli> clis)
    {
        this.identities = requireNonNull(identities, "identities is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
        this.clis = ImmutableList.copyOf(clis);
    }

    public HostingConnection connect(HostingProvider provider, URI webBaseUrl, URI apiBaseUrl, String token)
    {
        return connect(new HostingServer(provider, webBaseUrl, apiBaseUrl), token, Source.TOKEN);
    }

    public HostingConnection connectCli(HostingProvider provider, URI webBaseUrl, URI apiBaseUrl)
    {
        HostingServer server = new HostingServer(provider, webBaseUrl, apiBaseUrl);
        HostingCli cli = cli(provider);
        cli.login(server);
        return connect(server, cli.readToken(server), Source.CLI);
    }

    private HostingConnection connect(HostingServer server, String token, Source source)
    {
        checkText(token, "token");
        // Verify before retaining an instance so a mistyped API address is not stored.
        HostingAccess access = new HostingAccess(server, token);
        HostingAccounts reader = integrations.of(server).accounts();
        RemoteHostingAccount remote = reader.verify(access);
        Optional<HostingVersion> version = reader.serverVersion(access);
        HostingIntegration integration = integrations.of(server.provider(), version);
        Optional<HostingPermissions> permissions = integration.accounts().checkPermissions(access);
        permissions.filter(report -> report.status() == MISSING).ifPresent(report -> {
            throw new HostingFailure(FORBIDDEN, report.detail());
        });
        HostingInstance instance = identities.upsertInstance(server);
        identities.updateVersion(instance.id(), version);
        HostingAccount account = identities.upsertAccount(instance.id(), remote);
        credentials.save(account.id(), token, source);
        return new HostingConnection(account, instance, true, permissions, integration.capabilities());
    }

    public List<HostingConnection> connections()
    {
        return identities.listInstances().stream()
                .flatMap(instance -> identities.listAccounts(instance.id()).stream()
                        .map(account -> new HostingConnection(account, instance, credentials.find(account.id()).isPresent(), Optional.empty(),
                                integrations.of(server(instance)).capabilities())))
                .collect(toImmutableList());
    }

    public HostingPermissions checkPermissions(long accountId)
    {
        HostingReadSession session = readSession(accountId);
        HostingAccess access = session.access();
        HostingAccounts reader = integrations.of(access.server()).accounts();
        RemoteHostingAccount remote = reader.verify(access);
        if (!remote.remoteId().equals(account(session).remoteUserId())) {
            throw new HostingFailure(AUTHENTICATION_REQUIRED, "The credential belongs to a different account. Reconnect this hosting account to continue");
        }
        // A check also notices a server upgraded or downgraded since it was connected.
        Optional<HostingVersion> version = reader.serverVersion(access);
        identities.updateVersion(access.instanceId().orElseThrow(), version);
        return integrations.of(access.server().provider(), version).accounts().checkPermissions(access)
                .orElseThrow(() -> new HostingFailure(UNAVAILABLE, "Permission checks are unavailable for this hosting provider"));
    }

    public void disconnect(long accountId)
    {
        credentials.delete(accountId);
        verifiedCliTokens.remove(accountId);
    }

    /** How the account's credential was saved, or empty when the account is not connected. */
    public Optional<Source> credentialSource(long accountId)
    {
        return credentials.find(accountId).map(HostingCredential::source);
    }

    public RepositoryVisibility visibility(HostingReadSession session, String fullPath)
    {
        return integrations.of(session.access().server()).repositories().visibility(session.access(), fullPath);
    }

    public HostingRepository resolveRepository(long accountId, String fullPath)
    {
        return resolveRepository(readSession(accountId), fullPath);
    }

    public List<HostingRepository> searchRepositories(long accountId, String query, int limit)
    {
        checkArgument(limit > 0 && limit <= 30, "limit must be between 1 and 30");
        HostingAccess access = readSession(accountId).access();
        return integrations.of(access.server()).repositories().search(access, query.strip(), limit).stream()
                .map(repository -> identities.upsertRepository(access.instanceId().orElseThrow(), repository))
                .collect(toImmutableList());
    }

    public HostingRepository resolveRepositoryById(long accountId, String remoteId)
    {
        return resolveRepositoryById(readSession(accountId), remoteId);
    }

    public HostingReadSession readSession(long accountId)
    {
        return new HostingReadSession(accountId, CONNECTED_ACCOUNT, access(accountId));
    }

    /** Verifies an externally owned credential without replacing a separately connected account's token. */
    public HostingReadSession verifySession(HostingServer server, String scope, String token)
    {
        requireNonNull(server, "server is null");
        checkText(scope, "scope");
        checkText(token, "token");
        HostingAccess access = new HostingAccess(server, token);
        HostingAccounts reader = integrations.of(server).accounts();
        RemoteHostingAccount remote = reader.verify(access);
        HostingInstance instance = identities.upsertInstance(server);
        identities.updateVersion(instance.id(), reader.serverVersion(access));
        HostingAccount account = identities.upsertAccount(instance.id(), remote);
        return new HostingReadSession(account.id(), scope, new HostingAccess(instance, token));
    }

    public HostingAccount account(HostingReadSession session)
    {
        HostingAccount account = account(session.accountId());
        checkArgument(account.instanceId() == session.access().instanceId().orElseThrow(), "Hosting account belongs to another instance");
        return account;
    }

    public Optional<HostingRepository> findRepository(HostingReadSession session, String fullPath)
    {
        List<HostingRepository> matches = identities.findRepositories(session.access().server(), fullPath);
        if (matches.size() != 1) {
            return Optional.empty();
        }
        HostingRepository repository = matches.getFirst();
        checkArgument(repository.instanceId() == session.access().instanceId().orElseThrow(), "Repository belongs to another hosting instance");
        return Optional.of(repository);
    }

    public HostingRepository resolveRepository(HostingReadSession session, String fullPath)
    {
        HostingAccess access = session.access();
        RemoteHostingRepository remote = integrations.of(access.server()).repositories().findByPath(access, fullPath);
        return identities.upsertRepository(access.instanceId().orElseThrow(), remote);
    }

    public HostingRepository resolveRepositoryById(HostingReadSession session, String remoteId)
    {
        HostingAccess access = session.access();
        RemoteHostingRepository remote = integrations.of(access.server()).repositories().findById(access, remoteId);
        checkArgument(remote.remoteId().equals(remoteId), "Hosting repository identity changed");
        return identities.upsertRepository(access.instanceId().orElseThrow(), remote);
    }

    public PullRequestFetch readPullRequest(HostingReadSession session, long repositoryId, int number, Optional<String> repositoryPath, Optional<String> etag)
    {
        checkArgument(number > 0, "request number must be positive");
        checkOptionalText(repositoryPath, "repositoryPath");
        requireNonNull(etag, "etag is null");
        HostingRepository repository = repository(repositoryId);
        return readPullRequest(session, repository, number, repositoryPath.filter(repository.fullPath()::equals).isPresent() ? etag : Optional.empty());
    }

    public PullRequestListPage listPullRequests(HostingReadSession session, long repositoryId, boolean openOnly, int page, int pageSize)
    {
        checkArgument(page > 0, "page must be positive");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        HostingTarget target = new HostingTarget(session.access(), repository(repositoryId));
        return integrations.of(target).pullRequests().list(target, openOnly, page, pageSize);
    }

    private HostingRepository repository(long repositoryId)
    {
        return identities.findRepository(repositoryId).orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Repository identity is unavailable"));
    }

    private PullRequestFetch readPullRequest(HostingReadSession session, HostingRepository repository, int number, Optional<String> etag)
    {
        HostingTarget target = new HostingTarget(session.access(), repository);
        PullRequestFetch result = integrations.of(target).pullRequests().fetch(target, number, etag);
        if (etag.isEmpty() && result.pullRequest().isEmpty() && result.detail().isEmpty()) {
            throw new HostingFailure(INVALID_RESPONSE, "Hosting provider returned an unchanged response without a validator");
        }
        return result;
    }

    private HostingAccess access(long accountId)
    {
        HostingAccount account = account(accountId);
        HostingInstance instance = identities.findInstance(account.instanceId())
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting instance is unavailable"));
        HostingCredential credential = credential(accountId);
        if (credential.source() == Source.TOKEN) {
            return new HostingAccess(instance, credential.secret());
        }
        HostingServer server = server(instance);
        String token = cli(instance.provider()).readToken(server);
        HostingAccess access = new HostingAccess(instance, token);
        if (!token.equals(verifiedCliTokens.get(accountId))) {
            RemoteHostingAccount remote = integrations.of(server).accounts().verify(access);
            if (!remote.remoteId().equals(account.remoteUserId())) {
                throw new HostingFailure(AUTHENTICATION_REQUIRED, "The CLI is signed in to a different account. Reconnect this hosting account to continue");
            }
            verifiedCliTokens.put(accountId, token);
        }
        return access;
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

    private HostingCli cli(HostingProvider provider)
    {
        return clis.stream().filter(cli -> cli.provider() == provider).findFirst()
                .orElseThrow(() -> new HostingFailure(AUTHENTICATION_REQUIRED, "Browser sign-in is unavailable for this hosting provider. Connect with a token instead"));
    }

    private static HostingServer server(HostingInstance instance)
    {
        return new HostingServer(instance.provider(), instance.webBaseUrl(), instance.apiBaseUrl());
    }
}
