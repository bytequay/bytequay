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
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_settings")
class AppSettingEntity
{
    @Id
    private String key;

    @Column(nullable = false)
    private String value;

    @Column(nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalCreatedAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalUpdatedAt;

    protected AppSettingEntity() {}

    AppSettingEntity(String key, String value)
    {
        this.key = key;
        this.value = value;
    }

    @PrePersist
    void prePersist()
    {
        Instant now = Instant.now();
        this.internalCreatedAt = now;
        this.internalUpdatedAt = now;
    }

    @PreUpdate
    void preUpdate()
    {
        this.internalUpdatedAt = Instant.now();
    }

    String getKey() { return key; }

    String getValue() { return value; }
    void setValue(String value) { this.value = value; }
}
