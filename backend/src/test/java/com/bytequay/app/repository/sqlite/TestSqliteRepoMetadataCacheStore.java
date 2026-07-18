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

import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.repository.RepoMetadataCacheStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteRepoMetadataCacheStore
{
    @Autowired
    private RepoMetadataCacheStore store;

    @Test
    void roundTripsRepositoryChoices()
    {
        String repo = "owner/repo-" + UUID.randomUUID();
        Instant fetchedAt = Instant.parse("2026-07-19T00:00:00Z");
        List<GitHubUserMatch> users = List.of(new GitHubUserMatch("alice", "Alice", "avatar"));
        List<IssueDetail.Label> labels = List.of(new IssueDetail.Label("jdbc", "007f8b"));

        store.save(repo, users, labels, fetchedAt);

        RepoMetadataCacheStore.Snapshot snapshot = store.find(repo).orElseThrow();
        assertThat(snapshot.users()).isEqualTo(users);
        assertThat(snapshot.labels()).isEqualTo(labels);
        assertThat(snapshot.fetchedAt()).isEqualTo(fetchedAt);
    }
}
