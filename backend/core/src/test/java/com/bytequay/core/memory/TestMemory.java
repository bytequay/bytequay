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
package com.bytequay.core.memory;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMemory
{
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final MemorySource SOURCE = MemorySource.pullRequest("https://github.com/owner/repository/pull/31201", "Docs, please.", NOW,
            new MemorySource.PullRequestPart(31201, MemorySource.Part.REVIEW, "7", "alice", true, true, false, false));

    @Test
    void matchesPrefixesFilesGlobsAndTheDirectoriesAboveThem()
    {
        Memory memory = memory("Rule", ImmutableList.of("plugin/storage/", "core/spi/pom.xml", "**/Test*.java"));
        assertThat(memory.match("plugin/storage/src/DeltaLakeWriter.java")).isEqualTo(OptionalInt.of(15));
        assertThat(memory.match("plugin/storage")).isEqualTo(OptionalInt.of(15));
        assertThat(memory.match("plugin/")).isEqualTo(OptionalInt.of(15));
        assertThat(memory.match("core/spi/pom.xml")).isEqualTo(OptionalInt.of(16));
        assertThat(memory.match("plugin/files/src/test/java/TestFiles.java")).isEqualTo(OptionalInt.of(13));
        assertThat(memory.match("plugin/files/src/main/java/Files.java")).isEmpty();
        assertThat(memory.match("plugin/storage-extra/x")).isEmpty();
        assertThat(memory("Rule", ImmutableList.of()).match("anything")).isEmpty();
    }

    @Test
    void rejectsLineBreaksLongTextAndPathsOutsideTheRepository()
    {
        assertThatThrownBy(() -> memory("First\n\nSecond", ImmutableList.of())).hasMessageContaining("one paragraph");
        assertThatThrownBy(() -> memory("é".repeat(301), ImmutableList.of())).hasMessageContaining("exceeds 600 UTF-8 bytes");
        assertThatThrownBy(() -> memory("Rule", ImmutableList.of("../secrets/"))).hasMessageContaining("must not contain . or ..");
        assertThatThrownBy(() -> memory("Rule", ImmutableList.of("/etc/"))).hasMessageContaining("relative to the repository root");
        assertThatThrownBy(() -> memory("Rule", ImmutableList.of("a/", "a/"))).hasMessageContaining("duplicates");
        assertThatThrownBy(() -> new Memory("12", "workspace", Memory.Kind.RULE, "Title", "Rule", ImmutableList.of(), ImmutableList.of(SOURCE),
                Memory.Origin.USER_EDIT, 1, Optional.empty(), 0, 0, ImmutableList.of(), NOW, NOW)).hasMessageContaining("not m followed by a number");
        assertThatThrownBy(() -> MemorySource.pullRequest("http://github.com/x", "Quote", NOW, SOURCE.pullRequest().orElseThrow())).hasMessageContaining("HTTPS");
        MemorySource words = MemorySource.user("Keep refactors in their own commits.", NOW, new MemorySource.UserPart(MemorySource.Where.TASK_MESSAGE, "task", "event"));
        assertThat(words.key()).isEqualTo("user:TASK_MESSAGE:task:event");
        assertThatThrownBy(() -> new MemorySource(MemorySource.Kind.USER, Optional.of("https://github.com/x"), "Words", NOW, Optional.empty(), Optional.empty(),
                words.user(), Optional.empty())).hasMessageContaining("only a pull request or page source has an address");
        assertThatThrownBy(() -> MemorySource.user(" ", NOW, words.user().orElseThrow())).hasMessageContaining("a USER source quotes the words it rests on");
        assertThatThrownBy(() -> new MemorySource.CommandPart("task", "turn", "make test", ".", "abc")).hasMessageContaining("full commit ID");
    }

    @Test
    void editsMakeTheMemoryTheUsersAndRestoreSwapsVersions()
    {
        Memory agent = memory("Keep the old name working.", ImmutableList.of("plugin/"))
                .reported(new MemoryReport("Wrong", "", "task", NOW), NOW);
        Memory edited = agent.edited("Legacy config names", "Keep the old name with @LegacyConfig.", NOW.plusSeconds(1));
        assertThat(edited.origin()).isEqualTo(Memory.Origin.USER_EDIT);
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.reports()).isEmpty();
        assertThat(edited.previous()).contains(new Memory.Previous("Renamed properties", "Keep the old name working.", ImmutableList.of("plugin/")));

        Memory restored = edited.restored(NOW.plusSeconds(2));
        assertThat(restored).extracting(Memory::title, Memory::text).containsExactly("Renamed properties", "Keep the old name working.");
        assertThat(restored.previous().orElseThrow().title()).isEqualTo("Legacy config names");
        assertThat(restored.previous().orElseThrow().text()).isEqualTo("Keep the old name with @LegacyConfig.");
        assertThat(restored.restored(NOW.plusSeconds(3)).text()).isEqualTo(edited.text());
        assertThatThrownBy(() -> agent.restored(NOW)).hasMessageContaining("no previous version");
    }

    @Test
    void titlesAreOneShortLineAndLongerWordsAreCutAtAWord()
    {
        Memory.checkTitle("é".repeat(Memory.MAX_TITLE));
        assertThatThrownBy(() -> Memory.checkTitle(" ")).hasMessageContaining("1 to 80 characters");
        assertThatThrownBy(() -> Memory.checkTitle("x".repeat(81))).hasMessageContaining("1 to 80 characters");
        assertThatThrownBy(() -> Memory.checkTitle("Two\nlines")).hasMessageContaining("one line");
        assertThatThrownBy(() -> new Memory.Previous("", "Rule", ImmutableList.of())).hasMessageContaining("1 to 80 characters");

        assertThat(Memory.titleFrom("  Close\ton failure\n overwrites a checkpoint ")).isEqualTo("Close on failure overwrites a checkpoint");
        assertThat(Memory.titleFrom("When changing streaming Delta Lake checkpoint writes, publish only after the read/write phase succeeds"))
                .isEqualTo("When changing streaming Delta Lake checkpoint writes, publish only after the…");
        assertThat(Memory.titleFrom("Specification at example.com/" + "x".repeat(100))).hasSize(Memory.MAX_TITLE).endsWith("x…");
    }

    @Test
    void keepsTheNewestReports()
    {
        Memory memory = memory("Rule", ImmutableList.of());
        for (int index = 0; index < 10; index++) {
            memory = memory.reported(new MemoryReport("Reason " + index, "", "task", NOW), NOW);
        }
        assertThat(memory.reports()).hasSize(Memory.MAX_REPORTS);
        assertThat(memory.reports().getFirst().reason()).isEqualTo("Reason 2");
        assertThat(memory.reports()).isNotEmpty();
        assertThat(memory.kept(NOW).reports()).isEmpty();
    }

    private static Memory memory(String text, List<String> scope)
    {
        return new Memory("m1", "workspace", Memory.Kind.RULE, "Renamed properties", text, scope, ImmutableList.of(SOURCE),
                new Memory.Origin(false, "Learn from my PRs", Optional.of("task")), 1, Optional.empty(), 0, 0, ImmutableList.of(), NOW, NOW);
    }
}
