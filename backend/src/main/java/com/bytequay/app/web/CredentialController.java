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

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.credentials.CredentialService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
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

    public record UpsertRequest(
            String type,
            String name,
            String value,
            String label,
            String notes,
            String configJson)
    {}

    @GetMapping("/api/credentials/account/exists")
    public Map<String, Boolean> accountConfigured()
    {
        return Map.of("configured", credentialService.isGitHubAccountConfigured());
    }

    @PostMapping("/api/credentials")
    public Credential upsert(@RequestBody UpsertRequest req)
    {
        requireNotBlank(req.value(), "value must not be blank");
        requireNotBlank(req.name(), "name must not be blank");
        return credentialService.upsert(
                parseType(req.type()), req.name(), req.value(), req.label(), req.notes(), req.configJson());
    }

    private static CredentialType parseType(String value)
    {
        try {
            return CredentialType.valueOf(value.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "unknown credential type: " + value);
        }
    }
}
