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
import com.bytequay.app.service.hosting.HostingConnection;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.hosting.HostingCredential.Source;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.git.command.RepositoryUrls.isPlainHttps;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The app's main GitHub sign-in: one connected github.com hosting account, saved with a token or the GitHub CLI, that one setting names. */
@Service
public class CredentialService
{
    public static final HostingServer GITHUB_SERVER = HostingServer.GITHUB_COM;
    /** The id of the connected GitHub account that is the app's main sign-in. */
    public static final String GITHUB_ACCOUNT_SETTING = "github.account";

    private final AppSettingsStore settings;
    private final GhCliService ghCli;
    private final GitHubAccountClient gitHubAccount;
    private final HostingConnectionService hosting;
    private final WorkspaceStore workspaces;
    private volatile GitHubCredential cachedGitHubCredential;
    private volatile Optional<HostingReadSession> cachedHostingRead = Optional.empty();

    public CredentialService(AppSettingsStore settings, GhCliService ghCli,
            GitHubAccountClient gitHubAccount, HostingConnectionService hosting, WorkspaceStore workspaces)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.ghCli = requireNonNull(ghCli, "ghCli is null");
        this.gitHubAccount = requireNonNull(gitHubAccount, "gitHubAccount is null");
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    public synchronized void saveGitHubToken(String token)
    {
        String trimmed = requireNonNull(token, "token is null").trim();
        if (trimmed.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422), "Token must not be blank.");
        }
        UserProfile profile = gitHubAccount.fetchUserProfile(trimmed);
        signIn(hosting.connect(GITHUB_SERVER.provider(), GITHUB_SERVER.webBaseUrl(), GITHUB_SERVER.apiBaseUrl(), trimmed), new GitHubCredential(trimmed, profile));
    }

    public boolean isGitHubAccountConfigured()
    {
        return account().flatMap(hosting::credentialSource).isPresent();
    }

    /** Returns the current GitHub account token: {@code gh}'s live token for an account connected through it, else the pasted PAT. */
    public String resolveGitHubToken()
    {
        return captureHostingRead().access().token();
    }

    public HostingReadSession captureHostingRead()
    {
        long accountId = account().filter(id -> hosting.credentialSource(id).isPresent())
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(401), "GitHub PAT not configured. Add it in Settings → GitHub token."));
        HostingReadSession session = hosting.readSession(accountId);
        cachedHostingRead = Optional.of(session);
        return session;
    }

    /** The desktop sign-in's last verified session, without CLI or network work; a token change shows once a session is captured again. */
    public Optional<HostingReadSession> cachedHostingRead()
    {
        return isGitHubAccountConfigured() ? cachedHostingRead : Optional.empty();
    }

    /**
     * The signed-in login when one is already cached, without contacting
     * GitHub. Read paths that must answer offline use this instead of
     * {@link #resolveGitHubCredential()}, which fetches the profile on a cold
     * cache. Reading this snapshot must not wait for CLI or network work in a
     * synchronized credential update.
     */
    public Optional<String> cachedLogin()
    {
        return Optional.ofNullable(cachedGitHubCredential).map(credential -> credential.profile().login());
    }

    /** One coherent account snapshot for local diagnostics, without CLI or network work. */
    public Optional<GitHubCredential> cachedCredential()
    {
        return Optional.ofNullable(cachedGitHubCredential);
    }

    public synchronized GitHubCredential resolveGitHubCredential()
    {
        String token = resolveGitHubToken();
        if (cachedGitHubCredential == null || !cachedGitHubCredential.token().equals(token)) {
            cachedGitHubCredential = new GitHubCredential(token, gitHubAccount.fetchUserProfile(token));
        }
        return cachedGitHubCredential;
    }

    /** Git credentials of the workspace's bound account stay within that account's server and configured path. */
    public Map<String, String> gitAuthenticationEnvironment(String workspaceId, Collection<String> remoteUrls)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(remoteUrls, "remoteUrls is null");
        checkArgument(!remoteUrls.isEmpty(), "remoteUrls is empty");
        HostingReadSession selected = workspaces.findHostingBinding(workspaceId).map(binding -> hosting.readSession(binding.accountId()))
                .orElseThrow(() -> new HostingFailure(AUTHENTICATION_REQUIRED, "Choose the account this workspace uses in its settings."));
        String base = selected.access().server().webBaseUrl().toASCIIString() + "/";
        boolean matches = false;
        for (String remoteUrl : remoteUrls) {
            URI uri = URI.create(requireNonNull(remoteUrl, "remoteUrl is null"));
            checkArgument(isPlainHttps(uri), "unsafe remote URL");
            matches |= HostingUrls.canonicalHttpUrl(uri).toASCIIString().startsWith(base);
        }
        if (!matches) {
            return ImmutableMap.of("GIT_CONFIG_COUNT", "0");
        }
        String credential = selected.access().server().provider().gitUser() + ":" + selected.access().token();
        String header = "Authorization: Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
        return ImmutableMap.of(
                "GIT_CONFIG_COUNT", "2",
                "GIT_CONFIG_KEY_0", "http." + base + ".extraHeader",
                "GIT_CONFIG_VALUE_0", header,
                "GIT_CONFIG_KEY_1", "http.followRedirects",
                "GIT_CONFIG_VALUE_1", "false");
    }

    /** Masked display form of the account token; never the full value. */
    public Optional<String> maskedGitHubToken()
    {
        return gitHubTokenValue().map(SecretMasker::mask);
    }

    /**
     * Where the account token comes from, so the settings page can name it
     * instead of assuming a personal access token.
     */
    public GitHubTokenSource gitHubTokenSource()
    {
        return account().flatMap(hosting::credentialSource).filter(Source.CLI::equals).isPresent() ? GitHubTokenSource.GITHUB_CLI : GitHubTokenSource.PERSONAL_ACCESS_TOKEN;
    }

    /** The full account token for clipboard copy; the UI never renders it. */
    public Optional<String> gitHubTokenValue()
    {
        return isGitHubAccountConfigured() ? Optional.of(resolveGitHubToken()) : Optional.empty();
    }

    /** Signs out: the account stops being connected, and nothing names a main GitHub account. */
    public synchronized void deleteGitHubToken()
    {
        account().ifPresent(hosting::disconnect);
        settings.delete(GITHUB_ACCOUNT_SETTING);
        cachedGitHubCredential = null;
        cachedHostingRead = Optional.empty();
    }

    public synchronized String importGitHubCliToken()
    {
        HostingConnection connection = hosting.connectCli(GITHUB_SERVER.provider(), GITHUB_SERVER.webBaseUrl(), GITHUB_SERVER.apiBaseUrl());
        String token = hosting.readSession(connection.account().id()).access().token();
        UserProfile profile = gitHubAccount.fetchUserProfile(token);
        signIn(connection, new GitHubCredential(token, profile));
        return profile.login();
    }

    private void signIn(HostingConnection connection, GitHubCredential credential)
    {
        settings.put(GITHUB_ACCOUNT_SETTING, Long.toString(connection.account().id()));
        cachedGitHubCredential = credential;
        cachedHostingRead = Optional.empty();
    }

    /** The connected GitHub account that is the app's main sign-in. */
    private Optional<Long> account()
    {
        return settings.get(GITHUB_ACCOUNT_SETTING).map(Long::parseLong);
    }

    public boolean isGitHubCliAvailable()
    {
        return ghCli.findBinary().isPresent();
    }

    public record GitHubCredential(String token, UserProfile profile)
    {
        public GitHubCredential
        {
            requireNonNull(token, "token is null");
            requireNonNull(profile, "profile is null");
        }

        @Override
        public String toString()
        {
            return "GitHubCredential[token=<redacted>, profile=" + profile + "]";
        }
    }
}
