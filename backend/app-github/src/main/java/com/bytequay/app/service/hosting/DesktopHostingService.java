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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubReadSelection;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.store.HostingIdentityStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static java.util.Objects.requireNonNull;

/** Uses the desktop's selected GitHub credential without replacing a separately connected account credential. */
@Service
public class DesktopHostingService
{
    private static final HostingServer GITHUB_SERVER = new HostingServer(GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));

    private final CredentialService credentials;
    private final HostingIdentityStore identities;
    private final HostingConnectionService connections;
    private final HostingPullRequestService requests;
    private final HostingAccounts accounts;
    private volatile Selection selected;

    public DesktopHostingService(CredentialService credentials, HostingIdentityStore identities, HostingConnectionService connections,
            HostingPullRequestService requests, List<HostingIntegration> integrations)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.connections = requireNonNull(connections, "connections is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.accounts = integrations.stream().filter(integration -> integration.provider() == GITHUB).findFirst().orElseThrow().accounts();
    }

    public String scope(String pat)
    {
        return selection(pat).scope();
    }

    public <T> T withCurrent(String scope, Supplier<T> action)
    {
        Selection selection = selected;
        if (selection == null || !selection.scope().equals(scope)) {
            throw new HostingFailure(REVISION_CONFLICT, "The desktop GitHub selection changed; refresh to retry");
        }
        return credentials.withCurrentGitHubRead(selection.credential, action);
    }

    public HostingPullRequestSnapshot refresh(String pat, PullRequestRef ref, boolean force)
    {
        Selection selection = selection(pat);
        HostingRepository repository = selection.repository(ref.repoFullName());
        return requests.refresh(selection.session(), repository.id(), ref.number(), force);
    }

    public Optional<HostingPullRequestSnapshot> cached(String pat, PullRequestRef ref)
    {
        Selection selection = selection(pat);
        return Optional.ofNullable(selection.repositories.get(ref.repoFullName().toLowerCase(Locale.ROOT)))
                .flatMap(repository -> requests.cached(selection.session(), repository.id(), ref.number()));
    }

    public void evict(String repo, int number)
    {
        identities.findRepositories(GITHUB_SERVER, repo).forEach(repository -> requests.invalidateRepository(repository.id(), number));
    }

    private Selection selection(String pat)
    {
        GitHubReadSelection captured = credentials.captureGitHubRead(pat);
        return credentials.withCurrentGitHubRead(captured, () -> {
            Selection current = selected;
            if (current == null || !current.credential.equals(captured)) {
                current = new Selection(captured);
                selected = current;
            }
            return current;
        });
    }

    private final class Selection
    {
        private final GitHubReadSelection credential;
        private final Map<String, HostingRepository> repositories = new ConcurrentHashMap<>();
        private HostingReadSession session;

        private Selection(GitHubReadSelection credential)
        {
            this.credential = credential;
        }

        private String scope()
        {
            return "github-global:" + credential.revision();
        }

        private synchronized HostingReadSession session()
        {
            if (session == null) {
                Supplier<String> token = () -> credentials.withCurrentGitHubRead(credential, credential::token);
                RemoteHostingAccount remote = accounts.verify(new HostingAccess(GITHUB_SERVER, token));
                session = credentials.withCurrentGitHubRead(credential, () -> {
                    HostingInstance instance = identities.upsertInstance(GITHUB_SERVER);
                    HostingAccount account = identities.upsertAccount(instance.id(), remote);
                    return new HostingReadSession(account.id(), scope(), 1, new HostingAccess(instance, token), new HostingReadSession.Guard()
                    {
                        @Override
                        public <T> T withCurrent(Supplier<T> action)
                        {
                            return credentials.withCurrentGitHubRead(credential, action);
                        }
                    });
                });
            }
            return session;
        }

        private HostingRepository repository(String fullPath)
        {
            return repositories.computeIfAbsent(fullPath.toLowerCase(Locale.ROOT), ignored -> connections.resolveRepository(session(), fullPath));
        }
    }
}
