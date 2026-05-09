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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for IMAP-via-app-password Gmail connections — a sister
 * to {@link GmailOAuthController} that lets users skip Google's OAuth
 * verification process entirely. Listing connected accounts and
 * disconnecting both live on {@link GmailOAuthController} since they
 * span both auth modes.
 */
@RestController
@RequestMapping("/api/auth/gmail/imap")
public class GmailImapController
{
    private final GmailImapAuthService imap;

    public GmailImapController(GmailImapAuthService imap)
    {
        this.imap = requireNonNull(imap, "imap is null");
    }

    public record ConnectRequest(String email, String appPassword) {}

    /**
     * POST /api/auth/gmail/imap/connect — body:
     * {@code {"email": "...", "appPassword": "...."}}. Validates the
     * credentials by opening an {@code imaps} session against
     * {@code imap.gmail.com:993} before persisting; on success returns
     * the connected email. On failure returns 400 / 401 / 502 with a
     * human-readable message.
     */
    @PostMapping("/connect")
    public ConnectionInfo connect(@RequestBody ConnectRequest req)
    {
        return imap.connect(req.email(), req.appPassword());
    }
}
