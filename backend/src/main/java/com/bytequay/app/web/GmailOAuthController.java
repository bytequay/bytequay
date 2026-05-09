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

import com.bytequay.app.service.gmail.GmailImapAuthService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Gmail OAuth flow plus the cross-mode account
 * listing / disconnect endpoints. Two auth modes coexist: OAuth refresh
 * tokens (this controller's authorize-url + callback path) and IMAP
 * app passwords (handled by {@link GmailImapController#connect}). The
 * accounts and disconnect endpoints span both so the UI shows a
 * single unified list with an auth-mode badge per row.
 */
@RestController
@RequestMapping("/api/auth/gmail")
public class GmailOAuthController
{
    private final GmailOAuthService oauth;
    private final GmailImapAuthService imap;

    public GmailOAuthController(GmailOAuthService oauth, GmailImapAuthService imap)
    {
        this.oauth = requireNonNull(oauth, "oauth is null");
        this.imap = requireNonNull(imap, "imap is null");
    }

    public record CallbackRequest(String code, String state) {}

    public record GmailAccount(String email, String authMode) {}

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
     * GET /api/auth/gmail/accounts — unified list of connected accounts
     * across both auth modes. Each entry carries its {@code authMode}
     * so the UI can badge it accordingly. Returns an empty list when
     * nothing's connected.
     */
    @GetMapping("/accounts")
    public List<GmailAccount> accounts()
    {
        List<GmailAccount> out = new ArrayList<>();
        for (ConnectionInfo info : oauth.listAccounts()) {
            out.add(new GmailAccount(info.email(), "OAUTH"));
        }
        for (GmailImapAuthService.ConnectionInfo info : imap.listAccounts()) {
            out.add(new GmailAccount(info.email(), "IMAP"));
        }
        return out;
    }

    /**
     * DELETE /api/auth/gmail/accounts/{email} — drops the stored
     * credential for {@code email} regardless of auth mode. Both
     * deletions are idempotent, so it's safe to call when only one
     * mode has the address.
     */
    @DeleteMapping("/accounts/{email}")
    public Map<String, String> disconnect(@PathVariable String email)
    {
        oauth.disconnect(email);
        imap.disconnect(email);
        return ImmutableMap.of("result", "disconnected", "email", email);
    }
}
