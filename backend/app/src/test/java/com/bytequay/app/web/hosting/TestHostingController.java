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
package com.bytequay.app.web.hosting;

import com.bytequay.app.agent.runtime.AgentProperties;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.service.hosting.HostingConnection;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.AgentRequestFilter;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.app.web.agent.AgentController.SESSION_HEADER;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestHostingController
{
    private static final String SESSION = "desktop-session";
    private static final String TOKEN = "secret-token-for-provider-only";
    private static final URI WEB = URI.create("https://gitlab.company.test/team");
    private static final URI API = URI.create("https://gitlab.company.test/team/api/v4");
    private static final HostingInstance INSTANCE = new HostingInstance(3, GITLAB, WEB, API);
    private static final HostingAccount ACCOUNT = new HostingAccount(7, 3, "500", "alice");
    private static final String CONNECT_BODY = """
            {"provider":"GITLAB",
             "webBaseUrl":"https://gitlab.company.test/team",
             "apiBaseUrl":"https://gitlab.company.test/team/api/v4",
             "token":"secret-token-for-provider-only"}
            """;

    private final HostingConnectionService connections = mock(HostingConnectionService.class);
    private final HostingPullRequestService requests = mock(HostingPullRequestService.class);

    @ParameterizedTest
    @CsvSource({
            "GET,/api/hosting/accounts",
            "POST,/api/hosting/accounts",
            "DELETE,/api/hosting/accounts/7/credential",
            "POST,/api/hosting/accounts/7/repositories/resolve",
            "GET,/api/hosting/accounts/7/repositories/11/requests/17",
            "GET,/api/hosting/accounts/7/repositories/11/requests/17/cached"})
    void protectsEveryHostingEndpointWithTheDesktopSession(String method, String path)
            throws Exception
    {
        MockMvc mvc = mvc(connections);
        HttpMethod httpMethod = HttpMethod.valueOf(method);

        mvc.perform(request(httpMethod, path).contentType(MediaType.APPLICATION_JSON).content(CONNECT_BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(httpMethod, path).header(SESSION_HEADER, "wrong-session").contentType(MediaType.APPLICATION_JSON).content(CONNECT_BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(httpMethod, path).header(SESSION_HEADER, SESSION).header("Origin", "https://example.com")
                        .contentType(MediaType.APPLICATION_JSON).content(CONNECT_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(connections, requests);
    }

    @Test
    void rejectsRequestsAddressedToAnotherHostEvenWithAValidSession()
            throws Exception
    {
        mvc(connections).perform(get("/api/hosting/accounts").header(SESSION_HEADER, SESSION).with(request -> {
            request.setServerName("rebound.example.com");
            return request;
        })).andExpect(status().isForbidden());

        verifyNoInteractions(connections, requests);
    }

    @Test
    void connectsWithNormalizedIdentityWithoutReturningTheToken()
            throws Exception
    {
        when(connections.connect(GITLAB, WEB, API, TOKEN)).thenReturn(new HostingConnection(ACCOUNT, INSTANCE, true));

        String body = mvc(connections).perform(post("/api/hosting/accounts").header(SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON).content(CONNECT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.account.id").value(7))
                .andExpect(jsonPath("$.account.instanceId").value(3))
                .andExpect(jsonPath("$.account.remoteUserId").value("500"))
                .andExpect(jsonPath("$.account.login").value("alice"))
                .andExpect(jsonPath("$.instance.provider").value("GITLAB"))
                .andExpect(jsonPath("$.instance.webBaseUrl").value(WEB.toString()))
                .andExpect(jsonPath("$.instance.apiBaseUrl").value(API.toString()))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TOKEN, SESSION, "secret", "credential");
        verify(connections).connect(GITLAB, WEB, API, TOKEN);
        verifyNoMoreInteractions(connections);
    }

    @Test
    void listsRetainedDisconnectedAccountsWithoutCredentials()
            throws Exception
    {
        when(connections.connections()).thenReturn(ImmutableList.of(new HostingConnection(ACCOUNT, INSTANCE, false)));

        String body = mvc(connections).perform(get("/api/hosting/accounts").header(SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account.id").value(7))
                .andExpect(jsonPath("$[0].connected").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TOKEN, SESSION, "token");
        verify(connections).connections();
        verifyNoMoreInteractions(connections);
    }

    @Test
    void returnsSharedIdentityAndSelectedAccountMetadata()
            throws Exception
    {
        String url = WEB + "/group/project/-/merge_requests/17";
        FetchedPullRequest summary = new FetchedPullRequest(99L, "group/project", 17, "Change", "alice", url, Instant.EPOCH, Instant.EPOCH, PullRequest.Origin.OTHER,
                ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), null, null, "topic");
        PrRawDetail detail = new PrRawDetail("Change", url, "open", "alice", null, Instant.EPOCH, Instant.EPOCH, null, "Account-specific details", ImmutableList.of(),
                ImmutableMap.of(), ImmutableList.of(), null, false, null, null, -1, -1, -1, -1, ImmutableList.of(), "head", "topic", "main", "base");
        when(requests.refresh(7, 11, 17)).thenReturn(new HostingPullRequestSnapshot(new HostingPullRequest(50, 11, 17, "99"), 7, HostingPullRequestSnapshot.CONNECTED_ACCOUNT, summary, detail,
                Optional.of("etag"), Instant.EPOCH));
        String body = mvc(connections).perform(get("/api/hosting/accounts/7/repositories/11/requests/17").header(SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.id").value(50))
                .andExpect(jsonPath("$.request.repositoryId").value(11))
                .andExpect(jsonPath("$.accountId").value(7))
                .andExpect(jsonPath("$.detail.body").value("Account-specific details"))
                .andExpect(jsonPath("$.detail.additions").value(-1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(TOKEN, SESSION);
        verifyNoInteractions(connections);
    }

    @Test
    void readsOnlyTheSelectedAccountCache()
            throws Exception
    {
        when(requests.cached(7, 11, 17)).thenReturn(Optional.empty());
        mvc(connections).perform(get("/api/hosting/accounts/7/repositories/11/requests/17/cached").header(SESSION_HEADER, SESSION))
                .andExpect(status().isOk());
        verify(requests).cached(7, 11, 17);
        verifyNoInteractions(connections);
    }

    @ParameterizedTest
    @CsvSource({"AUTHENTICATION_REQUIRED,401", "FORBIDDEN,403", "MISSING_OR_INACCESSIBLE,404", "RATE_LIMITED,429",
            "REVISION_CONFLICT,409", "UNAVAILABLE,503", "INVALID_RESPONSE,502"})
    void preservesTypedRemoteFailuresAndRetryInformation(HostingFailure.Kind kind, int expectedStatus)
            throws Exception
    {
        Optional<Duration> retryAfter = kind == HostingFailure.Kind.RATE_LIMITED ? Optional.of(Duration.ofSeconds(37)) : Optional.empty();
        when(requests.refresh(7, 11, 17)).thenThrow(new HostingFailure(kind, "Remote operation could not complete", retryAfter));

        var result = mvc(connections).perform(get("/api/hosting/accounts/7/repositories/11/requests/17").header(SESSION_HEADER, SESSION))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.kind").value(kind.name()))
                .andExpect(jsonPath("$.detail").value("Remote operation could not complete"));
        if (retryAfter.isPresent()) {
            result.andExpect(jsonPath("$.retryAfterSeconds").value(37));
        }
        else {
            result.andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());
        }
        assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain(TOKEN, SESSION);
        verify(requests).refresh(7, 11, 17);
        verifyNoMoreInteractions(connections);
    }

    @ParameterizedTest
    @MethodSource("malformedConnections")
    void rejectsMalformedConnectRequestsBeforeCallingTheService(String body)
            throws Exception
    {
        String response = mvc(connections).perform(post("/api/hosting/accounts").header(SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(TOKEN, SESSION);
        verifyNoInteractions(connections, requests);
    }

    @Test
    void returnsASafeBadRequestForInvalidConnectionCoordinates()
            throws Exception
    {
        when(connections.connect(GITLAB, WEB, API, TOKEN)).thenThrow(new IllegalArgumentException("Internal validation included " + TOKEN));

        String body = mvc(connections).perform(post("/api/hosting/accounts").header(SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON).content(CONNECT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid hosting connection or resource coordinates"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TOKEN, "Internal validation");
    }

    @Test
    void resolvesTheRepositoryForTheSelectedAccountWithItsNestedPath()
            throws Exception
    {
        HostingRepository repository = new HostingRepository(11, 3, "42", "group/subgroup/repo", URI.create(WEB + "/group/subgroup/repo"),
                URI.create(WEB + "/group/subgroup/repo.git"), Optional.empty(), Optional.of("main"));
        when(connections.resolveRepository(7, "group/subgroup/repo")).thenReturn(repository);

        mvc(connections).perform(post("/api/hosting/accounts/7/repositories/resolve").header(SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullPath\":\"group/subgroup/repo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.remoteId").value("42"))
                .andExpect(jsonPath("$.fullPath").value("group/subgroup/repo"))
                .andExpect(jsonPath("$.defaultBranch").value("main"));

        verify(connections).resolveRepository(7, "group/subgroup/repo");
        verifyNoMoreInteractions(connections);
    }

    @Test
    void disconnectsOnlyTheSelectedCredentialAndRetainsItsIdentity()
            throws Exception
    {
        HostingIdentityStore identities = mock(HostingIdentityStore.class);
        HostingCredentialStore credentials = mock(HostingCredentialStore.class);
        HostingConnectionService service = new HostingConnectionService(identities, credentials, ImmutableList.of());

        mvc(service).perform(delete("/api/hosting/accounts/7/credential").header(SESSION_HEADER, SESSION))
                .andExpect(status().isOk());

        verify(credentials).delete(7);
        verifyNoMoreInteractions(identities, credentials);
    }

    private static Stream<String> malformedConnections()
    {
        return Stream.of("{", "{}", "null", CONNECT_BODY.replace("GITLAB", "OTHER"), CONNECT_BODY.replace(TOKEN, ""),
                CONNECT_BODY.replace("\"provider\":\"GITLAB\",", ""), CONNECT_BODY.replace("https://gitlab.company.test/team/api/v4", "invalid URI"));
    }

    private MockMvc mvc(HostingConnectionService service)
    {
        AgentProperties properties = new AgentProperties(
                Path.of("/tmp/agent-runtime"), Duration.ofMinutes(30), SESSION, "codex", "0.153.4", Path.of("/tmp/agent-codex-home"), "claude",
                Path.of("/tmp/agent-claude-home"));
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().modules(new Jdk8Module(), new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        return standaloneSetup(new HostingController(service, requests))
                .setControllerAdvice(new GlobalExceptionHandler(), new HostingExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new AgentRequestFilter(properties, mapper, new AgentServingGate()))
                .build();
    }
}
