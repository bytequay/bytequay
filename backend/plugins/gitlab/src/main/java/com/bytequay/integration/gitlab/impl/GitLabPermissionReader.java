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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPermissions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingPermissions.Status.MISSING;
import static com.bytequay.core.hosting.HostingPermissions.Status.UNAVAILABLE;
import static com.bytequay.core.hosting.HostingPermissions.Status.VERIFIED;
import static java.util.Objects.requireNonNull;

final class GitLabPermissionReader
{
    private final GitLabHttpClient http;

    GitLabPermissionReader(GitLabHttpClient http)
    {
        this.http = requireNonNull(http, "http is null");
    }

    HostingPermissions check(HostingAccess access)
    {
        try {
            return report(access, http.getOAuthTokenInfo(access, OAuthInfo.class).body().orElseThrow().scope());
        }
        catch (HostingFailure failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure;
            }
            switch (failure.kind()) {
                case AUTHENTICATION_REQUIRED, FORBIDDEN, MISSING_OR_INACCESSIBLE, INVALID_RESPONSE -> {}
                default -> {
                    return unavailable("Could not verify GitLab permissions. " + failure.getMessage() + " Use Check permissions to try again.");
                }
            }
        }
        // OAuth and personal tokens have separate inspection endpoints; no token is sent in a URL.
        try {
            return report(access, http.get(access, "personal_access_tokens/self", Optional.empty(), PersonalTokenInfo.class).body().orElseThrow().scopes());
        }
        catch (HostingFailure failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure;
            }
            return unavailable("Could not verify GitLab permissions. " + failure.getMessage() +
                    " This server may not support token permission inspection. Project access has not been verified.");
        }
    }

    private static HostingPermissions report(HostingAccess access, List<String> scopes)
    {
        if (scopes == null || scopes.size() > 100 || scopes.stream()
                .anyMatch(scope -> scope == null || !scope.matches("[a-z][a-z0-9_]{0,63}") || scope.equals(access.token()))) {
            return unavailable("GitLab returned an invalid permission list. Project access has not been verified; use Check permissions to try again.");
        }
        List<String> granted = scopes.stream().distinct().sorted().collect(ImmutableList.toImmutableList());
        if (granted.contains("api")) {
            return new HostingPermissions(VERIFIED, granted,
                    "The required api permission is granted. Access to each project still depends on this account's membership and server policy.");
        }
        return new HostingPermissions(MISSING, granted, "GitLab granted " + (granted.isEmpty() ? "no permissions" : String.join(", ", granted)) +
                "; ByteQuay requires api. Reconnect and authorize api, or use a personal access token with api selected.");
    }

    private static HostingPermissions unavailable(String detail)
    {
        return new HostingPermissions(UNAVAILABLE, ImmutableList.of(), detail);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OAuthInfo(List<String> scope) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PersonalTokenInfo(List<String> scopes) {}
}
