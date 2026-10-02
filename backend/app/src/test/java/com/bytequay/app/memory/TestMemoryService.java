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
package com.bytequay.app.memory;

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static com.bytequay.app.memory.MemoryFixtures.pullRequestSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMemoryService
{
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final MemoryService memories = new MemoryService(store, settings, new ObjectMapper(), new Clock()
    {
        @Override
        public ZoneId getZone()
        {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now.get();
        }
    });

    @Test
    void numbersAreNeverReusedAfterADelete()
    {
        Memory first = create("First rule");
        Memory second = create("Second rule");
        assertThat(ImmutableList.of(first.id(), second.id())).containsExactly("m1", "m2");
        memories.delete("workspace", "m2");
        assertThat(create("Third rule").id()).isEqualTo("m3");
        assertThat(settings.get(MemoryService.LAST_NUMBER)).contains("3");
    }

    @Test
    void editsAreOptimisticAndMakeTheMemoryTheUsers()
    {
        Memory memory = create("Keep the old name working.").reported(new MemoryReport("Wrong", "", "task", NOW), NOW);
        store.save(memory);
        assertThatThrownBy(() -> memories.edit("workspace", "m1", 7, "Text")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed since you opened it");

        Memory edited = memories.edit("workspace", "m1", 1, "  Keep the old name\nwith @LegacyConfig.  ");
        assertThat(edited.text()).isEqualTo("Keep the old name with @LegacyConfig.");
        assertThat(edited.origin().user()).isTrue();
        assertThat(edited.reports()).isEmpty();

        Memory restored = memories.restore("workspace", "m1", 2);
        assertThat(restored.text()).isEqualTo("Keep the old name working.");
        assertThatThrownBy(() -> memories.restore("workspace", "m1", 2)).hasMessageContaining("changed since you opened it");
        assertThatThrownBy(() -> memories.edit("other", "m1", 3, "Text")).hasMessageContaining("is not in this workspace");
    }

    @Test
    void keepSettlesReportsWithoutChangingTheText()
    {
        create("Rule");
        memories.report("workspace", "m1", "The code changed", "path(x)", "task");
        assertThat(store.find("m1").orElseThrow().flagged()).isTrue();
        Memory kept = memories.keep("workspace", "m1");
        assertThat(kept.flagged()).isFalse();
        assertThat(kept.text()).isEqualTo("Rule");
        assertThat(kept.version()).isEqualTo(1);
    }

    @Test
    void aDeleteCanBeUndoneForTenMinutesInItsWorkspace()
    {
        create("Rule");
        memories.delete("workspace", "m1");
        assertThat(store.find("m1")).isEmpty();
        assertThatThrownBy(() -> memories.undoDelete("other", "m1")).hasMessageContaining("can no longer be undone");
        assertThat(memories.undoDelete("workspace", "m1").text()).isEqualTo("Rule");
        assertThat(store.find("m1")).isPresent();

        memories.delete("workspace", "m1");
        now.set(NOW.plus(Duration.ofMinutes(11)));
        assertThatThrownBy(() -> memories.undoDelete("workspace", "m1")).hasMessageContaining("can no longer be undone");
    }

    @Test
    void switchesStayOnUntilTheUserTurnsThemOff()
    {
        assertThat(memories.switches("workspace")).isEqualTo(MemorySwitches.ON);
        memories.saveSwitches("workspace", new MemorySwitches(false, true));
        assertThat(memories.switches("workspace")).isEqualTo(new MemorySwitches(false, true));
        assertThat(memories.switches("other")).isEqualTo(MemorySwitches.ON);
        settings.put(MemoryService.SWITCHES + "broken", "{");
        assertThat(memories.switches("broken")).isEqualTo(MemorySwitches.ON);
    }

    @Test
    void specsBecomeMemoriesThatWidenToNewDirectoriesButNeverChangeTheUsers()
    {
        String spec = "https://github.com/delta-io/delta/blob/master/PROTOCOL.md";
        memories.rememberSpecs("workspace", "plugin/trino-delta-lake/", ImmutableList.of(spec, spec), "task-1");
        Memory saved = memories.list("workspace").getFirst();
        assertThat(saved.kind()).isEqualTo(Memory.Kind.SPEC);
        assertThat(saved.scope()).containsExactly("plugin/trino-delta-lake/");
        assertThat(saved.origin().description()).isEqualTo("Spec check of plugin/trino-delta-lake/");

        memories.rememberSpecs("workspace", "plugin/trino-delta-lake/", ImmutableList.of(spec), "task-2");
        memories.rememberSpecs("workspace", "lib/trino-delta-common/", ImmutableList.of(spec), "task-3");
        Memory widened = memories.list("workspace").getFirst();
        assertThat(widened.scope()).containsExactly("plugin/trino-delta-lake/", "lib/trino-delta-common/");
        assertThat(widened.previous().orElseThrow().scope()).containsExactly("plugin/trino-delta-lake/");

        memories.edit("workspace", widened.id(), widened.version(), "The Delta protocol governs these modules.");
        memories.rememberSpecs("workspace", "plugin/trino-hive/", ImmutableList.of(spec), "task-4");
        assertThat(memories.list("workspace")).singleElement().satisfies(memory -> assertThat(memory.scope()).doesNotContain("plugin/trino-hive/"));
    }

    @Test
    void aFullWorkspaceRefusesNewMemoriesButSpecChecksGoOn()
    {
        for (int number = 0; number < MemoryService.MAX_WORKSPACE_MEMORIES; number++) {
            create("Rule " + number);
        }
        assertThatThrownBy(() -> create("One more")).hasMessageContaining("at most 300 memories");
        memories.rememberSpecs("workspace", "plugin/", ImmutableList.of("https://iceberg.apache.org/spec/"), "task");
        assertThat(memories.list("workspace")).hasSize(MemoryService.MAX_WORKSPACE_MEMORIES);
    }

    private Memory create(String text)
    {
        return memories.create("workspace", Memory.Kind.RULE, text, ImmutableList.of(), ImmutableList.of(pullRequestSource(31201, "alice")),
                new Memory.Origin(false, "Learn from my PRs", Optional.of("harvest-task")));
    }
}
