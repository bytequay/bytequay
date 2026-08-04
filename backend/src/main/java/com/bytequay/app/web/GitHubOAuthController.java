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

import com.bytequay.app.service.github.GhCliService;
import com.bytequay.app.service.github.GitHubOAuthService;
import com.bytequay.app.service.github.GitHubOAuthService.ConnectionInfo;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.bytequay.app.web.RequestValidation.requireBody;
import static java.util.Objects.requireNonNull;

/**
 * REST surface for connecting a GitHub account. The OAuth flow starts by
 * issuing an authorize URL, then finishes via a callback POST once the
 * {@code open-url} handler hands the code back; the {@code /cli} endpoints
 * below are the alternative for users whose org blocks PATs but has
 * approved the GitHub CLI. Both fill the same credential slot.
 */
@RestController
@RequestMapping("/api/auth/github")
public class GitHubOAuthController
{
    private final GitHubOAuthService oauth;
    private final GhCliService ghCli;

    public GitHubOAuthController(GitHubOAuthService oauth, GhCliService ghCli)
    {
        this.oauth = requireNonNull(oauth, "oauth is null");
        this.ghCli = requireNonNull(ghCli, "ghCli is null");
    }

    public record CallbackRequest(String code, String state) {}

    /**
     * GET /api/auth/github/authorize-url — returns the URL the renderer
     * should open in the system browser. Mints a fresh CSRF state +
     * PKCE pair as a side effect.
     *
     * <p>Response shape: {@code {"configured": bool, "url": string?}}.
     * When {@code configured} is false the renderer hides the
     * "Sign in with GitHub" button and falls back to the PAT input.
     */
    @GetMapping("/authorize-url")
    public Map<String, Object> authorizeUrl()
    {
        if (!oauth.isConfigured()) {
            return ImmutableMap.of("configured", false);
        }
        return ImmutableMap.of(
                "configured", true,
                "url", oauth.issueAuthorizeUrl());
    }

    /**
     * POST /api/auth/github/callback — completes the handshake. Body:
     * {@code {"code": "...", "state": "..."}}. Returns the connected
     * login on success; 400 on bad state / missing fields; 502 on
     * GitHub-side failure; 503 when the OAuth App isn't configured.
     */
    @PostMapping("/callback")
    public ConnectionInfo callback(@RequestBody CallbackRequest req)
    {
        req = requireBody(req);
        requireNotBlank(req.code(), "code is required");
        requireNotBlank(req.state(), "state is required");
        return oauth.exchangeCode(req.code(), req.state());
    }

    /**
     * GET /api/auth/github/connection — returns
     * {@code {connected: bool, login?}}. Cheap; backed by a single
     * credentials-store lookup.
     */
    @GetMapping("/connection")
    public Map<String, Object> connection()
    {
        Optional<ConnectionInfo> info = oauth.getConnection();
        if (info.isEmpty()) {
            return ImmutableMap.of("connected", false);
        }
        ConnectionInfo c = info.get();
        ImmutableMap.Builder<String, Object> out = ImmutableMap.builder();
        out.put("connected", true);
        if (c.login() != null) {
            out.put("login", c.login());
        }
        return out.build();
    }

    /**
     * GET /api/auth/github/cli — {@code {available: bool}}. True when a
     * {@code gh} binary is on disk; it says nothing about whether the user
     * is logged in to it (that only surfaces when the import runs).
     */
    @GetMapping("/cli")
    public Map<String, Object> ghCliAvailable()
    {
        return ImmutableMap.of("available", ghCli.isAvailable());
    }

    /**
     * POST /api/auth/github/cli/import — stores the token {@code gh} is
     * already holding. 503 when gh isn't installed; 502 with gh's own
     * message when it is but can't produce a token.
     */
    @PostMapping("/cli/import")
    public ConnectionInfo importGhCliToken()
    {
        return ghCli.importToken();
    }

    /**
     * POST /api/auth/github/disconnect — clears the stored token.
     * Idempotent. The {@code pat:clear} IPC path also drops this
     * slot, so either route works.
     */
    @PostMapping("/disconnect")
    public Map<String, String> disconnect()
    {
        oauth.disconnect();
        return ImmutableMap.of("result", "disconnected");
    }
}
