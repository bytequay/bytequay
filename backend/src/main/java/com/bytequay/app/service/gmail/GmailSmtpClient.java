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

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.Properties;

/**
 * Sends mail via {@code smtp.gmail.com:587} using STARTTLS + LOGIN
 * with the same app password the IMAP client authenticates with.
 * Sister to {@link GmailImapClient}; the read/write split mirrors
 * Gmail's own protocol split.
 *
 * <p>Per-request connect for now — Angus' {@code Transport.send} opens
 * a fresh connection each call. SMTP setup runs ~200ms over a warm
 * route, which is fine for an interactive "Send" click. Pooled
 * transport is the obvious next step if we ever do batch sending.
 */
@Component
public class GmailSmtpClient
{
    private static final String HOST = "smtp.gmail.com";
    private static final int PORT = 587;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(GmailSmtpClient.class);

    /**
     * Builds and sends a plain-text reply. Threading headers
     * ({@code In-Reply-To}, {@code References}) are set when present so
     * the reply lands inside the same Gmail conversation rather than
     * starting a new one. The {@code From} address is always the
     * authenticated account — Gmail's SMTP rejects mismatched From
     * addresses.
     *
     * @param email        authenticated sender (also the From address)
     * @param appPassword  16-char Google app password (spaces stripped
     *                     by the caller)
     * @param to           recipient address (single recipient — Reply-All
     *                     comes later)
     * @param subject      already includes the {@code Re:} prefix when
     *                     appropriate
     * @param inReplyTo    original {@code Message-ID} value (with angle
     *                     brackets); null/blank to skip the header
     * @param references   accumulated thread {@code References} value;
     *                     null/blank to skip
     * @param body         plain-text body (UTF-8)
     */
    public void sendReply(
            String email,
            String appPassword,
            String to,
            String subject,
            String inReplyTo,
            String references,
            String body)
    {
        Properties props = properties();
        Session session = Session.getInstance(props, new Authenticator()
        {
            @Override
            protected PasswordAuthentication getPasswordAuthentication()
            {
                return new PasswordAuthentication(email, appPassword);
            }
        });
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(email));
            message.setRecipients(Message.RecipientType.TO, parseRecipients(to));
            message.setSubject(subject == null ? "" : subject, "UTF-8");
            if (inReplyTo != null && !inReplyTo.isBlank()) {
                message.setHeader("In-Reply-To", inReplyTo);
            }
            if (references != null && !references.isBlank()) {
                message.setHeader("References", references);
            }
            message.setSentDate(new Date());
            message.setText(body == null ? "" : body, "UTF-8");
            Transport.send(message);
            log.debug("Gmail SMTP reply sent for {}", email);
        }
        catch (AuthenticationFailedException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(401),
                    "Google rejected the SMTP login (check app password)", e);
        }
        catch (SendFailedException e) {
            // Recipient-side rejection (bad address, blocked, etc.) —
            // 422 surfaces it as user-fixable rather than a server error.
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "Gmail SMTP refused the message: " + e.getMessage(), e);
        }
        catch (AddressException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Invalid recipient address: " + e.getMessage(), e);
        }
        catch (MessagingException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail SMTP error: " + e.getMessage(), e);
        }
    }

    /** Parses one or more comma-separated addresses. We only ever pass
     *  a single recipient today, but {@code parseHeader} handles the
     *  comma-list shape correctly when that changes. */
    private static InternetAddress[] parseRecipients(String to)
            throws AddressException
    {
        if (to == null || to.isBlank()) {
            throw new AddressException("recipient must not be blank");
        }
        return InternetAddress.parseHeader(to, true);
    }

    private static Properties properties()
    {
        Properties props = new Properties();
        props.put("mail.smtp.host", HOST);
        props.put("mail.smtp.port", String.valueOf(PORT));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        // .required forces the upgrade — no plaintext fallback if Google's
        // STARTTLS handshake fails for any reason.
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(READ_TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(READ_TIMEOUT_MS));
        return props;
    }
}
