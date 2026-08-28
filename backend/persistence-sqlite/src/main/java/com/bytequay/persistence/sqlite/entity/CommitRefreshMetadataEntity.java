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

import com.bytequay.core.domain.CommitRefreshMetadata;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "commit_refresh_metadata")
@Getter
@Setter
public class CommitRefreshMetadataEntity
{
    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "last_attempt_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant lastAttemptAt;

    @Column(name = "last_succeeded_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant lastSucceededAt;

    @Column(columnDefinition = "TEXT")
    private String error;

    public CommitRefreshMetadata toDomain()
    {
        return new CommitRefreshMetadata(
                getWorkspaceId(),
                Optional.ofNullable(getLastAttemptAt()),
                Optional.ofNullable(getLastSucceededAt()),
                Optional.ofNullable(getError()));
    }
}
