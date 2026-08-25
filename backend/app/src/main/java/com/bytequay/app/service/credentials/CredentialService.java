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
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.utils.StringInputUtil.isNotBlank;
import static java.util.Objects.requireNonNull;

@Service
public class CredentialService
{
    static final String GITHUB_CLI_SOURCE = "{\"source\":\"gh-cli\"}";

    private final CredentialStore credentialStore;
    private final WorkspaceCredentialStore workspaceCredentials;
    private final GhCliService ghCli;
    private final GitHubAccountClient gitHubAccount;
    private GitHubCredential cachedGitHubCredential;

    public CredentialService(
            CredentialStore credentialStore,
            WorkspaceCredentialStore workspaceCredentials,
            GhCliService ghCli,
            GitHubAccountClient gitHubAccount)
    {
        this.credentialStore = requireNonNull(credentialStore, "credentialStore is null");
        this.workspaceCredentials = requireNonNull(workspaceCredentials, "workspaceCredentials is null");
        this.ghCli = requireNonNull(ghCli, "ghCli is null");
        this.gitHubAccount = requireNonNull(gitHubAccount, "gitHubAccount is null");
    }

    public synchronized void saveGitHubToken(String token)
    {
        String trimmed = requireNonNull(token, "token is null").trim();
        if (!isNotBlank(trimmed)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422), "Token must not be blank.");
        }
        UserProfile profile = gitHubAccount.fetchUserProfile(trimmed);
        credentialStore.save(trimmed, null);
        cachedGitHubCredential = new GitHubCredential(trimmed, profile);
    }

    public boolean isGitHubAccountConfigured()
    {
        return credentialStore.exists();
    }

    /**
     * Returns the current GitHub account token. Credentials imported from
     * {@code gh} track its live token and fall back to the stored copy when
     * the CLI is unavailable; pasted PATs always use the stored value.
     */
    public String resolveGitHubToken()
    {
        return liveGhCliToken()
                .or(this::storedGitHubToken)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(401),
                        "GitHub PAT not configured. Add it in Settings → GitHub token."));
    }

    public synchronized GitHubCredential resolveGitHubCredential()
    {
        String token = resolveGitHubToken();
        if (cachedGitHubCredential == null || !cachedGitHubCredential.token().equals(token)) {
            cachedGitHubCredential = new GitHubCredential(token, gitHubAccount.fetchUserProfile(token));
        }
        return cachedGitHubCredential;
    }

    /**
     * Returns the token used for one workspace's Git and GitHub traffic: the
     * workspace override when one is stored, otherwise the global account token.
     */
    // ponytail: the override authenticates git transport (clone/fetch/push);
    // GitHub API clients still resolve the global token until they carry a
    // workspace scope.
    public String resolveGitHubToken(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return workspaceCredentials.getSecret(workspaceId)
                .filter(value -> isNotBlank(value))
                .orElseGet(this::resolveGitHubToken);
    }

    /** Git environment that authenticates HTTPS remotes with one workspace's token. */
    public Map<String, String> gitAuthenticationEnvironment(
            String workspaceId,
            Collection<String> remoteUrls)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(remoteUrls, "remoteUrls is null");
        Set<String> bases = new LinkedHashSet<>();
        remoteUrls.forEach(url -> bases.add(authenticationBase(url)));
        if (bases.isEmpty()) {
            throw new IllegalArgumentException("remoteUrls is empty");
        }
        String header = "Authorization: Basic " + Base64.getEncoder().encodeToString(
                ("x-access-token:" + resolveGitHubToken(workspaceId)).getBytes(StandardCharsets.UTF_8));
        ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();
        environment.put("GIT_CONFIG_COUNT", Integer.toString(bases.size()));
        int index = 0;
        for (String base : bases) {
            environment.put("GIT_CONFIG_KEY_" + index, "http." + base + ".extraHeader");
            environment.put("GIT_CONFIG_VALUE_" + index, header);
            index++;
        }
        return environment.buildOrThrow();
    }

    /** Masked display form of the stored account token; never the full value. */
    public Optional<String> maskedGitHubToken()
    {
        return storedGitHubToken().map(SecretMasker::mask);
    }

    /** The full stored account token for clipboard copy; the UI never renders it. */
    public Optional<String> gitHubTokenValue()
    {
        return credentialStore.exists() ? Optional.of(resolveGitHubToken()) : Optional.empty();
    }

    public synchronized void deleteGitHubToken()
    {
        credentialStore.delete();
        cachedGitHubCredential = null;
    }

    /**
     * Validates the token against GitHub, records the account it authenticates
     * as, and stores it as this workspace's override.
     *
     * @return the login the token authenticates as
     */
    public String saveWorkspaceGitHubToken(String workspaceId, String token)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        if (!isNotBlank(token)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422), "Token must not be blank.");
        }
        String login = gitHubAccount.fetchUserProfile(token.trim()).login();
        workspaceCredentials.save(workspaceId, token.trim(), login);
        return login;
    }

    public Optional<WorkspaceToken> workspaceGitHubToken(String workspaceId)
    {
        return workspaceCredentials.getSecret(workspaceId)
                .map(secret -> new WorkspaceToken(
                        SecretMasker.mask(secret),
                        workspaceCredentials.getLogin(workspaceId).orElse("")));
    }

    public Optional<String> workspaceGitHubTokenValue(String workspaceId)
    {
        return workspaceCredentials.getSecret(workspaceId);
    }

    public void deleteWorkspaceGitHubToken(String workspaceId)
    {
        workspaceCredentials.delete(workspaceId);
    }

    public record WorkspaceToken(String maskedToken, String login) {}

    public synchronized String importGitHubCliToken()
    {
        GhCliService.CliCredential credential = ghCli.readCredential();
        UserProfile profile = gitHubAccount.fetchUserProfile(credential.token());
        credentialStore.save(credential.token(), GITHUB_CLI_SOURCE);
        cachedGitHubCredential = new GitHubCredential(credential.token(), profile);
        return profile.login();
    }

    public boolean isGitHubCliAvailable()
    {
        return ghCli.isAvailable();
    }

    private Optional<String> liveGhCliToken()
    {
        return credentialStore.source()
                .filter(GITHUB_CLI_SOURCE::equals)
                .flatMap(marker -> ghCli.currentToken())
                .filter(value -> isNotBlank(value));
    }

    private Optional<String> storedGitHubToken()
    {
        return credentialStore.getSecret()
                .filter(value -> isNotBlank(value));
    }

    public record GitHubCredential(String token, UserProfile profile)
    {
        public GitHubCredential
        {
            requireNonNull(token, "token is null");
            requireNonNull(profile, "profile is null");
        }
    }

    private static String authenticationBase(String remoteUrl)
    {
        URI uri = URI.create(requireNonNull(remoteUrl, "remoteUrl is null"));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("unsafe remote URL");
        }
        return uri.getScheme().toLowerCase() + "://" + uri.getAuthority().toLowerCase() + "/";
    }
}
