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
package com.bytequay.app.agenda;

import com.bytequay.core.domain.AgendaItem;
import com.bytequay.core.domain.AgendaRun;
import com.bytequay.core.domain.AgendaTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgendaReply
{
    private static final Map<String, String> SERVED = ImmutableMap.<String, String>builder()
            .put("work:w22", "epics")
            .put("task:t1", "tasks")
            .put("pr:trinodb/trino#31402", "tasks")
            .put("pr:trinodb/trino#31377", "asks")
            .put("pr:trinodb/trino-gateway#612", "mine")
            .put("epic:E1", "epics")
            .put("pr:trinodb/trino#31390", "history")
            .buildOrThrow();
    private static final Set<String> MINE = ImmutableSet.of("pr:trinodb/trino-gateway#612");

    private final ObjectMapper mapper = AgendaFixtures.mapper();
    private final AgendaSnapshot snapshot = AgendaFixtures.friday(0);

    @Test
    void keepsItemsWithServedRefsAndResolvesWhereTheyOpen()
    {
        AgendaReply reply = parse("""
                Here is the plan:
                ```json
                {"summary": "Your conflict-retry fix merged last night.",
                 "items": [
                   {"ref": "work:w22", "action": "start", "title": "Start “Expose snapshot id in metadata”", "label": "Expose snapshot id", "why": "It is next and unblocks 2 items."},
                   {"ref": "pr:trinodb/trino#31377", "action": "review", "title": "Review #31377 Add Iceberg metadata cache", "why": "Asked 3 days ago."}
                 ]}
                ```
                """);

        assertThat(reply.summary()).isEqualTo("Your conflict-retry fix merged last night.");
        assertThat(reply.items()).extracting(AgendaItem::ref).containsExactly("work:w22", "pr:trinodb/trino#31377");
        AgendaItem start = reply.items().getFirst();
        assertThat(start.action()).isEqualTo(AgendaItem.Action.START);
        assertThat(start.label()).isEqualTo("Expose snapshot id");
        assertThat(start.target()).isEqualTo(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w22"));
        assertThat(start.workspaceId()).contains("ws1");
        assertThat(start.at()).contains(AgendaFixtures.NOW.minusSeconds(32400));
        // Without a label, the chip shows the title cut to its length.
        assertThat(reply.items().get(1).label()).isEqualTo("Review #31377 Add Iceberg metad…").hasSize(32);
        assertThat(reply.picked()).extracting(AgendaRun.Pick::kind).containsExactly("epics", "asks");
        assertThat(reply.dropped()).isEmpty();
    }

    @Test
    void dropsWhatTheAppCannotStandBehindAndSaysWhy()
    {
        AgendaReply reply = parse("""
                {"summary": "Busy day.", "items": [
                  {"ref": "pr:invented/repo#9", "action": "review", "title": "Review a made-up PR", "why": "x"},
                  {"ref": "pr:unserved/repo#1", "action": "review", "title": "Review a PR no page named", "why": "x"},
                  {"ref": "pr:trinodb/trino-gateway#612", "action": "review", "title": "Review your own PR", "why": "x"},
                  {"ref": "epic:E1", "action": "merge", "title": "Merge an Epic", "why": "x"},
                  {"ref": "task:t1", "action": "sing", "title": "Sing", "why": "x"},
                  {"ref": "task:t1", "action": "answer", "title": "", "why": "x"},
                  {"ref": "task:t1", "action": "answer", "title": "Answer the retry question", "why": "x"},
                  {"ref": "task:t1", "action": "answer", "title": "Answer it again", "why": "x"},
                  {"ref": "pr:trinodb/trino#31402", "action": "reply", "title": "Reply on #31402", "why": "x"},
                  {"ref": "pr:trinodb/trino#31377", "action": "review", "title": "Review #31377", "why": "x"},
                  {"ref": "pr:trinodb/trino-gateway#612", "action": "merge", "title": "Merge #612", "why": "x"},
                  {"ref": "epic:E1", "action": "decide", "title": "Decide on two proposals", "why": "x"},
                  {"ref": "work:w22", "action": "start", "title": "Start the next item", "why": "x"}
                ]}
                """);

        assertThat(reply.items()).extracting(AgendaItem::title)
                .containsExactly("Answer the retry question", "Reply on #31402", "Review #31377", "Merge #612", "Decide on two proposals");
        assertThat(reply.dropped()).extracting(drop -> drop.ref() + " " + drop.reason()).containsExactly(
                "pr:invented/repo#9 unknown ref",
                "pr:unserved/repo#1 unknown ref",
                "pr:trinodb/trino-gateway#612 action does not fit",
                "epic:E1 action does not fit",
                "task:t1 unknown action",
                "task:t1 no title",
                "task:t1 duplicate",
                "work:w22 over five");
    }

    @Test
    void keepsTheRecapOnlyOnTheRecapDayWithServedRefs()
    {
        AgendaReply reply = parse("""
                {"summary": "Quiet.", "items": [], "recap": {"text": "Merged 3 PRs.", "merged": ["pr:trinodb/trino#31390", "pr:unserved/repo#1"],
                 "slipped": ["pr:trinodb/trino#31377"]}}
                """);

        assertThat(reply.items()).isEmpty();
        assertThat(reply.recap()).hasValueSatisfying(recap -> {
            assertThat(recap.text()).isEqualTo("Merged 3 PRs.");
            assertThat(recap.merged()).extracting(link -> link.ref()).containsExactly("pr:trinodb/trino#31390");
            assertThat(recap.slipped()).extracting(link -> link.ref()).containsExactly("pr:trinodb/trino#31377");
        });

        AgendaSnapshot thursday = new AgendaSnapshot(new AgendaSnapshot.Overview("2026-10-15", "THURSDAY", false, AgendaFixtures.SINCE, snapshot.overview().counts(),
                snapshot.overview().newlyWatched()), snapshot.changes(), snapshot.mine(), snapshot.tasks(), snapshot.asks(), snapshot.epics(), snapshot.history(),
                snapshot.week(), snapshot.refs());
        assertThat(AgendaReply.parse("{\"summary\":\"\",\"items\":[],\"recap\":{\"text\":\"x\"}}", thursday, SERVED, MINE, mapper).recap()).isEmpty();
    }

    @Test
    void refusesAReplyWithNoAgenda()
    {
        assertThatThrownBy(() -> parse("I could not read the tool.")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no agenda");
        assertThatThrownBy(() -> parse("{\"summary\": ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no agenda");
        assertThatThrownBy(() -> parse("{\"summary\": } x {")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid JSON");
    }

    private AgendaReply parse(String reply)
    {
        return AgendaReply.parse(reply, snapshot, SERVED, MINE, mapper);
    }
}
