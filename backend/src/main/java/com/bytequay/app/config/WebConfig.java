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

import com.bytequay.app.repository.github.GitHubOrgAccess;
import com.bytequay.app.repository.github.GitHubOrgAccessInterceptor;
import com.bytequay.app.repository.github.GitHubRateLimitInterceptor;
import com.bytequay.app.repository.github.GitHubRateLimitMonitor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebConfig
        implements WebMvcConfigurer
{
    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final String USER_AGENT = "bytequay -app";

    private static final String ANTHROPIC_API_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEEPSEEK_API_BASE_URL = "https://api.deepseek.com";
    // The default local-server endpoint matches ds4-server's
    // out-of-the-box port. Override per environment by changing the
    // ds4.port setting; for the singleton RestClient bean we ship the
    // default and let a future config-aware variant land if needed
    // (the lifecycle service tracks the live port in its config).
    private static final String DEEPSEEK_LOCAL_BASE_URL = "http://127.0.0.1:8000";
    private static final String OPENAI_API_BASE_URL = "https://api.openai.com/v1";
    private static final String GITHUB_GRAPHQL_API_URL = "https://api.github.com/graphql";

    // Outbound-HTTP timeouts. Without these a stuck GitHub call (rate-limited,
    // TCP dead connection, network blip) ties up a Tomcat worker until Node's
    // undici kills the frontend fetch with HeadersTimeoutError after 5 min.
    // 15s connect + 45s read means a PR-detail orchestration (5 parallel GH
    // calls) has a bounded worst case of ~45s, well under the fetch ceiling.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    @Override
    public void addCorsMappings(CorsRegistry registry)
    {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "file://*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }

    @Bean
    public GitHubRateLimitMonitor gitHubRateLimitMonitor()
    {
        return new GitHubRateLimitMonitor();
    }

    @Bean
    public GitHubRateLimitInterceptor gitHubRateLimitInterceptor(GitHubRateLimitMonitor monitor)
    {
        return new GitHubRateLimitInterceptor(monitor);
    }

    @Bean
    public GitHubOrgAccess gitHubOrgAccess()
    {
        return new GitHubOrgAccess();
    }

    @Bean
    public GitHubOrgAccessInterceptor gitHubOrgAccessInterceptor(GitHubOrgAccess orgAccess)
    {
        return new GitHubOrgAccessInterceptor(orgAccess);
    }

    @Bean
    public RestClient gitHubRestClient(
            GitHubRateLimitInterceptor rateLimitInterceptor,
            GitHubOrgAccessInterceptor orgAccessInterceptor)
    {
        return RestClient.builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader("Accept", GITHUB_ACCEPT)
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .defaultHeader("User-Agent", USER_AGENT)
                .requestInterceptor(rateLimitInterceptor)
                .requestInterceptor(orgAccessInterceptor)
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT))
                .build();
    }

    @Bean
    public RestClient anthropicRestClient()
    {
        // Anthropic streams back model output; allow a longer read timeout.
        return RestClient.builder()
                .baseUrl(ANTHROPIC_API_BASE_URL)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, Duration.ofMinutes(2)))
                .build();
    }

    @Bean
    public RestClient deepseekRestClient()
    {
        // DeepSeek's cloud REST surface is OpenAI-compatible (chat
        // completions). Same long read window as Anthropic — model
        // responses regularly run past the 45s GitHub-side default.
        return RestClient.builder()
                .baseUrl(DEEPSEEK_API_BASE_URL)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, Duration.ofMinutes(2)))
                .build();
    }

    @Bean
    public RestClient deepseekLocalRestClient()
    {
        // Locally-served DeepSeek model variant routed through the
        // ds4 subprocess. Same Chat-Completions request shape as the
        // cloud client; only the base URL and credential strategy
        // differ. Read timeout is longer than the cloud window because
        // local inference on a cold KV cache regularly runs 60+ s for
        // the first token.
        return RestClient.builder()
                .baseUrl(DEEPSEEK_LOCAL_BASE_URL)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, Duration.ofMinutes(5)))
                .build();
    }

    @Bean
    public RestClient gitHubGraphQLRestClient(
            GitHubRateLimitInterceptor rateLimitInterceptor,
            GitHubOrgAccessInterceptor orgAccessInterceptor)
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
                .requestInterceptor(rateLimitInterceptor)
                .requestInterceptor(orgAccessInterceptor)
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT))
                .build();
    }

    @Bean
    public RestClient openAiRestClient()
    {
        // OpenAI chat-completions API — request shape matches DeepSeek
        // (DeepSeek's surface was modelled after OpenAI's), so the
        // OpenAiReviewer can reuse the same DTOs.
        return RestClient.builder()
                .baseUrl(OPENAI_API_BASE_URL)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(newTimeoutRequestFactory(CONNECT_TIMEOUT, Duration.ofMinutes(2)))
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
