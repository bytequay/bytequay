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
import com.bytequay.app.service.gmail.GmailImapAuthService.ConnectionInfo;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for Gmail accounts connected via IMAP + app password.
 * Connect, list, and disconnect all live here — IMAP is the only
 * supported auth mode now (the OAuth path was removed in favour of a
 * single, predictable local-only flow).
 */
@RestController
@RequestMapping("/api/auth/gmail")
public class GmailImapController
{
    private final GmailImapAuthService imap;

    public GmailImapController(GmailImapAuthService imap)
    {
        this.imap = requireNonNull(imap, "imap is null");
    }

    public record ConnectRequest(String email, String appPassword) {}

    /** Kept on the wire so frontend bridge clients can keep returning a
     *  uniform "{email, authMode}" shape. {@code authMode} is always
     *  {@code "IMAP"} now; the field exists purely for compatibility
     *  with downstream code that still branches on it. */
    public record GmailAccount(String email, String authMode) {}

    /**
     * POST /api/auth/gmail/imap/connect — body:
     * {@code {"email": "...", "appPassword": "...."}}. Validates the
     * credentials by opening an {@code imaps} session against
     * {@code imap.gmail.com:993} before persisting; on success returns
     * the connected email. On failure returns 400 / 401 / 502 with a
     * human-readable message.
     */
    @PostMapping("/imap/connect")
    public ConnectionInfo connect(@RequestBody ConnectRequest req)
    {
        return imap.connect(req.email(), req.appPassword());
    }

    /** GET /api/auth/gmail/accounts — connected Gmail accounts. */
    @GetMapping("/accounts")
    public List<GmailAccount> accounts()
    {
        return imap.listAccounts().stream()
                .map(info -> new GmailAccount(info.email(), "IMAP"))
                .toList();
    }

    /** DELETE /api/auth/gmail/accounts/{email} — drops the stored
     *  IMAP credential. Idempotent. */
    @DeleteMapping("/accounts/{email}")
    public Map<String, String> disconnect(@PathVariable String email)
    {
        imap.disconnect(email);
        return ImmutableMap.of("result", "disconnected", "email", email);
    }
}
