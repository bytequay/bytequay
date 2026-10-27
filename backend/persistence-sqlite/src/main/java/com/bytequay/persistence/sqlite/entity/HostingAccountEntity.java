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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.hosting.HostingAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

@Entity
@Table(name = "hosting_accounts", uniqueConstraints = @UniqueConstraint(columnNames = {"instance_id", "remote_user_id"}))
public class HostingAccountEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private HostingInstanceEntity instance;

    @Column(name = "remote_user_id", nullable = false)
    private String remoteUserId;

    @Column(nullable = false)
    private String login;

    protected HostingAccountEntity() {}

    public HostingAccountEntity(HostingInstanceEntity instance, String remoteUserId, String login)
    {
        this.instance = requireNonNull(instance, "instance is null");
        this.remoteUserId = checkText(remoteUserId, "remoteUserId");
        updateLogin(login);
    }

    public void updateLogin(String login)
    {
        this.login = checkText(login, "login");
    }

    public HostingAccount toDomain()
    {
        return new HostingAccount(id, instance.getId(), remoteUserId, login);
    }
}
