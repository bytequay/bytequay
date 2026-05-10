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
package com.bytequay.app.repository.sqlite;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "email_messages")
class EmailMessageEntity
{
    @EmbeddedId
    private EmailMessageKey id;

    @Column(name = "gmail_thread_id", nullable = false)
    private String gmailThreadId;

    @Column(name = "from_addr")
    private String fromAddr;

    @Column(name = "subject")
    private String subject;

    @Column(name = "snippet")
    private String snippet;

    @Column(name = "received_at_ms", nullable = false)
    private long receivedAtMs;

    @Column(name = "is_unread", nullable = false)
    private boolean unread;

    @Column(name = "is_in_inbox", nullable = false)
    private boolean inInbox;

    @Column(name = "cached_at_ms", nullable = false)
    private long cachedAtMs;

    EmailMessageKey getId() { return id; }
    void setId(EmailMessageKey id) { this.id = id; }

    String getGmailThreadId() { return gmailThreadId; }
    void setGmailThreadId(String gmailThreadId) { this.gmailThreadId = gmailThreadId; }

    String getFromAddr() { return fromAddr; }
    void setFromAddr(String fromAddr) { this.fromAddr = fromAddr; }

    String getSubject() { return subject; }
    void setSubject(String subject) { this.subject = subject; }

    String getSnippet() { return snippet; }
    void setSnippet(String snippet) { this.snippet = snippet; }

    long getReceivedAtMs() { return receivedAtMs; }
    void setReceivedAtMs(long receivedAtMs) { this.receivedAtMs = receivedAtMs; }

    boolean isUnread() { return unread; }
    void setUnread(boolean unread) { this.unread = unread; }

    boolean isInInbox() { return inInbox; }
    void setInInbox(boolean inInbox) { this.inInbox = inInbox; }

    long getCachedAtMs() { return cachedAtMs; }
    void setCachedAtMs(long cachedAtMs) { this.cachedAtMs = cachedAtMs; }

    @Embeddable
    static class EmailMessageKey
            implements Serializable
    {
        @Column(name = "account_email", nullable = false)
        private String accountEmail;

        @Column(name = "gmail_message_id", nullable = false)
        private String gmailMessageId;

        EmailMessageKey() {}

        EmailMessageKey(String accountEmail, String gmailMessageId)
        {
            this.accountEmail = accountEmail;
            this.gmailMessageId = gmailMessageId;
        }

        String getAccountEmail() { return accountEmail; }
        void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }

        String getGmailMessageId() { return gmailMessageId; }
        void setGmailMessageId(String gmailMessageId) { this.gmailMessageId = gmailMessageId; }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof EmailMessageKey other)) {
                return false;
            }
            return Objects.equals(accountEmail, other.accountEmail)
                    && Objects.equals(gmailMessageId, other.gmailMessageId);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(accountEmail, gmailMessageId);
        }
    }
}
