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
package com.bytequay.app.config;

import com.bytequay.app.repository.github.GitHubRateLimitInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebConfig
{
    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final String USER_AGENT = "bytequay -app";

    private static final String GITHUB_GRAPHQL_API_URL = "https://api.github.com/graphql";

    // Bound stuck GitHub calls caused by rate limits, dead connections, or
    // network blips. This stays below the frontend's one-minute request timeout.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    @Bean
    public RestClient gitHubRestClient(GitHubRateLimitInterceptor rateLimit)
    {
        return RestClient.builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader("Accept", GITHUB_ACCEPT)
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .defaultHeader("User-Agent", USER_AGENT)
                .requestInterceptor(rateLimit)
                .requestFactory(newTimeoutRequestFactory(
                        CONNECT_TIMEOUT,
                        READ_TIMEOUT,
                        HttpClient.Redirect.NEVER))
                .build();
    }

    @Bean
    public RestClient gitHubLogRestClient()
    {
        // The authenticated Actions endpoint returns a short-lived HTTPS URL.
        // This client follows storage redirects but has no GitHub defaults or
        // credentials, so the bearer token cannot cross the host boundary.
        return RestClient.builder()
                .requestFactory(newTimeoutRequestFactory(
                        CONNECT_TIMEOUT,
                        READ_TIMEOUT,
                        HttpClient.Redirect.NORMAL))
                .build();
    }

    @Bean
    public RestClient gitHubGraphQLRestClient()
    {
        // GitHub's GraphQL endpoint takes a single POST with
        // { query, variables }. We use it for review-thread resolution
        // state (REST doesn't expose it) and the resolve / unresolve
        // mutations. Same Accept + User-Agent contract as the REST
        // client; auth is per-request because the PAT differs per
        // call site.
        return RestClient.builder()
                .baseUrl(GITHUB_GRAPHQL_API_URL)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(newTimeoutRequestFactory(
                        CONNECT_TIMEOUT,
                        READ_TIMEOUT,
                        HttpClient.Redirect.NEVER))
                .build();
    }

    private static ClientHttpRequestFactory newTimeoutRequestFactory(
            Duration connect,
            Duration read,
            HttpClient.Redirect redirects)
    {
        // JdkClientHttpRequestFactory wraps the JDK 11+ HttpClient. We use it
        // instead of SimpleClientHttpRequestFactory because the latter is
        // backed by HttpURLConnection, whose setRequestMethod rejects PATCH —
        // breaking GitHub PR edits (PATCH /repos/{o}/{r}/pulls/{n}).
        //
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(redirects)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(read);
        return factory;
    }
}
