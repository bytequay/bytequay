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
import com.bytequay.app.service.CredentialService;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.bytequay.app.repository.CredentialStore.DEFAULT_INSTANCE_NAME;
import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static java.util.Objects.requireNonNull;

/**
 * REST endpoints for the Credentials vault. Raw secrets are never returned;
 * only display-safe metadata (type, name, instance name, label, masked
 * preview, timestamps).
 *
 * <p>Each credential is identified by the triple (type, name, instanceName).
 * {@code instanceName} defaults to {@code "default api"} when the request
 * omits it, so single-instance callers don't need to think about it.
 */
@RestController
public class CredentialController
{
    private static final String DELETED = "deleted";

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
    }

    public record UpsertRequest(
            String type,
            String name,
            String instanceName,
            String value,
            String label,
            String notes)
    {}

    /**
     * GET /api/credentials/account/exists — lightweight existence probe used
     * by the frontend's first-run gate. Returns {@code {configured: true}}
     * iff at least one ACCOUNT/github credential is stored.
     */
    @GetMapping("/api/credentials/account/exists")
    public Map<String, Boolean> accountConfigured()
    {
        boolean configured = credentialService.get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME).isPresent();
        return ImmutableMap.of("configured", configured);
    }

    /**
     * GET /api/credentials — list all stored credentials. Optional {@code ?type=}
     * filter narrows to one of ACCOUNT / REPO / AI.
     */
    @GetMapping("/api/credentials")
    public List<Credential> list(@RequestParam(value = "type", required = false) String type)
    {
        if (type == null || type.isBlank()) {
            return credentialService.list();
        }
        return credentialService.listByType(parseType(type));
    }

    /** POST /api/credentials — upsert a credential identified by (type, name, instanceName). */
    @PostMapping("/api/credentials")
    public Credential upsert(@RequestBody UpsertRequest req)
    {
        requireNotBlank(req.value(), "value must not be blank");
        requireNotBlank(req.name(), "name must not be blank");

        return credentialService.upsert(
                parseType(req.type()),
                req.name(),
                resolveInstanceName(req.instanceName()),
                req.value(),
                req.label(),
                req.notes());
    }

    /**
     * DELETE /api/credentials/{type}/{name} — remove the {@code "default api"}
     * instance. The instanceName variant below targets a specific instance.
     */
    @DeleteMapping("/api/credentials/{type}/{name}")
    public Map<String, String> delete(@PathVariable String type, @PathVariable String name)
    {
        credentialService.delete(parseType(type), name, DEFAULT_INSTANCE_NAME);
        return ImmutableMap.of("result", DELETED);
    }

    /** DELETE /api/credentials/{type}/{name}/{instanceName} — remove a specific instance. */
    @DeleteMapping("/api/credentials/{type}/{name}/{instanceName}")
    public Map<String, String> deleteInstance(
            @PathVariable String type,
            @PathVariable String name,
            @PathVariable String instanceName)
    {
        credentialService.delete(parseType(type), name, instanceName);
        return ImmutableMap.of("result", DELETED);
    }

    private static String resolveInstanceName(String fromRequest)
    {
        if (fromRequest == null || fromRequest.isBlank()) {
            return DEFAULT_INSTANCE_NAME;
        }
        return fromRequest.trim();
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
