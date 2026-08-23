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
package com.bytequay.app.web;

import com.bytequay.app.service.credentials.CredentialService;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static java.util.Objects.requireNonNull;

@RestController
public class CredentialController
{
    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
    }

    public record TokenRequest(String value) {}

    @GetMapping("/api/auth/github/configured")
    public Map<String, Boolean> accountConfigured()
    {
        return ImmutableMap.of("configured", credentialService.isGitHubAccountConfigured());
    }

    @PostMapping("/api/auth/github/token")
    public void saveToken(@RequestBody TokenRequest req)
    {
        requireNotBlank(req.value(), "value must not be blank");
        credentialService.saveGitHubToken(req.value());
    }

    @GetMapping("/api/auth/github/account")
    public AccountResponse account()
    {
        return new AccountResponse(
                credentialService.isGitHubAccountConfigured(),
                credentialService.maskedGitHubToken().orElse(null));
    }

    /** Full stored token for main-process clipboard copy; the renderer never displays it. */
    @GetMapping("/api/auth/github/token/value")
    public Map<String, String> tokenValue()
    {
        return ImmutableMap.of("value", credentialService.gitHubTokenValue()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "No GitHub token is stored.")));
    }

    @DeleteMapping("/api/auth/github/token")
    public void deleteToken()
    {
        credentialService.deleteGitHubToken();
    }

    public record AccountResponse(boolean configured, String maskedToken) {}

    @GetMapping("/api/auth/github/cli")
    public Map<String, Boolean> ghCliAvailable()
    {
        return ImmutableMap.of("available", credentialService.isGitHubCliAvailable());
    }

    @PostMapping("/api/auth/github/cli/import")
    public Map<String, String> importGhCliToken()
    {
        return ImmutableMap.of("login", credentialService.importGitHubCliToken());
    }
}
