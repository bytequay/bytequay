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

import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingCapabilities;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.HostingVersion;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.PullRequestCi;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestDiscovery;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.hosting.RepositoryMetadataReader;
import com.bytequay.core.hosting.TeamActivityReader;
import com.bytequay.core.store.HostingIdentityStore;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;

/** Hosting integrations whose ports a test does not exercise are mocks. */
public final class HostingIntegrationFixtures
{
    private HostingIntegrationFixtures() {}

    public static HostingIntegrations integrations(HostingIntegration... integrations)
    {
        // No server has a stored release, so each provider resolves to its newest implementation.
        return integrations(mock(HostingIdentityStore.class), integrations);
    }

    public static HostingIntegrations integrations(HostingIdentityStore identities, HostingIntegration... integrations)
    {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int index = 0; index < integrations.length; index++) {
            beans.addBean(integrations[index].provider().name() + "-" + index, integrations[index]);
        }
        return new HostingIntegrations(beans.getBeanProvider(HostingIntegration.class), identities);
    }

    /** The same implementations, needing at least {@code since}. */
    public static HostingIntegration since(HostingIntegration integration, HostingVersion since)
    {
        return new HostingIntegration(integration.provider(), Optional.of(since), integration.capabilities(), integration.accounts(), integration.repositories(),
                integration.pullRequests(), integration.contents(), integration.writer(), integration.issues(), integration.metadata(), integration.discovery(),
                integration.ci(), integration.team());
    }

    /** One mocked implementation per provider, for code that only reads capabilities or routes by server. */
    public static HostingIntegrations bothProviders()
    {
        return integrations(issues(HostingProvider.GITHUB, mock(IssueClient.class)), issues(HostingProvider.GITLAB, mock(IssueClient.class)));
    }

    /** Matches a target for this repository, whichever access the code under test selected. */
    public static HostingTarget targeting(HostingRepository repository)
    {
        return argThat(target -> target != null && target.repository().equals(repository));
    }

    public static HostingIntegration connections(HostingProvider provider, HostingAccounts accounts, HostingRepositories repositories, PullRequestReader pullRequests)
    {
        return integration(provider, accounts, repositories, pullRequests, mock(PullRequestContentReader.class), mock(PullRequestWriter.class),
                mock(IssueClient.class), mock(RepositoryMetadataReader.class), mock(PullRequestDiscovery.class), mock(PullRequestCi.class), mock(TeamActivityReader.class));
    }

    public static HostingIntegration pullRequests(HostingProvider provider, PullRequestContentReader contents, PullRequestWriter writer)
    {
        return pullRequests(provider, contents, writer, mock(PullRequestDiscovery.class));
    }

    public static HostingIntegration pullRequests(HostingProvider provider, PullRequestContentReader contents, PullRequestWriter writer, PullRequestDiscovery discovery)
    {
        return integration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class), contents, writer,
                mock(IssueClient.class), mock(RepositoryMetadataReader.class), discovery, mock(PullRequestCi.class), mock(TeamActivityReader.class));
    }

    public static HostingIntegration ci(HostingProvider provider, PullRequestContentReader contents, PullRequestWriter writer, PullRequestCi ci)
    {
        return integration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class), contents, writer,
                mock(IssueClient.class), mock(RepositoryMetadataReader.class), mock(PullRequestDiscovery.class), ci, mock(TeamActivityReader.class));
    }

    public static HostingIntegration team(HostingProvider provider, TeamActivityReader team)
    {
        return integration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class),
                mock(PullRequestContentReader.class), mock(PullRequestWriter.class), mock(IssueClient.class), mock(RepositoryMetadataReader.class),
                mock(PullRequestDiscovery.class), mock(PullRequestCi.class), team);
    }

    public static HostingIntegration issues(HostingProvider provider, IssueClient issues)
    {
        return integration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class),
                mock(PullRequestContentReader.class), mock(PullRequestWriter.class), issues, mock(RepositoryMetadataReader.class), mock(PullRequestDiscovery.class),
                mock(PullRequestCi.class), mock(TeamActivityReader.class));
    }

    public static HostingIntegration metadata(HostingProvider provider, RepositoryMetadataReader metadata)
    {
        return integration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class),
                mock(PullRequestContentReader.class), mock(PullRequestWriter.class), mock(IssueClient.class), metadata, mock(PullRequestDiscovery.class),
                mock(PullRequestCi.class), mock(TeamActivityReader.class));
    }

    private static HostingIntegration integration(HostingProvider provider, HostingAccounts accounts, HostingRepositories repositories, PullRequestReader pullRequests,
            PullRequestContentReader contents, PullRequestWriter writer, IssueClient issues, RepositoryMetadataReader metadata, PullRequestDiscovery discovery,
            PullRequestCi ci, TeamActivityReader team)
    {
        return new HostingIntegration(provider, Optional.empty(), capabilities(provider), accounts, repositories, pullRequests, contents, writer, issues, metadata,
                discovery, ci, team);
    }

    /** The capabilities each provider's own integration declares. */
    public static HostingCapabilities capabilities(HostingProvider provider)
    {
        return provider == HostingProvider.GITHUB ? HostingCapabilities.ALL : new HostingCapabilities(false, false, true, true, false, true);
    }
}
