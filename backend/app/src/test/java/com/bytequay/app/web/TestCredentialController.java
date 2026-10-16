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
package com.bytequay.app.web;

import com.bytequay.app.repository.github.GitHubNotificationClient;
import com.bytequay.app.repository.github.GitHubRateLimitException;
import com.bytequay.app.repository.github.GitHubRateLimitInterceptor;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.core.domain.UserProfile;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestCredentialController
{
    private final CredentialService credentials = mock(CredentialService.class);
    private final GitHubNotificationClient notifications = mock(GitHubNotificationClient.class);
    private final GitHubRateLimitInterceptor rateLimit = new GitHubRateLimitInterceptor(Clock.fixed(Instant.parse("2026-09-29T00:59:00Z"), ZoneOffset.UTC));

    @Test
    void syncStatusUsesOneCachedAccountAndDoesNotExposeItsCredential()
            throws Exception
    {
        String token = "secret-only-for-transport";
        when(credentials.cachedCredential()).thenReturn(Optional.of(new GitHubCredential(token,
                new UserProfile("octocat", "Private name", "", "", 0, 0, 0, "", "", "", "private@example.com"))));
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        Instant retryAt = Instant.parse("2026-09-29T01:00:00Z");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com").requestInterceptor(rateLimit);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/graphql"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Secondary rate limit exceeded\"}")
                        .header("X-RateLimit-Resource", "graphql").header("X-RateLimit-Limit", "5000")
                        .header("X-RateLimit-Remaining", "4900").header("X-RateLimit-Used", "100")
                        .header("X-RateLimit-Reset", Long.toString(retryAt.plusSeconds(3600).getEpochSecond()))
                        .header(HttpHeaders.RETRY_AFTER, "60"));
        assertThatThrownBy(() -> builder.build().post().uri("/graphql").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(Map.of("query", "fragment Summary on PullRequest { id }")).retrieve().body(String.class)).isInstanceOf(GitHubRateLimitException.class);

        String body = mvc().perform(get("/api/auth/github/sync-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.login").value("octocat"))
                .andExpect(jsonPath("$.usage.requests[0].count").value(1))
                .andExpect(jsonPath("$.usage.requests[0].status").value(429))
                .andExpect(jsonPath("$.usage.quotas[0].remaining").value(4900))
                .andExpect(jsonPath("$.usage.cooldowns.secondary").value(retryAt.toString()))
                .andExpect(jsonPath("$.usage.rateLimitedResponses").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(token, "Private name", "private@example.com");
        verify(credentials).cachedCredential();
        verify(credentials).isGitHubAccountConfigured();
        verifyNoMoreInteractions(credentials, notifications);
        server.verify();
    }

    @Test
    void coldAccountReportsUnavailableUsageWithoutResolvingCredentials()
            throws Exception
    {
        when(credentials.cachedCredential()).thenReturn(Optional.empty());
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);

        mvc().perform(get("/api/auth/github/sync-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.login").isEmpty())
                .andExpect(jsonPath("$.usage").isEmpty());
        verify(credentials).cachedCredential();
        verify(credentials).isGitHubAccountConfigured();
        verifyNoMoreInteractions(credentials);
        verifyNoInteractions(notifications);
    }

    private MockMvc mvc()
    {
        return standaloneSetup(new CredentialController(credentials, rateLimit))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
    }
}
