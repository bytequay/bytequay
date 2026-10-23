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

import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgendaPages
{
    private final ObjectMapper mapper = AgendaFixtures.mapper();

    @Test
    void pagesHoldOnlyTheFieldsEachKindNames()
            throws Exception
    {
        AgendaPages pages = new AgendaPages(AgendaFixtures.friday(1), mapper);
        ReviewTool tool = pages.tool();
        assertThat(tool.name()).isEqualTo("agenda_context");
        assertThat(tool.childCallable()).isTrue();

        JsonNode overview = read(tool, "{\"kind\":\"overview\"}");
        assertThat(keys(overview)).isEqualTo(ImmutableSet.of("today", "weekday", "recapDay", "since", "counts", "calendar", "nextPage"));
        // A calendar that was not read says only so.
        assertThat(keys(overview.path("calendar"))).isEqualTo(ImmutableSet.of("state"));
        assertThat(keys(entry(tool, "changes"))).isEqualTo(ImmutableSet.of("ref", "title", "facts", "at"));
        assertThat(keys(entry(tool, "mine"))).isEqualTo(ImmutableSet.of("ref", "title", "draft", "review", "ci", "mergeable", "mergeBlocked", "quietSince"));
        assertThat(keys(entry(tool, "tasks"))).isEqualTo(ImmutableSet.of("ref", "kind", "title", "state", "waitingFor", "question", "idleSince", "pr", "work", "findings"));
        assertThat(keys(entry(tool, "asks"))).isEqualTo(ImmutableSet.of("ref", "why", "title", "from", "circle", "since", "lines", "ci"));
        assertThat(keys(entry(tool, "epics"))).isEqualTo(ImmutableSet.of("ref", "title", "done", "total", "blocked", "proposals", "newChildren", "ready"));
        JsonNode history = read(tool, "{\"kind\":\"history\"}");
        assertThat(keys(history)).isEqualTo(ImmutableSet.of("days", "week", "nextPage"));
        assertThat(history.path("days").path(0).path("items").path(0).path("outcome").asText()).isEqualTo("open");
    }

    @Test
    void pagesTwentyEntriesAtATime()
            throws Exception
    {
        ReviewTool tool = new AgendaPages(AgendaFixtures.friday(25), mapper).tool();

        JsonNode first = read(tool, "{\"kind\":\"changes\"}");
        assertThat(first.path("entries").size()).isEqualTo(20);
        assertThat(first.path("nextPage").asInt()).isEqualTo(1);
        JsonNode second = read(tool, "{\"kind\":\"changes\",\"page\":1}");
        assertThat(second.path("entries").size()).isEqualTo(5);
        assertThat(second.path("nextPage").isNull()).isTrue();
        assertThat(read(tool, "{\"kind\":\"changes\",\"page\":7}").path("entries").size()).isZero();
    }

    @Test
    void remembersEveryRefItHandsOutUnderTheFirstKindThatNamedIt()
            throws Exception
    {
        AgendaPages pages = new AgendaPages(AgendaFixtures.friday(0), mapper);
        ReviewTool tool = pages.tool();
        read(tool, "{\"kind\":\"tasks\"}");
        read(tool, "{\"kind\":\"history\"}");

        assertThat(pages.served()).containsEntry("task:t1", "tasks")
                // A task names its pull request and work item; the reply may pick those too.
                .containsEntry("pr:owner/repository#31402", "tasks")
                .containsEntry("work:w19", "tasks")
                .containsEntry("pr:owner/repository#31377", "history")
                .doesNotContainKey("pr:unserved/repo#1")
                .doesNotContainKey("epic:E1");
        assertThat(pages.calls()).extracting(call -> call.kind() + "/" + call.page()).containsExactly("tasks/0", "history/0");
        assertThat(pages.calls().getFirst().chars()).isPositive();
    }

    @Test
    void answersABadCallWithAnErrorItCanRepair()
            throws Exception
    {
        AgendaPages pages = new AgendaPages(AgendaFixtures.friday(0), mapper);
        ReviewTool tool = pages.tool();

        assertThat(read(tool, "{\"kind\":\"pull_request\"}").path("error").path("message").asText()).contains("kind must be one of");
        assertThat(read(tool, "{\"kind\":\"tasks\",\"ref\":\"task:t1\"}").path("error").path("message").asText()).contains("Unknown field: ref");
        assertThat(read(tool, "{\"kind\":\"tasks\",\"page\":-1}").path("error").path("message").asText()).contains("non-negative");
        assertThat(read(tool, "not json").path("error").path("message").asText()).isEqualTo("arguments are not JSON");
        assertThat(pages.served()).isEmpty();
        assertThat(pages.calls()).allSatisfy(call -> assertThat(call.error()).isPresent());
    }

    private JsonNode entry(ReviewTool tool, String kind)
            throws Exception
    {
        return read(tool, "{\"kind\":\"" + kind + "\"}").path("entries").path(0);
    }

    private JsonNode read(ReviewTool tool, String arguments)
            throws Exception
    {
        return mapper.readTree(tool.call().apply(arguments));
    }

    private static Set<String> keys(JsonNode node)
    {
        return ImmutableSet.copyOf(node.fieldNames());
    }
}
