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

import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteWorkItemStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-work-items-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.EPOCH;

    private final String sourceIdentity = "ISSUE:github.com:" + UUID.randomUUID();

    @Autowired
    private SqliteWorkItemStore store;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void roundTripsHistoryAndSourceIdentityAndRejectsAStaleRevision()
    {
        WorkItem original = item("work", "workspace");
        store.insert(original);
        assertThat(store.find("work")).contains(original);
        assertThat(store.findByIdentity(sourceIdentity)).contains(original);
        WorkItem updated = new WorkItem(original.id(), original.workspaceId(), Optional.empty(), original.title(), original.kind(), "New scope",
                original.completionRule(), WorkItem.State.WORKING, 1, original.links(), original.history(), NOW, NOW.plusSeconds(1), WorkItem.Priority.HIGH, 2,
                ImmutableList.of("blocker"));
        assertThat(store.update(updated, 0)).isTrue();
        assertThat(store.update(updated, 0)).isFalse();
        assertThat(store.find("work")).contains(updated);
        assertThat(store.list("workspace")).containsExactly(updated);
        assertThat(store.list("other")).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ownershipConflictRollsBackTheEntireNewItemAcrossWorkspaces()
    {
        store.insert(item("owner", "workspace"));
        assertThatThrownBy(() -> store.insert(item("duplicate", "other"))).hasMessageContaining("already belongs");
        assertThat(store.find("duplicate")).isEmpty();
        assertThat(store.findByIdentity(sourceIdentity)).map(WorkItem::id).contains("owner");
    }

    private WorkItem item(String id, String workspaceId)
    {
        WorkItemLink source = new WorkItemLink(sourceIdentity, new GoalItem(GoalItemKind.ISSUE, "org/repo#1", "#1", "Fix", NOW));
        return new WorkItem(id, workspaceId, Optional.of("goal"), "Fix", WorkItem.Kind.DEVELOP, "Covers the bug", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(source), ImmutableList.of(new WorkItemEvent("op", "input", "CREATED", "User accepted this item", NOW)), NOW, NOW,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of());
    }
}
