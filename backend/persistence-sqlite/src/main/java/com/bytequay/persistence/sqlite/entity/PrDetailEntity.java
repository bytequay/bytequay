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

import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import com.bytequay.persistence.sqlite.converter.StringMapConverter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "pr_detail")
@Getter
@Setter
public class PrDetailEntity
{
    @Id
    private Long prId;

    private String title;

    private String htmlUrl;

    private String state;

    private String author;

    private String authorAvatarUrl;

    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant mergedAt;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> labels;

    @Convert(converter = StringMapConverter.class)
    private Map<String, String> labelColors;

    @Convert(converter = StringListConverter.class)
    private List<String> assignees;

    private String milestone;

    @Convert(converter = StringListConverter.class)
    private List<String> unavailableSections;

    @Column(nullable = false)
    private boolean draft;

    private Boolean mergeable;

    private String mergeableState;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    @Column(nullable = false)
    private int changedFiles;

    @Column(name = "requested_reviewers")
    @Convert(converter = StringListConverter.class)
    private List<String> requestedReviewers;

    private String headSha;

    @Column(name = "head_ref")
    private String headRef;

    @Column(name = "base_ref")
    private String baseRef;

    @Column(name = "viewer_can_write")
    private Boolean viewerCanWrite;

    private String etag;

    @Column(name = "check_runs_etag")
    private String checkRunsEtag;

    @Column(name = "commit_statuses_etag")
    private String commitStatusesEtag;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    public PrDetailEntity() {}

    public PrRawDetail toRawDetail()
    {
        List<String> requestedReviewers = getRequestedReviewers() != null ? getRequestedReviewers() : ImmutableList.of();
        return new PrRawDetail(
                getTitle(),
                getHtmlUrl(),
                getState(),
                getAuthor(),
                getAuthorAvatarUrl(),
                getCreatedAt(),
                getUpdatedAt(),
                getMergedAt(),
                getBody(),
                getLabels(),
                getLabelColors() != null ? getLabelColors() : ImmutableMap.of(),
                getAssignees() != null ? getAssignees() : ImmutableList.of(),
                getMilestone(),
                isDraft(),
                getMergeable(),
                getMergeableState(),
                getAdditions(),
                getDeletions(),
                getChangedFiles(),
                requestedReviewers.size(),
                requestedReviewers,
                getHeadSha(),
                getHeadRef(),
                getBaseRef());
    }
}
