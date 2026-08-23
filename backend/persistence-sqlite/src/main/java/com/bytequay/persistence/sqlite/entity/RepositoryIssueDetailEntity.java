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

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.IssueTimelineListConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "repository_issue_details")
public class RepositoryIssueDetailEntity
{
    @Id
    private String id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "issue_number", nullable = false)
    private Integer issueNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = IssueTimelineListConverter.class)
    private List<PrTimelineEvent> timeline;

    @Column(name = "current_type")
    private String currentType;

    @Column(name = "available_types", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = StringListConverter.class)
    private List<String> availableTypes;

    @Column(name = "refreshed_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant refreshedAt;

    public RepositoryIssueDetailEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getRepositoryId() { return repositoryId; }
    public void setRepositoryId(Long repositoryId) { this.repositoryId = repositoryId; }

    public Integer getIssueNumber() { return issueNumber; }
    public void setIssueNumber(Integer issueNumber) { this.issueNumber = issueNumber; }

    public List<PrTimelineEvent> getTimeline() { return timeline; }
    public void setTimeline(List<PrTimelineEvent> timeline) { this.timeline = timeline; }

    public String getCurrentType() { return currentType; }
    public void setCurrentType(String currentType) { this.currentType = currentType; }

    public List<String> getAvailableTypes() { return availableTypes; }
    public void setAvailableTypes(List<String> availableTypes) { this.availableTypes = availableTypes; }

    public Instant getRefreshedAt() { return refreshedAt; }
    public void setRefreshedAt(Instant refreshedAt) { this.refreshedAt = refreshedAt; }

    public RepositoryIssueDetail toDomain()
    {
        return new RepositoryIssueDetail(
                repositoryId,
                issueNumber,
                timeline,
                currentType,
                availableTypes,
                Optional.ofNullable(refreshedAt));
    }
}
