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
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static com.bytequay.app.utils.StringInputUtil.isNotBlank;
import static java.util.Objects.requireNonNull;

/**
 * Resolves the GitHub PAT for a backend GitHub call. The frontend no longer
 * passes a bearer token — every request resolves the PAT from the
 * credentials store:
 * <ol>
 *   <li>(REPO, {@code "owner/repo"}) — when a repo slug is provided and a
 *       per-repo token exists.</li>
 *   <li>(ACCOUNT, {@code "github"}) — the account-level token, used for
 *       cross-repo and account-scoped calls. When that slot was filled
 *       from the GitHub CLI, gh's live token wins over the stored copy.</li>
 * </ol>
 * Throws 401 if no usable token is configured.
 */
@Component
public class PatResolver
{
    private final CredentialService credentialService;
    private final GhCliService ghCli;

    public PatResolver(CredentialService credentialService, GhCliService ghCli)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.ghCli = requireNonNull(ghCli, "ghCli is null");
    }

    /** Resolves the account-level PAT (no per-repo override considered). */
    public String resolve()
    {
        return resolve(null);
    }

    /**
     * Resolves a PAT for a repo-scoped fetch. When {@code repoFullName} is
     * non-null and a (REPO, repoFullName) credential exists, that token is
     * preferred over the account-level token.
     */
    public String resolve(String repoFullName)
    {
        if (isNotBlank(repoFullName)) {
            Optional<String> repoToken = credentialService.getSecret(CredentialType.REPO, repoFullName)
                    .filter(value -> isNotBlank(value));
            if (repoToken.isPresent()) {
                return repoToken.orElseThrow();
            }
        }

        return liveGhCliToken()
                .or(this::storedAccountToken)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(401),
                        "GitHub PAT not configured. Add it in Settings → GitHub token."));
    }

    /**
     * When the account slot was filled from the GitHub CLI, the stored value
     * is only a copy — gh owns the real one. Reading gh's live token (cached
     * by {@link GhCliService}) means a {@code gh auth login} rotation heals
     * itself instead of 401-ing until the user re-imports.
     *
     * <p>Empty for PAT- and OAuth-sourced slots, and when gh has since been
     * uninstalled or logged out; both fall through to the stored copy.
     */
    private Optional<String> liveGhCliToken()
    {
        return credentialService.get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .map(Credential::configJson)
                .filter(GhCliService.SOURCE_MARKER::equals)
                .flatMap(marker -> ghCli.currentToken())
                .filter(value -> isNotBlank(value));
    }

    private Optional<String> storedAccountToken()
    {
        return credentialService.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .filter(value -> isNotBlank(value));
    }
}
