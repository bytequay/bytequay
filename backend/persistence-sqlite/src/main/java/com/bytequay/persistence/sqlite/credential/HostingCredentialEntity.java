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

import com.bytequay.persistence.sqlite.entity.HostingAccountEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;

import static java.util.Objects.requireNonNull;

@Entity
@Table(name = "hosting_credentials")
class HostingCredentialEntity
{
    @Id
    @Column(name = "account_id")
    private Long accountId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private HostingAccountEntity account;

    @Column(nullable = false)
    @Getter(AccessLevel.PACKAGE)
    private String ciphertext;

    protected HostingCredentialEntity() {}

    HostingCredentialEntity(HostingAccountEntity account)
    {
        this.account = requireNonNull(account, "account is null");
    }

    void update(String ciphertext)
    {
        this.ciphertext = requireNonNull(ciphertext, "ciphertext is null");
    }
}
