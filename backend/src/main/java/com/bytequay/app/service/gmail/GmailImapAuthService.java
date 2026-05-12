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
package com.bytequay.app.service.gmail;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Authenticates a Gmail account via IMAP + app-password (a.k.a. "Sign
 * in with App Password"). Sister to {@link GmailOAuthService}; lets
 * users connect mailboxes without going through the Google OAuth
 * verification process. The trade-off is uglier onboarding (the user
 * has to enable 2FA and generate a 16-character app password in
 * their Google account).
 *
 * <p>Storage uses a parallel credential slot
 * {@code (ACCOUNT, "gmail-imap", instanceName=<email>)} so the auth
 * mode is unambiguous from the credential row alone — no need for a
 * notes-field discriminator.
 *
 * <p>Validation happens at connect time: we open an IMAP connection
 * and run {@code LOGIN} before persisting, so the user gets immediate
 * feedback if they pasted the wrong password instead of failing
 * silently on the first sync.
 */
@Service
public class GmailImapAuthService
{
    /** Credential row family for IMAP-stored accounts. The
     *  {@code instanceName} is the connected email; the secret column
     *  is the app password. */
    public static final String GMAIL_IMAP_ACCOUNT_NAME = "gmail-imap";

    static final String GMAIL_IMAP_HOST = "imap.gmail.com";
    static final int GMAIL_IMAP_PORT = 993;
    static final int CONNECT_TIMEOUT_MS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(GmailImapAuthService.class);

    private final CredentialService credentialService;
    private final ImapValidator validator;
    private final Clock clock;

    @Autowired
    public GmailImapAuthService(CredentialService credentialService)
    {
        this(credentialService, new GmailImapValidator(), Clock.systemUTC());
    }

    GmailImapAuthService(CredentialService credentialService, ImapValidator validator, Clock clock)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.validator = requireNonNull(validator, "validator is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Validates {@code email} + {@code appPassword} against
     * {@code imap.gmail.com:993} and, on success, upserts a
     * credential row. Returns the connected email so the renderer
     * can confirm visually.
     *
     * <p>Throws 400 on bad input, 401 when Google rejects the login
     * (most often: 2FA disabled, wrong app password, or a regular
     * Google password used by mistake), 502 on network trouble.
     */
    public ConnectionInfo connect(String email, String appPassword)
    {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "email must not be blank");
        }
        if (appPassword == null || appPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "appPassword must not be blank");
        }
        // Google app passwords are 16 chars displayed in groups of 4.
        // Users often copy them with the spaces — strip those before
        // sending so the LOGIN actually matches Google's expectation.
        String normalisedPassword = appPassword.replace(" ", "");
        validator.validate(email, normalisedPassword);
        credentialService.upsert(
                CredentialType.ACCOUNT,
                GMAIL_IMAP_ACCOUNT_NAME,
                email,
                normalisedPassword,
                email,
                "Acquired via Gmail IMAP app password on " + Instant.now(clock));
        log.info("Gmail IMAP connected for email={}", email);
        return new ConnectionInfo(email);
    }

    /** All currently connected IMAP-via-app-password Gmail accounts. */
    public List<ConnectionInfo> listAccounts()
    {
        return credentialService.listByTypeAndName(CredentialType.ACCOUNT, GMAIL_IMAP_ACCOUNT_NAME)
                .stream()
                .map(c -> new ConnectionInfo(c.label() != null ? c.label() : c.instanceName()))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Drops the IMAP-stored credential for {@code email}. Idempotent. */
    public void disconnect(String email)
    {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "email must not be blank");
        }
        credentialService.delete(CredentialType.ACCOUNT, GMAIL_IMAP_ACCOUNT_NAME, email);
    }

    /** True iff there's an IMAP credential row for {@code email}. Used
     *  by EmailService to pick the right backend (OAuth vs IMAP) per
     *  request without each call site reaching into CredentialService
     *  with the magic name string. */
    public boolean isConnected(String email)
    {
        if (email == null || email.isBlank()) {
            return false;
        }
        return credentialService.get(CredentialType.ACCOUNT, GMAIL_IMAP_ACCOUNT_NAME, email).isPresent();
    }

    /** Decrypted app password for {@code email}. Throws 401 if the row
     *  is missing — by then the caller has decided this is an IMAP
     *  account so a missing secret means the credential row was deleted
     *  out from under them (other Claude thread, manual DB edit, etc.). */
    public String getAppPassword(String email)
    {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "email must not be blank");
        }
        return credentialService.getSecret(CredentialType.ACCOUNT, GMAIL_IMAP_ACCOUNT_NAME, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(401),
                        "No IMAP credential stored for " + email + " — reconnect under Settings → Integrations"));
    }

    public record ConnectionInfo(String email) {}

    /** Test seam — extracted so unit tests don't need a live IMAP server. */
    interface ImapValidator
    {
        void validate(String email, String appPassword);
    }

    /** Default implementation: open imaps connection, run LOGIN, close. */
    static final class GmailImapValidator
            implements ImapValidator
    {
        @Override
        public void validate(String email, String appPassword)
        {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", GMAIL_IMAP_HOST);
            props.put("mail.imaps.port", String.valueOf(GMAIL_IMAP_PORT));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.checkserveridentity", "true");
            props.put("mail.imaps.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
            props.put("mail.imaps.timeout", String.valueOf(CONNECT_TIMEOUT_MS));
            Session session = Session.getInstance(props);
            try (Store store = session.getStore("imaps")) {
                store.connect(GMAIL_IMAP_HOST, email, appPassword);
            }
            catch (NoSuchProviderException e) {
                // Means the imaps provider isn't on the classpath, which is
                // a packaging bug — surface it loud rather than as a 401.
                throw new ResponseStatusException(HttpStatusCode.valueOf(500),
                        "IMAP provider missing from classpath", e);
            }
            catch (AuthenticationFailedException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(401),
                        "Google rejected the login. Make sure 2FA is on and you "
                                + "pasted the 16-character app password (not your "
                                + "regular Google password).", e);
            }
            catch (MessagingException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Couldn't reach " + GMAIL_IMAP_HOST + ":" + GMAIL_IMAP_PORT
                                + " — " + e.getMessage(), e);
            }
        }
    }
}
