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

import java.time.Instant;

@Entity
@Table(name = "model_api_keys")
class ModelApiKeyEntity
{
    @Id
    private String alias;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String ciphertext;

    @Column(nullable = false)
    private Instant createdAt;

    protected ModelApiKeyEntity() {}

    ModelApiKeyEntity(String alias, String provider, String ciphertext, Instant createdAt)
    {
        this.alias = alias;
        this.provider = provider;
        this.ciphertext = ciphertext;
        this.createdAt = createdAt;
    }

    String getAlias() { return alias; }

    String getProvider() { return provider; }

    String getCiphertext() { return ciphertext; }

    Instant getCreatedAt() { return createdAt; }
}
