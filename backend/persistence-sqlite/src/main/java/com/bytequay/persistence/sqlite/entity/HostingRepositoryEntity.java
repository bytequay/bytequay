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

import com.bytequay.core.hosting.HostingRepository;
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

import java.net.URI;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

@Entity
@Table(name = "hosting_repositories", uniqueConstraints = @UniqueConstraint(columnNames = {"instance_id", "remote_id"}))
public class HostingRepositoryEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private HostingInstanceEntity instance;

    @Column(name = "remote_id", nullable = false)
    private String remoteId;

    @Column(name = "full_path", nullable = false)
    private String fullPath;

    @Column(name = "web_url", nullable = false)
    private String webUrl;

    @Column(name = "http_clone_url", nullable = false)
    private String httpCloneUrl;

    @Column(name = "ssh_clone_url")
    private String sshCloneUrl;

    @Column(name = "default_branch")
    private String defaultBranch;

    protected HostingRepositoryEntity() {}

    public HostingRepositoryEntity(HostingInstanceEntity instance, String remoteId)
    {
        this.instance = requireNonNull(instance, "instance is null");
        this.remoteId = checkText(remoteId, "remoteId");
    }

    public void update(String fullPath, URI webUrl, URI httpCloneUrl, Optional<String> sshCloneUrl, Optional<String> defaultBranch)
    {
        this.fullPath = checkText(fullPath, "fullPath");
        this.webUrl = requireNonNull(webUrl, "webUrl is null").toASCIIString();
        this.httpCloneUrl = requireNonNull(httpCloneUrl, "httpCloneUrl is null").toASCIIString();
        this.sshCloneUrl = requireNonNull(sshCloneUrl, "sshCloneUrl is null").orElse(null);
        this.defaultBranch = requireNonNull(defaultBranch, "defaultBranch is null").orElse(null);
    }

    public HostingRepository toDomain()
    {
        return new HostingRepository(id, instance.getId(), remoteId, fullPath, URI.create(webUrl), URI.create(httpCloneUrl),
                Optional.ofNullable(sshCloneUrl), Optional.ofNullable(defaultBranch));
    }
}
