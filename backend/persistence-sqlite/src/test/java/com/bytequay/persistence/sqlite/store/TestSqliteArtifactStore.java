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
import com.bytequay.core.domain.ArtifactOffer;
import com.bytequay.core.domain.ArtifactPage;
import com.bytequay.core.domain.ArtifactSession;
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
    void keepsConversationsEventsOffersAndPageVersionsApartFromTheirHtml()
    {
        // A whole second and a fraction of it: their text does not sort by time.
        Artifact older = Artifact.started("a1", "workspace", "Teach me position deletes", AT).working(true, AT);
        Artifact newer = Artifact.started("a2", "workspace", "Explain snapshots", AT.plusMillis(500));
        Artifact elsewhere = Artifact.started("a3", "other", "Elsewhere", AT);
        store.save(older);
        store.save(newer);
        store.save(elsewhere);
        store.addEvent("workspace", "a1", 1, "{\"sequence\":1}");
        store.addEvent("workspace", "a1", 2, "{\"sequence\":2}");
        store.addEvent("workspace", "a1", 3, "{\"sequence\":3}");
        ArtifactOffer offer = ArtifactOffer.offered("a1", "t2", "Deletes on read", "How a scan skips deleted rows.", AT);
        ArtifactOffer later = ArtifactOffer.offered("a1", "t3", "Equality deletes", "How equality deletes match rows.", AT);
        store.saveOffer("workspace", offer);
        store.saveOffer("workspace", later);
        store.saveOffer("workspace", offer.answered(ArtifactOffer.State.ACCEPTED));
        ArtifactPage first = new ArtifactPage("v1", "a1", "p1", 1, "Deletes on read", "abc123", AT, Optional.of("t3"));
        // Saved before turns were recorded.
        ArtifactPage second = new ArtifactPage("v2", "a1", "p1", 2, "Deletes on read", "abc123", AT.plusSeconds(60), Optional.empty());
        store.savePage("workspace", first, "<p>one</p>");
        store.savePage("workspace", second, "<p>two</p>");
        flush();

        assertThat(store.list("workspace")).containsExactly(newer, older);
        assertThat(store.working()).containsExactly(older);
        assertThat(store.find("workspace", "a3")).isEmpty();
        assertThat(store.events("workspace", "a1", 0, 10)).containsExactly("{\"sequence\":1}", "{\"sequence\":2}", "{\"sequence\":3}");
        assertThat(store.events("workspace", "a1", 1, 1)).containsExactly("{\"sequence\":2}");
        assertThat(store.events("other", "a1", 0, 10)).isEmpty();
        assertThat(store.lastEvent("workspace", "a1")).isEqualTo(3);
        assertThat(store.lastEvent("workspace", "a2")).isZero();
        assertThat(store.offers("workspace", "a1")).containsExactly(offer.answered(ArtifactOffer.State.ACCEPTED), later);
        assertThat(store.offers("other", "a1")).isEmpty();
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
        assertThat(store.events("workspace", "a1", 0, 10)).isEmpty();
        assertThat(store.offers("workspace", "a1")).isEmpty();
        assertThat(store.pages("workspace", "a1")).isEmpty();
        assertThat(store.list("other")).containsExactly(elsewhere);
    }

    private void flush()
    {
        entities.flush();
        entities.clear();
    }
}
