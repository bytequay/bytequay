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

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.sqlite.SqliteCredentialStore;
import com.bytequay.app.service.github.GhCliService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.utils.StringInputUtil.isNotBlank;
import static java.util.Objects.requireNonNull;

@Service
public class CredentialService
{
    public static final String GITHUB_ACCOUNT_NAME = "github";
    private static final String GITHUB_CLI_SOURCE_MARKER = "{\"source\":\"gh-cli\"}";

    private final SqliteCredentialStore credentialStore;
    private final GhCliService ghCli;

    public CredentialService(SqliteCredentialStore credentialStore, GhCliService ghCli)
    {
        this.credentialStore = requireNonNull(credentialStore, "credentialStore is null");
        this.ghCli = requireNonNull(ghCli, "ghCli is null");
    }

    public Optional<Credential> get(CredentialType type, String name)
    {
        return credentialStore.find(type, name);
    }

    public Optional<String> getSecret(CredentialType type, String name)
    {
        return credentialStore.getSecret(type, name);
    }

    public Credential upsert(
            CredentialType type,
            String name,
            String rawValue,
            String label,
            String notes)
    {
        return upsert(type, name, rawValue, label, notes, null);
    }

    public Credential upsert(
            CredentialType type,
            String name,
            String rawValue,
            String label,
            String notes,
            String configJson)
    {
        return credentialStore.upsert(type, name, rawValue, label, notes, configJson);
    }

    public void delete(CredentialType type, String name)
    {
        credentialStore.delete(type, name);
    }

    public boolean isGitHubAccountConfigured()
    {
        return get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME).isPresent();
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

    public String importGitHubCliToken()
    {
        GhCliService.CliCredential credential = ghCli.readCredential();
        upsert(
                CredentialType.ACCOUNT,
                GITHUB_ACCOUNT_NAME,
                credential.token(),
                credential.login(),
                "Imported from the GitHub CLI on " + Instant.now(),
                GITHUB_CLI_SOURCE_MARKER);
        return credential.login();
    }

    public boolean isGitHubCliAvailable()
    {
        return ghCli.isAvailable();
    }

    private Optional<String> liveGhCliToken()
    {
        return get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .map(Credential::configJson)
                .filter(GITHUB_CLI_SOURCE_MARKER::equals)
                .flatMap(marker -> ghCli.currentToken())
                .filter(value -> isNotBlank(value));
    }

    private Optional<String> storedGitHubToken()
    {
        return getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .filter(value -> isNotBlank(value));
    }
}
