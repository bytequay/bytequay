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

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the OAuth-discovery probes an MCP client (e.g. Codex's rmcp
 * client) fires when it connects to our HTTP MCP server.
 *
 * <p>This sidecar is loopback-only and does no OAuth, so per RFC 8414 /
 * RFC 9728 the correct response is a 404 — which the client reads as "no
 * authorization required, proceed unauthenticated". Handling the paths
 * here returns a clean, body-less 404 instead of Spring's static-resource
 * {@code NoResourceFoundException} (a noisy stack trace + an HTML error
 * page that a strict client may choke on).
 */
@RestController
public class OAuthDiscoveryController
{
    @GetMapping({
            "/.well-known/oauth-authorization-server",
            "/.well-known/oauth-authorization-server/**",
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/**",
            "/.well-known/openid-configuration"})
    public ResponseEntity<Void> noOauth()
    {
        return ResponseEntity.notFound().build();
    }
}
