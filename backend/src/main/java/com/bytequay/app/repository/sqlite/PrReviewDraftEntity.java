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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pr_review_draft")
class PrReviewDraftEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pr_id", nullable = false)
    private long prId;

    @Column
    private String repo;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column
    private String summary;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(nullable = false)
    private String model;

    @Column(name = "head_sha")
    private String headSha;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    protected PrReviewDraftEntity() {}

    @PrePersist
    void prePersist()
    {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate()
    {
        this.updatedAt = Instant.now();
    }

    Long getId() { return id; }

    long getPrId() { return prId; }
    void setPrId(long prId) { this.prId = prId; }

    String getRepo() { return repo; }
    void setRepo(String repo) { this.repo = repo; }

    Integer getPrNumber() { return prNumber; }
    void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }

    String getSummary() { return summary; }
    void setSummary(String summary) { this.summary = summary; }

    String getProviderId() { return providerId; }
    void setProviderId(String providerId) { this.providerId = providerId; }

    String getModel() { return model; }
    void setModel(String model) { this.model = model; }

    String getHeadSha() { return headSha; }
    void setHeadSha(String headSha) { this.headSha = headSha; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    Instant getCreatedAt() { return createdAt; }

    Instant getUpdatedAt() { return updatedAt; }
}
