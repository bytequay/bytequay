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

import com.bytequay.persistence.sqlite.entity.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "model_api_keys")
@Getter(AccessLevel.PACKAGE)
class ModelApiKeyEntity
        extends AssignedIdEntity<String>
{
    @Id
    private String alias;

    /** Nullable for rows saved before opaque IDs were introduced. */
    @Column(name = "key_id")
    private String keyId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String ciphertext;

    /** Nullable: rows written before the hint was kept have none. */
    @Column
    private String maskedHint;

    @Column(nullable = false)
    @Convert(disableConversion = true)
    private Instant createdAt;

    protected ModelApiKeyEntity() {}

    ModelApiKeyEntity(String keyId, String alias, String provider, String ciphertext, String maskedHint, Instant createdAt)
    {
        this.keyId = keyId;
        this.alias = alias;
        this.provider = provider;
        this.ciphertext = ciphertext;
        this.maskedHint = maskedHint;
        this.createdAt = createdAt;
    }

    void setKeyId(String keyId)
    {
        this.keyId = keyId;
    }

    @Override
    public String getId()
    {
        return alias;
    }
}
