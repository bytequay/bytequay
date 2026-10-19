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

import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactItem;
import com.bytequay.core.domain.ArtifactPage;
import com.bytequay.core.domain.ArtifactSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteArtifactStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-artifact-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant AT = Instant.parse("2026-10-19T10:00:00Z");

    @Autowired
    private SqliteArtifactStore store;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE, "create-drop");
    }

    @Test
    void keepsConversationsItemsInOrderAndPageVersionsApartFromTheirHtml()
    {
        // A whole second and a fraction of it: their text does not sort by time.
        Artifact older = Artifact.started("a1", "workspace", "Teach me position deletes", AT).working(true, AT);
        Artifact newer = Artifact.started("a2", "workspace", "Explain snapshots", AT.plusMillis(500));
        Artifact elsewhere = Artifact.started("a3", "other", "Elsewhere", AT);
        store.save(older);
        store.save(newer);
        store.save(elsewhere);
        ArtifactItem asked = ArtifactItem.of("a1", ArtifactItem.Kind.USER, "Teach me position deletes", AT);
        ArtifactItem read = ArtifactItem.of("a1", ArtifactItem.Kind.ACTIVITY, "DeleteManager.java · 1 step", AT);
        ArtifactItem offer = ArtifactItem.offered("a1", "Deletes on read", "How a scan skips deleted rows.", AT);
        store.add("workspace", asked);
        store.add("workspace", read);
        store.add("workspace", offer);
        store.update(read.withText("DeleteManager.java · 2 steps"));
        store.update(offer.answered(ArtifactItem.Offer.State.ACCEPTED));
        ArtifactPage first = new ArtifactPage("v1", "a1", "p1", 1, "Deletes on read", "abc123", AT);
        ArtifactPage second = new ArtifactPage("v2", "a1", "p1", 2, "Deletes on read", "abc123", AT.plusSeconds(60));
        store.savePage("workspace", first, "<p>one</p>");
        store.savePage("workspace", second, "<p>two</p>");
        flush();

        assertThat(store.list("workspace")).containsExactly(newer, older);
        assertThat(store.working()).containsExactly(older);
        assertThat(store.find("workspace", "a3")).isEmpty();
        assertThat(store.items("workspace", "a1")).containsExactly(asked, read.withText("DeleteManager.java · 2 steps"), offer.answered(ArtifactItem.Offer.State.ACCEPTED));
        assertThat(store.items("other", "a1")).isEmpty();
        assertThat(store.pages("workspace", "a1")).containsExactly(first, second);
        assertThat(store.pageHtml("workspace", "a1", "v2")).contains("<p>two</p>");
        assertThat(store.pageHtml("other", "a1", "v2")).isEmpty();

        Artifact ended = older.working(false, AT.plusSeconds(120)).continuing(new ArtifactSession("claude", new DiscussionSession(1, "session-1")), AT.plusSeconds(120));
        store.save(ended);
        flush();
        assertThat(store.find("workspace", "a1")).contains(ended);
        assertThat(store.working()).isEmpty();

        store.deleteWorkspace("workspace");
        flush();
        assertThat(store.list("workspace")).isEmpty();
        assertThat(store.items("workspace", "a1")).isEmpty();
        assertThat(store.pages("workspace", "a1")).isEmpty();
        assertThat(store.list("other")).containsExactly(elsewhere);
    }

    private void flush()
    {
        entities.flush();
        entities.clear();
    }
}
