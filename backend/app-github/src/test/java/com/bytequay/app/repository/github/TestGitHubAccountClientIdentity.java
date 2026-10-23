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
package com.bytequay.app.repository.github;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubAccountClientIdentity
{
    @Test
    void readsStableAccountAndMemberIdentitiesWithoutChangingProfileContract()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubAccountClient client = new GitHubAccountClient(builder.build(), RestClient.create());
        server.expect(requestTo("https://api.github.test/user")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":1,\"login\":\"alice\",\"type\":\"User\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/users/bob")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":42,\"login\":\"bob\",\"name\":\"Bob\",\"avatar_url\":\"https://avatars.githubusercontent.com/u/42\",\"type\":\"User\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/users/acme")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":2,\"login\":\"acme\",\"type\":\"Organization\"}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchUserIdentity("token", Optional.empty()).id()).isEqualTo(1);
        assertThat(client.fetchUserIdentity("token", Optional.of("bob")))
                .isEqualTo(new GitHubAccountClient.UserIdentity(42, "bob", Optional.of("Bob"), Optional.of("https://avatars.githubusercontent.com/u/42")));
        assertThatThrownBy(() -> client.fetchUserIdentity("token", Optional.of("acme"))).isInstanceOf(ResponseStatusException.class);
        server.verify();
    }
}
