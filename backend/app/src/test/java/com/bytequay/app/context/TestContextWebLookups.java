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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestContextWebLookups
{
    private static final String WORKSPACE = "workspace";
    private static final Instant SETTLED = Instant.parse("2026-09-30T00:00:00Z");

    private final ContextWebLookups lookups = new ContextWebLookups(new InMemoryAppSettingsStore(),
            new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()));

    @Test
    void keepsEachTurnsFirstCountForTheMostRecentTurns()
    {
        lookups.record(WORKSPACE, new ContextWebLookups.Turn("task", "turn", 3, SETTLED));
        lookups.record(WORKSPACE, new ContextWebLookups.Turn("task", "turn", 1, SETTLED.plusSeconds(5)));
        assertThat(lookups.find(WORKSPACE)).containsExactly(new ContextWebLookups.Turn("task", "turn", 3, SETTLED));

        for (int index = 0; index < ContextWebLookups.MAX_TURNS; index++) {
            lookups.record(WORKSPACE, new ContextWebLookups.Turn("task", "turn-" + index, index, SETTLED.plusSeconds(index + 1)));
        }
        assertThat(lookups.find(WORKSPACE)).hasSize(ContextWebLookups.MAX_TURNS);
        assertThat(lookups.find(WORKSPACE)).extracting(ContextWebLookups.Turn::turnId).doesNotContain("turn");
        assertThat(lookups.find("other")).isEmpty();
    }
}
