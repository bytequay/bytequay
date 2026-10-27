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

import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.net.URI;

import static java.util.Objects.requireNonNull;

@Entity
@Table(name = "hosting_instances", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "web_base_url"}))
public class HostingInstanceEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HostingProvider provider;

    @Column(name = "web_base_url", nullable = false)
    private String webBaseUrl;

    @Column(name = "api_base_url", nullable = false)
    private String apiBaseUrl;

    protected HostingInstanceEntity() {}

    public HostingInstanceEntity(HostingProvider provider, URI webBaseUrl, URI apiBaseUrl)
    {
        this.provider = requireNonNull(provider, "provider is null");
        this.webBaseUrl = requireNonNull(webBaseUrl, "webBaseUrl is null").toASCIIString();
        this.apiBaseUrl = requireNonNull(apiBaseUrl, "apiBaseUrl is null").toASCIIString();
    }

    public HostingInstance toDomain()
    {
        return new HostingInstance(id, provider, URI.create(webBaseUrl), URI.create(apiBaseUrl));
    }
}
