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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
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
 *       cross-repo and account-scoped calls.</li>
 * </ol>
 * Throws 401 if no usable token is configured.
 */
@Component
public class PatResolver
{
    private final CredentialService credentialService;

    public PatResolver(CredentialService credentialService)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
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

        return credentialService.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .filter(value -> isNotBlank(value))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(401),
                        "GitHub PAT not configured. Add it in Settings → GitHub token."));
    }
}
