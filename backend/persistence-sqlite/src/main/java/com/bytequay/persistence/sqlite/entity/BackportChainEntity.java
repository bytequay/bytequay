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

import com.bytequay.core.backport.BackportChain;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.BackportChainConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import static com.google.common.base.Preconditions.checkArgument;

@Entity
@Table(name = "backport_chain")
public class BackportChainEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Version
    @Getter
    private long version;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = BackportChainConverter.class)
    private BackportChain data;

    public BackportChainEntity() {}

    public static BackportChainEntity fromDomain(BackportChain chain)
    {
        var entity = new BackportChainEntity();
        entity.id = chain.id();
        entity.version = chain.version();
        entity.updateFrom(chain);
        return entity;
    }

    public void updateFrom(BackportChain chain)
    {
        checkArgument(id.equals(chain.id()), "Chain identity changed");
        data = chain;
    }

    public BackportChain toDomain()
    {
        return data.withVersion(version);
    }
}
