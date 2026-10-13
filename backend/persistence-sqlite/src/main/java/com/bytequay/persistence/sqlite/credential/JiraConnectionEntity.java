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
package com.bytequay.persistence.sqlite.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;

@Entity
@Table(name = "jira_connection")
@Getter(AccessLevel.PACKAGE)
class JiraConnectionEntity
{
    @Id
    private String id;
    @Column(nullable = false)
    private String brokerUrl;
    @Column(nullable = false)
    private String ciphertext;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    protected JiraConnectionEntity() {}

    JiraConnectionEntity(String id, String brokerUrl, String ciphertext, String stateJson)
    {
        this.id = id;
        update(brokerUrl, ciphertext, stateJson);
    }

    void update(String brokerUrl, String ciphertext, String stateJson)
    {
        this.brokerUrl = brokerUrl;
        this.ciphertext = ciphertext;
        this.stateJson = stateJson;
    }
}
