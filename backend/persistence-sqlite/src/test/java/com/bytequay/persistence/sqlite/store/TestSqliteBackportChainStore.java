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

import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChain.Acceptance;
import com.bytequay.core.backport.BackportChain.Hop;
import com.bytequay.core.backport.BackportChain.Route;
import com.bytequay.core.backport.BackportChain.State;
import com.bytequay.core.cherrypick.MergedPullRequestRange;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteBackportChainStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-chain-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteBackportChainStore store;

    @Autowired
    private jakarta.persistence.EntityManager entities;

    @Autowired
    private SqliteWorkspacePurge purge;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void retainsAuthorityMergeEvidenceChildrenAndStopAcrossReads()
    {
        var chain = store.createOrGet(chain());
        var source = new MergedPullRequestRange(PullRequestRef.of("test", "p4", 12), 4, "main", Instant.EPOCH,
                "b".repeat(40), ImmutableList.of("c".repeat(40)), "a".repeat(40), ImmutableList.of("b".repeat(40)));
        var hop = chain.hops().getFirst().withSource(source)
                .withPlan(new BackportChain.Plan("d".repeat(40), new com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft("Backport", "Saved")))
                .withExecution(UUID.randomUUID().toString()).publishing();
        var saved = store.save(chain.withHop(0, hop, Instant.EPOCH).withState(State.STOPPING, Optional.of("Stop requested"), Instant.EPOCH));
        entities.clear();
        assertThat(store.find(chain.id())).contains(saved);
        assertThat(purge.hasActiveWorkflows("p4")).isTrue();
        assertThat(purge.hasActiveWorkflows("p3")).isTrue();
        assertThat(saved.version()).isGreaterThan(chain.version());
        assertThat(store.list()).contains(saved);
        assertThat(store.createOrGet(chain)).isEqualTo(saved);
        assertThatThrownBy(() -> store.save(chain.withState(State.ACTIVE, Optional.empty(), Instant.EPOCH)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version changed");
        store.save(saved.withState(State.STOPPED, Optional.empty(), Instant.EPOCH));
        assertThat(purge.hasActiveWorkflows("p3")).isFalse();
    }

    private static BackportChain chain()
    {
        return new BackportChain(UUID.randomUUID().toString(), "p4", "request", "source", "task-branch", route(4), Optional.empty(), false,
                ImmutableList.of(new Acceptance("request-key", "turn", ImmutableList.of("p3"))),
                ImmutableList.of(new Hop(route(3), "codex", Optional.of("model"), Optional.of("high"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty(), false, false)),
                State.ACTIVE, Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static Route route(int number)
    {
        var repo = RepoRef.of("test", "p" + number);
        return new Route("p" + number, number, repo, number, repo, "https://github.com/" + repo.fullName() + ".git",
                "https://github.com/" + repo.fullName() + ".git", "origin", "main");
    }
}
