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

import com.bytequay.app.service.gmail.GmailOAuthService;
import com.bytequay.app.service.gmail.GmailOAuthService.ConnectionInfo;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Gmail OAuth flow. Multi-account by design — the
 * connect dance can run repeatedly to add more Google accounts; each
 * connection lands in its own credential row and shows up in
 * {@link #accounts()}.
 */
@RestController
@RequestMapping("/api/auth/gmail")
public class GmailOAuthController
{
    private final GmailOAuthService oauth;

    public GmailOAuthController(GmailOAuthService oauth)
    {
        this.oauth = requireNonNull(oauth, "oauth is null");
    }

    public record CallbackRequest(String code, String state) {}

    /**
     * GET /api/auth/gmail/authorize-url?redirectUri=… — returns the URL
     * the renderer should open in the system browser. Mints a fresh
     * CSRF state + PKCE pair as a side effect.
     *
     * <p>The renderer passes the loopback URL it just bound to
     * (Google's Desktop OAuth client only accepts {@code http://127.0.0.1:*}).
     *
     * <p>Response shape: {@code {"configured": bool, "url": string?}}.
     */
    @GetMapping("/authorize-url")
    public Map<String, Object> authorizeUrl(@RequestParam String redirectUri)
    {
        if (!oauth.isConfigured()) {
            return ImmutableMap.of("configured", false);
        }
        return ImmutableMap.of(
                "configured", true,
                "url", oauth.issueAuthorizeUrl(redirectUri));
    }

    /**
     * POST /api/auth/gmail/callback — completes the handshake. Body:
     * {@code {"code": "...", "state": "..."}}. Returns the connected
     * email on success.
     */
    @PostMapping("/callback")
    public ConnectionInfo callback(@RequestBody CallbackRequest req)
    {
        return oauth.exchangeCode(req.code(), req.state());
    }

    /**
     * GET /api/auth/gmail/accounts — list of connected accounts.
     * Returns an empty list when nothing's connected. Cheap; backed
     * by a single credentials-store lookup per row.
     */
    @GetMapping("/accounts")
    public List<ConnectionInfo> accounts()
    {
        return oauth.listAccounts();
    }

    /**
     * DELETE /api/auth/gmail/accounts/{email} — drops the stored
     * refresh token for {@code email}. Idempotent.
     */
    @DeleteMapping("/accounts/{email}")
    public Map<String, String> disconnect(@PathVariable String email)
    {
        oauth.disconnect(email);
        return ImmutableMap.of("result", "disconnected", "email", email);
    }
}
