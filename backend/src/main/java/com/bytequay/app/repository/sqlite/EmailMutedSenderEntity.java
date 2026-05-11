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
@Table(name = "email_muted_senders")
class EmailMutedSenderEntity
{
    @EmbeddedId
    private EmailMutedSenderKey id;

    @Column(name = "muted_at_ms", nullable = false)
    private long mutedAtMs;

    EmailMutedSenderKey getId() { return id; }
    void setId(EmailMutedSenderKey id) { this.id = id; }

    long getMutedAtMs() { return mutedAtMs; }
    void setMutedAtMs(long mutedAtMs) { this.mutedAtMs = mutedAtMs; }

    @Embeddable
    static final class EmailMutedSenderKey
            implements Serializable
    {
        @Column(name = "account_email", nullable = false)
        private String accountEmail;

        @Column(name = "sender_email", nullable = false)
        private String senderEmail;

        EmailMutedSenderKey() {}

        EmailMutedSenderKey(String accountEmail, String senderEmail)
        {
            this.accountEmail = accountEmail;
            this.senderEmail = senderEmail;
        }

        String getAccountEmail() { return accountEmail; }
        void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }

        String getSenderEmail() { return senderEmail; }
        void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof EmailMutedSenderKey that)) {
                return false;
            }
            return Objects.equals(accountEmail, that.accountEmail)
                    && Objects.equals(senderEmail, that.senderEmail);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(accountEmail, senderEmail);
        }
    }
}
