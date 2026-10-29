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
import com.bytequay.core.hosting.HostingCredential.Source;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingPermissions;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.hosting.RepositoryVisibility;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Owns connection selection and retained identity; remote adapters never select stored credentials. */
@Service
public class HostingConnectionService
{
    private final HostingIdentityStore identities;
    private final HostingCredentialStore credentials;
    private final Map<HostingProvider, HostingIntegration> integrations;
    private final Map<HostingProvider, HostingCli> clis;

    public HostingConnectionService(HostingIdentityStore identities, HostingCredentialStore credentials, List<HostingIntegration> integrations, List<HostingCli> clis)
    {
        this.identities = requireNonNull(identities, "identities is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.integrations = integrations.stream().collect(toImmutableMap(HostingIntegration::provider, integration -> integration));
        this.clis = clis.stream().collect(toImmutableMap(HostingCli::provider, cli -> cli));
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
        var accounts = integration(server.provider()).accounts();
        RemoteHostingAccount remote = accounts.verify(access);
        Optional<HostingPermissions> permissions = accounts.checkPermissions(access);
        permissions.filter(report -> report.status() == MISSING).ifPresent(report -> {
            throw new HostingFailure(FORBIDDEN, report.detail());
        });
        HostingInstance instance = identities.upsertInstance(server);
        HostingAccount account = identities.upsertAccount(instance.id(), remote);
        credentials.save(account.id(), token, source);
        return new HostingConnection(account, instance, true, permissions);
    }

    public List<HostingConnection> connections()
    {
        return identities.listInstances().stream()
                .flatMap(instance -> identities.listAccounts(instance.id()).stream()
                        .map(account -> new HostingConnection(account, instance, credentials.find(account.id()).isPresent(), Optional.empty())))
                .collect(toImmutableList());
    }

    public HostingPermissions checkPermissions(long accountId)
    {
        HostingReadSession session = readSession(accountId);
        HostingAccess access = session.access();
        var accounts = integration(access.server().provider()).accounts();
        RemoteHostingAccount remote = accounts.verify(access);
        if (!remote.remoteId().equals(account(session).remoteUserId())) {
            throw new HostingFailure(AUTHENTICATION_REQUIRED, "The credential belongs to a different account. Reconnect this hosting account to continue");
        }
        return accounts.checkPermissions(access)
                .orElseThrow(() -> new HostingFailure(UNAVAILABLE, "Permission checks are unavailable for this hosting provider"));
    }

    public void disconnect(long accountId)
    {
        credentials.delete(accountId);
    }

    public RepositoryVisibility visibility(HostingReadSession session, String fullPath)
    {
        return integration(session.access().server().provider()).repositories().visibility(session.access(), fullPath);
    }

    public HostingRepository resolveRepository(long accountId, String fullPath)
    {
        return resolveRepository(readSession(accountId), fullPath);
    }

    public List<HostingRepository> searchRepositories(long accountId, String query, int limit)
    {
        checkArgument(limit > 0 && limit <= 30, "limit must be between 1 and 30");
        HostingAccess access = readSession(accountId).access();
        return integration(access.server().provider()).repositories().search(access, query.strip(), limit).stream()
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
        RemoteHostingAccount remote = integration(server.provider()).accounts().verify(new HostingAccess(server, token));
        HostingInstance instance = identities.upsertInstance(server);
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
        RemoteHostingRepository remote = integration(access.server().provider()).repositories().findByPath(access, fullPath);
        return identities.upsertRepository(access.instanceId().orElseThrow(), remote);
    }

    public HostingRepository resolveRepositoryById(HostingReadSession session, String remoteId)
    {
        HostingAccess access = session.access();
        RemoteHostingRepository remote = integration(access.server().provider()).repositories().findById(access, remoteId);
        checkArgument(remote.remoteId().equals(remoteId), "Hosting repository identity changed");
        return identities.upsertRepository(access.instanceId().orElseThrow(), remote);
    }

    public PullRequestFetch readPullRequest(HostingReadSession session, long repositoryId, int number, Optional<String> repositoryPath, Optional<String> etag)
    {
        checkArgument(number > 0, "request number must be positive");
        checkOptionalText(repositoryPath, "repositoryPath");
        requireNonNull(etag, "etag is null");
        HostingRepository repository = repository(session.access(), repositoryId);
        return readPullRequest(session, repository, number, repositoryPath.filter(repository.fullPath()::equals).isPresent() ? etag : Optional.empty());
    }

    public PullRequestListPage listPullRequests(HostingReadSession session, long repositoryId, boolean openOnly, int page, int pageSize)
    {
        checkArgument(page > 0, "page must be positive");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        HostingAccess access = session.access();
        return integration(access.server().provider()).pullRequests().list(access, repository(access, repositoryId), openOnly, page, pageSize);
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
        HostingServer server = new HostingServer(instance.provider(), instance.webBaseUrl(), instance.apiBaseUrl());
        HostingAccess access = new HostingAccess(instance, cli(instance.provider()).readToken(server));
        RemoteHostingAccount remote = integration(instance.provider()).accounts().verify(access);
        if (!remote.remoteId().equals(account.remoteUserId())) {
            throw new HostingFailure(AUTHENTICATION_REQUIRED, "The CLI is signed in to a different account. Reconnect this hosting account to continue");
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
        return Optional.ofNullable(clis.get(provider))
                .orElseThrow(() -> new HostingFailure(AUTHENTICATION_REQUIRED, "Browser sign-in is unavailable for this hosting provider. Connect with a token instead"));
    }

    private HostingIntegration integration(HostingProvider provider)
    {
        HostingIntegration integration = integrations.get(requireNonNull(provider, "provider is null"));
        checkArgument(integration != null, "unsupported hosting provider");
        return integration;
    }
}
