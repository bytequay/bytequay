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
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestDiscovery;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.hosting.RepositoryMetadataReader;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;

/** Hosting integrations whose ports a test does not exercise are mocks. */
public final class HostingIntegrationFixtures
{
    private HostingIntegrationFixtures() {}

    public static HostingIntegrations integrations(HostingIntegration... integrations)
    {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (HostingIntegration integration : integrations) {
            beans.addBean(integration.provider().name(), integration);
        }
        return new HostingIntegrations(beans.getBeanProvider(HostingIntegration.class));
    }

    /** Matches a target for this repository, whichever access the code under test selected. */
    public static HostingTarget targeting(HostingRepository repository)
    {
        return argThat(target -> target != null && target.repository().equals(repository));
    }

    public static HostingIntegration connections(HostingProvider provider, HostingAccounts accounts, HostingRepositories repositories, PullRequestReader pullRequests)
    {
        return new HostingIntegration(provider, accounts, repositories, pullRequests, mock(PullRequestContentReader.class), mock(PullRequestWriter.class),
                mock(IssueClient.class), mock(RepositoryMetadataReader.class), mock(PullRequestDiscovery.class));
    }

    public static HostingIntegration pullRequests(HostingProvider provider, PullRequestContentReader contents, PullRequestWriter writer)
    {
        return pullRequests(provider, contents, writer, mock(PullRequestDiscovery.class));
    }

    public static HostingIntegration pullRequests(HostingProvider provider, PullRequestContentReader contents, PullRequestWriter writer, PullRequestDiscovery discovery)
    {
        return new HostingIntegration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class), contents, writer,
                mock(IssueClient.class), mock(RepositoryMetadataReader.class), discovery);
    }

    public static HostingIntegration issues(HostingProvider provider, IssueClient issues)
    {
        return new HostingIntegration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class),
                mock(PullRequestContentReader.class), mock(PullRequestWriter.class), issues, mock(RepositoryMetadataReader.class), mock(PullRequestDiscovery.class));
    }

    public static HostingIntegration metadata(HostingProvider provider, RepositoryMetadataReader metadata)
    {
        return new HostingIntegration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), mock(PullRequestReader.class),
                mock(PullRequestContentReader.class), mock(PullRequestWriter.class), mock(IssueClient.class), metadata, mock(PullRequestDiscovery.class));
    }
}
