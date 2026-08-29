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
package com.bytequay.app.git;

import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCommitHistoryRouting
{
    private static final WorkspaceRepositoryContext CONTEXT = new WorkspaceRepositoryContext(
            "workspace-a",
            Path.of("/managed/workspace-a/repository"),
            101,
            RepoRef.of("contributor", "trino"),
            OptionalLong.of(202),
            RepoRef.of("trinodb", "trino"),
            "upstream",
            "origin",
            "master",
            ImmutableList.of(
                    new RemoteSource(
                            "workspace-a",
                            "oss",
                            "https://github.com/trinodb/trino.git",
                            Optional.of(202L),
                            Optional.of(RepoRef.of("trinodb", "trino"))),
                    new RemoteSource("workspace-a", "myoss", "https://example.invalid/trino.git")));

    @Test
    void workspaceReadsTheFetchedOriginBranchWithProviderIdentity()
    {
        var resolved = CommitHistoryRouting.resolve(CONTEXT, new CommitHistoryRef(Source.WORKSPACE, "master"));
        assertThat(resolved.gitRef()).isEqualTo("origin/master");
        assertThat(resolved.repositoryId()).hasValue(101);
    }

    @Test
    void upstreamReadsTheLinkedRemote()
    {
        var resolved = CommitHistoryRouting.resolve(CONTEXT, new CommitHistoryRef(Source.UPSTREAM, "master"));
        assertThat(resolved.gitRef()).isEqualTo("upstream/master");
        assertThat(resolved.repositoryId()).hasValue(202);
        assertThat(resolved.repository()).contains(RepoRef.of("trinodb", "trino"));
    }

    @Test
    void identifiedExtraRemoteResolvesWithReusableProviderIdentity()
    {
        var resolved = CommitHistoryRouting.resolve(
                CONTEXT, new CommitHistoryRef(Source.REMOTE, "master", Optional.of("oss")));
        assertThat(resolved.gitRef()).isEqualTo("oss/master");
        assertThat(resolved.repositoryId()).hasValue(202);
        assertThat(resolved.repository()).contains(RepoRef.of("trinodb", "trino"));
    }

    @Test
    void unidentifiedExtraRemoteResolvesWithoutProviderIdentity()
    {
        var resolved = CommitHistoryRouting.resolve(
                CONTEXT, new CommitHistoryRef(Source.REMOTE, "master", Optional.of("myoss")));
        assertThat(resolved.gitRef()).isEqualTo("myoss/master");
        assertThat(resolved.repositoryId()).isEmpty();
        assertThat(resolved.repository()).isEmpty();
    }

    @Test
    void unknownRemoteIsRejected()
    {
        assertThatThrownBy(() -> CommitHistoryRouting.resolve(
                CONTEXT, new CommitHistoryRef(Source.REMOTE, "master", Optional.of("random"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remoteNamePresenceMustMatchTheSource()
    {
        assertThatThrownBy(() -> new CommitHistoryRef(Source.REMOTE, "master"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommitHistoryRef(Source.WORKSPACE, "master", Optional.of("oss")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
