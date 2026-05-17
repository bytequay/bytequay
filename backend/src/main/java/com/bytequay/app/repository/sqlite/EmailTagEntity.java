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
@Table(name = "email_tags")
class EmailTagEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "account_email", nullable = false)
    private String accountEmail;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "subject_contains", nullable = false)
    private String subjectContains;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "updated_at_ms", nullable = false)
    private long updatedAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getAccountEmail() { return accountEmail; }
    void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }

    String getName() { return name; }
    void setName(String name) { this.name = name; }

    String getSubjectContains() { return subjectContains; }
    void setSubjectContains(String subjectContains) { this.subjectContains = subjectContains; }

    String getAction() { return action; }
    void setAction(String action) { this.action = action; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    long getUpdatedAtMs() { return updatedAtMs; }
    void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }
}
