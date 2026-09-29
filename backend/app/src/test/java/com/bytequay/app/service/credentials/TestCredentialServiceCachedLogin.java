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
package com.bytequay.app.service.credentials;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.CredentialStore;
import com.bytequay.core.store.WorkspaceCredentialStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestCredentialServiceCachedLogin
{
    @Test
    void readsTheCachedLoginWhileAReplacementProfileIsStillLoading()
            throws Exception
    {
        CredentialStore store = mock(CredentialStore.class);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(store.source()).thenReturn(Optional.empty());
        when(store.getSecret()).thenReturn(Optional.of("token"), Optional.of("rotated"));
        when(accounts.fetchUserProfile("token")).thenReturn(profile("octocat"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(accounts.fetchUserProfile("rotated")).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return profile("hubot");
        });
        CredentialService service = new CredentialService(store, mock(WorkspaceCredentialStore.class), mock(GhCliService.class), accounts);
        assertThat(service.cachedLogin()).isEmpty();
        assertThat(service.cachedCredential()).isEmpty();
        service.resolveGitHubCredential();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var refreshing = executor.submit(service::resolveGitHubCredential);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(service::cachedLogin).get(1, TimeUnit.SECONDS)).contains("octocat");
                CredentialService.GitHubCredential cached = executor.submit(service::cachedCredential).get(1, TimeUnit.SECONDS).orElseThrow();
                assertThat(cached.token()).isEqualTo("token");
                assertThat(cached.profile().login()).isEqualTo("octocat");
            }
            finally {
                release.countDown();
            }
            assertThat(refreshing.get(5, TimeUnit.SECONDS).profile().login()).isEqualTo("hubot");
        }
        assertThat(service.cachedLogin()).contains("hubot");
        assertThat(service.cachedCredential().orElseThrow().token()).isEqualTo("rotated");
        service.deleteGitHubToken();
        assertThat(service.cachedLogin()).isEmpty();
        assertThat(service.cachedCredential()).isEmpty();
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", "");
    }
}
