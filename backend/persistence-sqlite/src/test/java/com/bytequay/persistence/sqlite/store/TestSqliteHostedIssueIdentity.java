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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteHostedIssueIdentity
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqliteRepositoryIssueStore issues;

    @Test
    void sameRemoteIssueIdInDifferentHostingRepositoriesStaysIndependent()
    {
        RepositoryIssue first = issue("https://github.com/group/repo/issues/7", "GitHub");
        RepositoryIssue second = issue("https://gitlab.example.com/group/repo/-/issues/7", "GitLab");
        issues.saveSyncPage(101, ImmutableList.of(first));
        issues.saveSyncPage(102, ImmutableList.of(second));
        issues.completeSync(101, ImmutableSet.of(70L), Instant.EPOCH);
        issues.completeSync(102, ImmutableSet.of(70L), Instant.EPOCH);

        assertThat(issues.find(101, 7)).contains(first);
        assertThat(issues.find(102, 7)).contains(second);
        issues.completeSync(101, ImmutableSet.of(), Instant.EPOCH);
        assertThat(issues.find(101, 7)).isEmpty();
        assertThat(issues.findPage(102, RepoRef.of("group", "repo"), 0, 50).issues()).containsExactly(second);
    }

    private static RepositoryIssue issue(String url, String title)
    {
        return new RepositoryIssue(70, 7, title, "body", "alice", "", url, ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH, Instant.EPOCH);
    }
}
