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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_account_sync")
class EmailAccountSyncEntity
{
    @Id
    @Column(name = "account_email", nullable = false)
    private String accountEmail;

    @Column(name = "last_history_id")
    private String lastHistoryId;

    @Column(name = "last_sync_at_ms")
    private Long lastSyncAtMs;

    String getAccountEmail() { return accountEmail; }
    void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }

    String getLastHistoryId() { return lastHistoryId; }
    void setLastHistoryId(String lastHistoryId) { this.lastHistoryId = lastHistoryId; }

    Long getLastSyncAtMs() { return lastSyncAtMs; }
    void setLastSyncAtMs(Long lastSyncAtMs) { this.lastSyncAtMs = lastSyncAtMs; }
}
