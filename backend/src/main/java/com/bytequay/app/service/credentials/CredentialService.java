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

import com.bytequay.app.repository.sqlite.SqliteCredentialStore;
import com.bytequay.app.service.github.GhCliService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static com.bytequay.app.utils.StringInputUtil.isNotBlank;
import static java.util.Objects.requireNonNull;

@Service
public class CredentialService
{
    static final String GITHUB_CLI_SOURCE = "{\"source\":\"gh-cli\"}";

    private final SqliteCredentialStore credentialStore;
    private final GhCliService ghCli;

    public CredentialService(SqliteCredentialStore credentialStore, GhCliService ghCli)
    {
        this.credentialStore = requireNonNull(credentialStore, "credentialStore is null");
        this.ghCli = requireNonNull(ghCli, "ghCli is null");
    }

    public void saveGitHubToken(String token)
    {
        credentialStore.save(token, null);
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

    public String importGitHubCliToken()
    {
        GhCliService.CliCredential credential = ghCli.readCredential();
        credentialStore.save(credential.token(), GITHUB_CLI_SOURCE);
        return credential.login();
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
}
