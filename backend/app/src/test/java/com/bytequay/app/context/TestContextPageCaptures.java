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
package com.bytequay.app.context;

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class TestContextPageCaptures
{
    private static final String WORKSPACE = "workspace";
    private static final String URL = "https://github.com/delta-io/delta/blob/master/PROTOCOL.md";
    private static final Instant SAVED = Instant.parse("2026-09-01T00:00:00Z");

    private final ContextPageCaptures pages = new ContextPageCaptures(new InMemoryAppSettingsStore(),
            new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()));

    @Test
    void flagsASourceOnlyWhenALaterCaptureOfItsPageHasDifferentText()
    {
        ContextSourceEvidence cited = page("cited", "a", SAVED);
        pages.record(WORKSPACE, new ContextPageCaptures.Page(URL, "a".repeat(64), SAVED.plusSeconds(60)));
        assertThat(ContextPageCaptures.changed(pages.find(WORKSPACE), cited)).isEmpty();

        pages.record(WORKSPACE, new ContextPageCaptures.Page(URL, "b".repeat(64), SAVED.plusSeconds(120)));
        // A capture that finishes after a newer one never replaces it.
        pages.record(WORKSPACE, new ContextPageCaptures.Page(URL, "a".repeat(64), SAVED.plusSeconds(90)));
        assertThat(pages.find(WORKSPACE)).containsExactly(new ContextPageCaptures.Page(URL, "b".repeat(64), SAVED.plusSeconds(120)));
        assertThat(ContextPageCaptures.changed(pages.find(WORKSPACE), cited)).contains(SAVED.plusSeconds(120));
        assertThat(ContextPageCaptures.changed(pages.find(WORKSPACE), page("resaved", "c", SAVED.plusSeconds(180)))).isEmpty();

        ContextSourceEvidence code = new ContextSourceEvidence("code", new ContextReference.RepositoryFile(1, RepoRef.of("delta-io", "delta"), "PROTOCOL.md", "a".repeat(40)),
                "Provenance", "1  text", ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(SAVED), Optional.of("a".repeat(64)), Optional.empty(), false);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 1, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()), ImmutableList.of(),
                ImmutableList.of(cited, code), Optional.empty());
        assertThat(pages.changed(WORKSPACE, notebook)).containsExactly(entry("cited", SAVED.plusSeconds(120)));
    }

    @Test
    void keepsTheMostRecentlyCapturedPages()
    {
        for (int index = 0; index <= ContextPageCaptures.MAX_PAGES; index++) {
            pages.record(WORKSPACE, new ContextPageCaptures.Page("https://example.com/page-" + index, "a".repeat(64), SAVED.plusSeconds(index)));
        }
        assertThat(pages.find(WORKSPACE)).hasSize(ContextPageCaptures.MAX_PAGES);
        assertThat(pages.find(WORKSPACE).getFirst().url()).isEqualTo("https://example.com/page-" + ContextPageCaptures.MAX_PAGES);
        assertThat(pages.find(WORKSPACE).getLast().url()).isEqualTo("https://example.com/page-1");
    }

    private static ContextSourceEvidence page(String id, String hash, Instant retrievedAt)
    {
        return new ContextSourceEvidence(id, new ContextReference.Document("page:1", URL), "Provenance", "Actions apply in order.",
                ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(retrievedAt), Optional.of(hash.repeat(64)), Optional.empty(), false);
    }
}
