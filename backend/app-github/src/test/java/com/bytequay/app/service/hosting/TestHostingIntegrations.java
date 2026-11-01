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

import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.HostingVersion;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.store.HostingIdentityStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.integrations;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.issues;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.since;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestHostingIntegrations
{
    private static final HostingServer SERVER = new HostingServer(GITLAB, URI.create("https://gitlab.example.com"), URI.create("https://gitlab.example.com/api/v4"));

    private final HostingIdentityStore identities = mock(HostingIdentityStore.class);
    private final HostingIntegration current = since(issues(GITLAB, mock(IssueClient.class)), HostingVersion.of(16, 11));
    private final HostingIntegration legacy = issues(GITLAB, mock(IssueClient.class));
    private final HostingIntegration gitHub = issues(GITHUB, mock(IssueClient.class));
    private final HostingIntegrations registry = integrations(identities, legacy, gitHub, current);

    @Test
    void picksTheNewestImplementationTheServersReleaseSupports()
    {
        when(identities.findVersion(SERVER)).thenReturn(HostingVersion.parse("14.10.5"));
        assertThat(registry.of(SERVER)).isSameAs(legacy);

        when(identities.findVersion(SERVER)).thenReturn(HostingVersion.parse("16.11.0"));
        assertThat(registry.of(SERVER)).isSameAs(current);

        when(identities.findVersion(SERVER)).thenReturn(HostingVersion.parse("19.6.0-ee"));
        assertThat(registry.of(SERVER)).isSameAs(current);
    }

    @Test
    void aServerWhoseReleaseIsNotKnownYetGetsTheNewest()
    {
        when(identities.findVersion(SERVER)).thenReturn(Optional.empty());

        assertThat(registry.of(SERVER)).isSameAs(current);
        assertThat(registry.of(HostingServer.GITHUB_COM)).isSameAs(gitHub);
    }

    @Test
    void aReleaseOlderThanEveryImplementationIsRefused()
    {
        HostingIntegrations newOnly = integrations(identities, current);
        when(identities.findVersion(SERVER)).thenReturn(HostingVersion.parse("15.0.0"));

        assertThatThrownBy(() -> newOnly.of(SERVER)).isInstanceOf(HostingFailure.class).hasMessageContaining("15.0.0");
    }
}
