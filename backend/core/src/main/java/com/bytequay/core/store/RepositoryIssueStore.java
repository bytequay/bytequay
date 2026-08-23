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
package com.bytequay.core.store;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RepositoryIssueStore
{
    RepositoryIssuePage findPage(long repositoryId, RepoRef repository, int page, int pageSize);

    Optional<RepositoryIssue> find(long repositoryId, int issueNumber);

    void saveSyncPage(long repositoryId, List<RepositoryIssue> issues, String syncMarker);

    void completeSync(long repositoryId, RepoRef repository, String syncMarker, Instant syncedAt);

    void updateMetadata(long repositoryId, int issueNumber, List<String> assignees, List<String> labels, Instant updatedAt);
}
