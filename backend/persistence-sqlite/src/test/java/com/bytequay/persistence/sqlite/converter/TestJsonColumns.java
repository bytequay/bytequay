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
package com.bytequay.persistence.sqlite.converter;

import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.EpicProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.assertj.core.api.Assertions.assertThat;

class TestJsonColumns
{
    /**
     * A background thread in the packaged jar can carry a context class loader that reaches no
     * service file, which is how a stored CI Autofix attempt once became unwritable.
     */
    @Test
    void writesOptionalAndInstantWithoutADiscoverableModule()
            throws Exception
    {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
        try {
            ObjectMapper mapper = JsonColumns.mapper().disable(WRITE_DATES_AS_TIMESTAMPS);
            assertThat(mapper.writeValueAsString(Optional.of("value"))).isEqualTo("\"value\"");
            assertThat(mapper.writeValueAsString(Optional.empty())).isEqualTo("null");
            assertThat(mapper.writeValueAsString(Instant.parse("2026-09-21T17:20:16Z"))).isEqualTo("\"2026-09-21T17:20:16Z\"");
        }
        finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void keepsAProposalsContextAndReadsProposalsSavedBeforeIt()
            throws Exception
    {
        ObjectMapper mapper = JsonColumns.mapper();
        Epic detailed = epic(new EpicProposal("proposal-1", "Invalidate cached metadata on DDL", "ALTER TABLE reads the table again",
                "Drop a table's cached metadata when DDL changes it.", Optional.of("Stale metadata survives ALTER TABLE."), Optional.empty(), Optional.of("1. ALTER a table")));
        assertThat(mapper.readValue(mapper.writeValueAsString(detailed), Epic.class)).isEqualTo(detailed);

        EpicProposal plain = new EpicProposal("proposal-1", "Tune the cache", "Reads are faster", "", Optional.empty(), Optional.empty(), Optional.empty());
        String older = mapper.writeValueAsString(epic(plain))
                .replace(",\"description\":\"\"", "").replace(",\"background\":null", "").replace(",\"evidence\":null", "").replace(",\"reproduceSteps\":null", "");
        assertThat(older).doesNotContain("description", "background", "evidence", "reproduceSteps");
        assertThat(mapper.readValue(older, Epic.class)).isEqualTo(epic(plain));
    }

    private static Epic epic(EpicProposal proposal)
    {
        return Epic.of("ISSUE:github.com:1", "workspace").withProposals(ImmutableList.of(proposal));
    }
}
