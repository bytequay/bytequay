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

import com.bytequay.core.domain.PrCheckRunState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "pr_check_runs",
        indexes = @Index(
                name = "idx_pr_check_runs_pr_github",
                columnList = "pr_id, github_id"))
@Getter
@Setter
public class PrCheckRunEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    private Long prId;

    @Column(name = "github_id")
    private Long githubId;

    private String name;

    private String status;

    private String conclusion;

    private String detailsUrl;

    private String source;

    @Column(name = "completed_at")
    private Instant completedAt;

    public PrCheckRunEntity() {}

    public PrCheckRunState toDomain()
    {
        return new PrCheckRunState(
                getGithubId(),
                getName(),
                getStatus(),
                getConclusion(),
                getDetailsUrl(),
                getSource(),
                getCompletedAt());
    }

    public PrCheckRunEntity updateFrom(long prId, PrCheckRunState check)
    {
        setPrId(prId);
        setGithubId(check.githubId());
        setName(check.name());
        setStatus(check.status());
        setConclusion(check.conclusion());
        setDetailsUrl(check.detailsUrl());
        setSource(check.source());
        setCompletedAt(check.completedAt());
        return this;
    }
}
