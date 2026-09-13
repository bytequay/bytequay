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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.localreview.LocalReviewLearningAnchor;
import com.bytequay.core.localreview.LocalReviewMessage;
import com.bytequay.core.localreview.LocalReviewMessage.Author;
import com.bytequay.core.localreview.LocalReviewMessage.Routing;
import com.bytequay.core.localreview.LocalReviewThread;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
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

class TestSqliteLocalReviewStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-local-review-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteLocalReviewStore store;

    @Autowired
    private SqliteAgentTaskStore tasks;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE, "create-drop");
    }

    @Test
    void roundTripsPrivateEvidenceMessagesAndReplayKeysWithOptimisticVersions()
    {
        LocalReviewMessage question = new LocalReviewMessage("question", Author.USER, "Why this change?", Instant.EPOCH,
                Optional.of("stable-input"), Optional.of(Routing.SEND), Optional.empty(), Optional.of("Provider unavailable"));
        LocalReviewMessage answer = new LocalReviewMessage("answer", Author.AGENT, "It preserves the invariant", Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.of("turn"), Optional.empty());
        LocalReviewThread thread = new LocalReviewThread("thread", "task", "workspace", "owner/repo", 7, "src/Example.java", "LEFT", 3, 4,
                Optional.of("a".repeat(40)), Optional.of("b".repeat(40)), "old code\nsecond line", false, 0, Instant.EPOCH, Instant.EPOCH,
                ImmutableList.of(question, answer), ImmutableMap.of("operation", "fingerprint"));
        LocalReviewThread stored = store.insert(thread);
        entityManager.clear();
        assertThat(store.find("thread")).contains(stored);
        assertThat(store.listByTask("task")).containsExactly(stored);
        assertThat(store.listByPullRequest("owner/repo", 7)).containsExactly(stored);
        LocalReviewThread resolved = store.update(stored.update(true, stored.messages(), stored.operations(), Instant.EPOCH.plusSeconds(1)));
        assertThat(resolved.version()).isGreaterThan(stored.version());
        entityManager.clear();
        assertThat(store.find("thread").orElseThrow().resolved()).isTrue();
        assertThatThrownBy(() -> store.update(stored)).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void roundTripsLearningSnapshotWithoutAClaimedPullRequestOrCodeLocation()
    {
        LocalReviewLearningAnchor anchor = new LocalReviewLearningAnchor("summary-turn", "purpose", "What this change does", "Preserves nullable values");
        LocalReviewMessage question = new LocalReviewMessage("question", Author.USER, "Why allow null?", Instant.EPOCH,
                Optional.of("learning-input"), Optional.of(Routing.SEND), Optional.empty(), Optional.empty());
        LocalReviewThread thread = new LocalReviewThread("learning", "task", "workspace", "owner/repo", 0, "", "RIGHT", 0, 0,
                Optional.empty(), Optional.empty(), "", false, 0, Instant.EPOCH, Instant.EPOCH,
                ImmutableList.of(question), ImmutableMap.of("create", "fingerprint"), Optional.of(anchor));
        LocalReviewThread stored = store.insert(thread);
        entityManager.clear();
        assertThat(store.find("learning")).contains(stored);
        assertThat(store.listByTask("task")).containsExactly(stored);
        assertThat(store.listByPullRequest("owner/repo", 7)).isEmpty();
        LocalReviewThread resolved = store.update(stored.update(true, stored.messages(), stored.operations(), Instant.EPOCH.plusSeconds(1)));
        entityManager.clear();
        assertThat(store.find("learning")).contains(resolved);
        assertThat(resolved.learningAnchor()).contains(anchor);
        assertThatThrownBy(() -> new LocalReviewThread("invalid", "task", "workspace", "owner/repo", 7, "Example.java", "RIGHT", 1, 1,
                Optional.empty(), Optional.empty(), "code", false, 0, Instant.EPOCH, Instant.EPOCH, ImmutableList.of(), ImmutableMap.of(), Optional.of(anchor)))
                .hasMessageContaining("cannot claim a code location");
    }

    @Test
    void deletingATaskRemovesOnlyItsPrivateThreads()
    {
        TaskId taskId = TaskId.random();
        tasks.insert(AgentTask.created(taskId, Optional.of("workspace"), true, "Task", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("owner", "repo"), Path.of("/tmp/worktree"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH));
        LocalReviewThread thread = new LocalReviewThread("thread", taskId.toString(), "workspace", "owner/repo", 7, "Example.java", "RIGHT", 1, 1,
                Optional.empty(), Optional.empty(), "code", false, 0, Instant.EPOCH, Instant.EPOCH, ImmutableList.of(), ImmutableMap.of());
        LocalReviewThread other = new LocalReviewThread("other", TaskId.random().toString(), "workspace", "owner/repo", 7, "Example.java", "RIGHT", 1, 1,
                Optional.empty(), Optional.empty(), "code", false, 0, Instant.EPOCH, Instant.EPOCH, ImmutableList.of(), ImmutableMap.of());
        store.insert(thread);
        store.insert(other);
        entityManager.clear();
        tasks.delete(taskId);
        entityManager.clear();
        assertThat(store.find("thread")).isEmpty();
        assertThat(store.find("other")).contains(other);
    }
}
