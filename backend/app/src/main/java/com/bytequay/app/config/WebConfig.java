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
    public RestClient gitHubRestClient()
    {
        return RestClient.builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader("Accept", GITHUB_ACCEPT)
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT))
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
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT))
                .build();
    }

    private static ClientHttpRequestFactory newTimeoutRequestFactory(Duration connect, Duration read)
    {
        // JdkClientHttpRequestFactory wraps the JDK 11+ HttpClient. We use it
        // instead of SimpleClientHttpRequestFactory because the latter is
        // backed by HttpURLConnection, whose setRequestMethod rejects PATCH —
        // breaking GitHub PR edits (PATCH /repos/{o}/{r}/pulls/{n}).
        //
        // followRedirects(NORMAL) is required for GitHub Actions log fetch:
        // /actions/jobs/{id}/logs always returns 302 → presigned blob URL,
        // and the JDK HttpClient default is NEVER (don't follow). Without
        // this the log endpoint silently returns an empty body and the
        // merge card permanently shows "No log available". NORMAL also
        // blocks HTTPS → HTTP downgrades, which is what we want.
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(read);
        return factory;
    }
}
