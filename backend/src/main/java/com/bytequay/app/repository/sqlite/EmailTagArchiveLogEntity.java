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
@Table(name = "email_tag_archive_log")
class EmailTagArchiveLogEntity
{
    @EmbeddedId
    private EmailTagArchiveLogKey id;

    @Column(name = "tag_id")
    private String tagId;

    @Column(name = "subject")
    private String subject;

    @Column(name = "from_addr")
    private String fromAddr;

    @Column(name = "snippet")
    private String snippet;

    @Column(name = "received_at_ms", nullable = false)
    private long receivedAtMs;

    @Column(name = "archived_at_ms", nullable = false)
    private long archivedAtMs;

    EmailTagArchiveLogKey getId() { return id; }
    void setId(EmailTagArchiveLogKey id) { this.id = id; }

    String getTagId() { return tagId; }
    void setTagId(String tagId) { this.tagId = tagId; }

    String getSubject() { return subject; }
    void setSubject(String subject) { this.subject = subject; }

    String getFromAddr() { return fromAddr; }
    void setFromAddr(String fromAddr) { this.fromAddr = fromAddr; }

    String getSnippet() { return snippet; }
    void setSnippet(String snippet) { this.snippet = snippet; }

    long getReceivedAtMs() { return receivedAtMs; }
    void setReceivedAtMs(long receivedAtMs) { this.receivedAtMs = receivedAtMs; }

    long getArchivedAtMs() { return archivedAtMs; }
    void setArchivedAtMs(long archivedAtMs) { this.archivedAtMs = archivedAtMs; }

    @Embeddable
    static final class EmailTagArchiveLogKey
            implements Serializable
    {
        @Column(name = "account_email", nullable = false)
        private String accountEmail;

        @Column(name = "gmail_thread_id", nullable = false)
        private String gmailThreadId;

        EmailTagArchiveLogKey() {}

        EmailTagArchiveLogKey(String accountEmail, String gmailThreadId)
        {
            this.accountEmail = accountEmail;
            this.gmailThreadId = gmailThreadId;
        }

        String getAccountEmail() { return accountEmail; }
        void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }

        String getGmailThreadId() { return gmailThreadId; }
        void setGmailThreadId(String gmailThreadId) { this.gmailThreadId = gmailThreadId; }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof EmailTagArchiveLogKey that)) {
                return false;
            }
            return Objects.equals(accountEmail, that.accountEmail)
                    && Objects.equals(gmailThreadId, that.gmailThreadId);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(accountEmail, gmailThreadId);
        }
    }
}
