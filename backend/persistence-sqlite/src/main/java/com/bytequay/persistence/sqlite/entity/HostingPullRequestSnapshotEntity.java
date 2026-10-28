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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static java.util.Objects.requireNonNull;

@Entity
@Table(name = "pr_metadata_snapshots", uniqueConstraints = @UniqueConstraint(columnNames = {"pr_id", "account_id"}))
public class HostingPullRequestSnapshotEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pr_id", nullable = false)
    private PullRequestEntity request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private HostingAccountEntity account;

    private String credentialScope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "TEXT")
    private FetchedPullRequest pullRequest;

    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "TEXT")
    private PrRawDetail metadata;

    private String etag;

    private Instant metadataValidatedAt;

    protected HostingPullRequestSnapshotEntity() {}

    public HostingPullRequestSnapshotEntity(PullRequestEntity request, HostingAccountEntity account)
    {
        this.request = requireNonNull(request, "request is null");
        this.account = requireNonNull(account, "account is null");
    }

    public void update(HostingPullRequestSnapshot snapshot)
    {
        updateSummary(snapshot.credentialScope(), snapshot.pullRequest());
        metadata = snapshot.detail();
        etag = snapshot.etag().orElse(null);
        metadataValidatedAt = snapshot.validatedAt();
    }

    public void updateSummary(String scope, FetchedPullRequest summary)
    {
        if (!scope.equals(credentialScope())) {
            metadata = null;
            etag = null;
            metadataValidatedAt = null;
        }
        credentialScope = scope;
        pullRequest = summary;
        updatedAt = summary.updatedAt();
    }

    public FetchedPullRequest summary()
    {
        return pullRequest;
    }

    public Optional<HostingPullRequestSnapshot> toDomain()
    {
        return Optional.ofNullable(metadata).map(value -> new HostingPullRequestSnapshot(request.hostingIdentity(), account.getId(), credentialScope(),
                pullRequest, value, Optional.ofNullable(etag), metadataValidatedAt));
    }

    public String credentialScope()
    {
        return Optional.ofNullable(credentialScope).orElse(CONNECTED_ACCOUNT);
    }
}
