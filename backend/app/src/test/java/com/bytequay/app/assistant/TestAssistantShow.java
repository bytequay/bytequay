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
package com.bytequay.app.assistant;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.agenda.AgendaFixtures;
import com.bytequay.app.agenda.ItemTaskStarter;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantDraft;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantList;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.ServedRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.bytequay.core.domain.AssistantReceipt.Operation.OPEN;
import static com.bytequay.core.domain.AssistantReceipt.Outcome.DONE;
import static com.bytequay.core.domain.AssistantReceipt.Outcome.REFUSED;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAssistantShow
{
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-10-17");
    private static final String PULL = "pr:owner/repository#31402";
    private static final Map<String, ServedRef> SERVED = ImmutableMap.of(
            "work:w22", new ServedRef("epics", Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w22"))),
            "ws:ws1", new ServedRef("start_options", Optional.empty()));

    private final AppNoticeService notices = mock(AppNoticeService.class);
    private final AssistantRows rows = mock(AssistantRows.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final ObjectMapper mapper = AgendaFixtures.mapper();
    private final List<AssistantItem> shown = new ArrayList<>();
    private AssistantShow show;

    @BeforeEach
    void setUp()
    {
        // The card on screen belongs to task t1, which a message showing it may name.
        when(notices.current()).thenReturn(ImmutableList.of(new AppNotice("approval:a1", AppNotice.Kind.APPROVAL, "t1", "ws1", Optional.of("turn-1"), Optional.empty(),
                "Retry Iceberg commit on conflict", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "Permission needed", "owner/repository", "claude",
                Optional.empty(), NOW)));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AssistantActions actions = new AssistantActions(new MemoryAssistantStore(), mock(AssistantSettings.class), mock(AgentApprovalStore.class),
                mock(AgentApprovalService.class), mock(AgentTaskStore.class), mock(AgentTurnService.class), mock(ItemTaskStarter.class), notices,
                mock(AgentReviewStore.class), mock(AssistantWork.class), mapper, clock);
        show = new AssistantShow(actions, rows, workspaces, mapper, clock);
        when(workspaces.findById("ws1")).thenReturn(Optional.of(new Workspace("ws1", "Repository", 1, RepoRef.of("owner", "repository"), Optional.empty(), Optional.empty(), 1,
                RepoRef.of("owner", "repository"), Workspace.Topology.DIRECT, "master", "https://github.com/owner/repository.git", Optional.empty(), Optional.empty(),
                "/managed/repository", Optional.empty(), Optional.empty(), 0, NOW, NOW)));
        AgendaTarget pull = AgendaTarget.pull("owner/repository", 31402, Optional.of("ws1"));
        when(rows.rows(ImmutableList.of(PULL))).thenReturn(ImmutableMap.of(PULL, new AssistantRow(PULL, AssistantRow.Kind.PULL, "Retry Iceberg commit on conflict",
                Optional.of("#31402"), Optional.of("open"), Optional.of("failing"), Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), pull)));
    }

    @Test
    void listsWhatTheUserCouldSeeAndNamesWhatItDrops()
            throws Exception
    {
        JsonNode answer = show(message(), "{\"op\":\"list\",\"style\":\"cards\",\"title\":\"Waiting on you\",\"refs\":[\"" + PULL + "\",\"work:w22\",\"task:t1\","
                + "\"pr:owner/repository#1\",\"ws:ws1\",\"approval:a1\",\"work:w22\"]}");

        // What was on screen, what the day handed out and the tasks of the cards shown; never a card, a repository or an unread ref.
        assertThat(answer.path("outcome").asText()).isEqualTo("done");
        assertThat(answer.path("dropped").toString()).isEqualTo("[\"pr:owner/repository#1\",\"ws:ws1\",\"approval:a1\"]");
        AssistantItem list = shown.getLast();
        assertThat(list.kind()).isEqualTo(AssistantItem.Kind.LIST);
        assertThat(list.text()).isEqualTo("Waiting on you");
        assertThat(list.day()).isEqualTo(DAY);
        assertThat(list.list()).contains(new AssistantList(AssistantList.Style.CARDS, ImmutableList.of(PULL, "work:w22", "task:t1")));

        assertThat(show(message(), "{\"op\":\"list\",\"title\":\"" + "Your day ".repeat(20) + "\",\"refs\":[\"work:w22\"]}").has("dropped")).isFalse();
        assertThat(shown.getLast().list().orElseThrow().style()).isEqualTo(AssistantList.Style.ROWS);
        assertThat(shown.getLast().text()).hasSizeLessThanOrEqualTo(80).endsWith("…");
    }

    @Test
    void refusesAListOfNothingTheUserCouldSee()
            throws Exception
    {
        JsonNode answer = show(message(), "{\"op\":\"list\",\"title\":\"Others\",\"refs\":[\"pr:owner/repository#1\",\"task:t9\"]}");

        assertThat(answer.path("outcome").asText()).isEqualTo("refused");
        assertThat(answer.path("dropped").toString()).isEqualTo("[\"pr:owner/repository#1\",\"task:t9\"]");
        assertThat(shown).isEmpty();
    }

    @Test
    void holdsAtMostFiftyRefs()
            throws Exception
    {
        Map<String, ServedRef> served = IntStream.range(0, 51).boxed()
                .collect(toImmutableMap(index -> "work:w" + index, index -> new ServedRef("search", Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w" + index)))));
        List<String> refs = ImmutableList.copyOf(served.keySet());

        assertThat(error(show(message(), served, list(refs)))).isEqualTo("a list holds at most 50 refs");
        assertThat(shown).isEmpty();
        assertThat(show(message(), served, list(refs.subList(0, 50))).path("outcome").asText()).isEqualTo("done");
        assertThat(shown.getLast().list().orElseThrow().refs()).hasSize(50);
    }

    @Test
    void rejectsAMalformedCall()
            throws Exception
    {
        assertThat(error(show(message(), "{\"op\":\"merge\"}"))).isEqualTo("op must be one of list, open, draft_epic");
        assertThat(error(show(message(), "{\"op\":\"list\",\"title\":\"Mine\",\"refs\":[\"work:w22\"],\"words\":\"show them\"}"))).isEqualTo("list takes no words");
        assertThat(error(show(message(), "{\"op\":\"list\",\"refs\":[\"work:w22\"]}"))).isEqualTo("list needs a title");
        assertThat(error(show(message(), "{\"op\":\"list\",\"title\":\"Mine\",\"refs\":\"work:w22\"}"))).isEqualTo("list needs refs");
        assertThat(error(show(message(), "{\"op\":\"list\",\"title\":\"Mine\",\"style\":\"grid\",\"refs\":[\"work:w22\"]}")))
                .isEqualTo("style must be one of rows, cards, timeline");
        assertThat(error(show(message(), "not json"))).isEqualTo("arguments are not JSON");
        assertThat(shown).isEmpty();
    }

    @Test
    void opensAnItemTheUserCouldSeeOnTheirOwnWords()
            throws Exception
    {
        AssistantItem message = message("open it, please");
        JsonNode answer = show(message, "{\"op\":\"open\",\"ref\":\"" + PULL + "\",\"words\":\"open  IT\"}");

        assertThat(answer.path("outcome").asText()).isEqualTo("done");
        AssistantItem receipt = shown.getLast();
        assertThat(receipt.kind()).isEqualTo(AssistantItem.Kind.RECEIPT);
        // Its name and where it opens come from the stores, and the words it acted on go on the receipt.
        assertThat(receipt.text()).isEqualTo("Opened “Retry Iceberg commit on conflict”");
        assertThat(receipt.receipt()).contains(AssistantReceipt.done(OPEN, Optional.of(AgendaTarget.pull("owner/repository", 31402, Optional.of("ws1")))).withWords("open  IT"));
    }

    @Test
    void opensAPageOrASettingsSectionOnTheirOwnWords()
            throws Exception
    {
        AssistantItem message = message("take me to model settings");
        JsonNode answer = show(message, "{\"op\":\"open\",\"page\":\"settings/models\",\"words\":\"take me to model settings\"}");

        assertThat(answer.path("message").asText()).isEqualTo("Opened Settings · Models");
        AssistantReceipt receipt = shown.getLast().receipt().orElseThrow();
        assertThat(receipt.operation()).isEqualTo(OPEN);
        assertThat(receipt.outcome()).isEqualTo(DONE);
        assertThat(receipt.page()).contains("settings/models");
        assertThat(receipt.target()).isEmpty();
        assertThat(receipt.words()).contains("take me to model settings");
        show(message("show me my pull requests"), "{\"op\":\"open\",\"page\":\"pulls\",\"words\":\"show me my pull requests\"}");
        assertThat(shown.getLast().text()).isEqualTo("Opened Pull requests");
        verify(rows, never()).rows(ImmutableList.of(PULL));
    }

    @Test
    void refusesWordsThatAreNotTheUsersOrThatTheMessageTurnsDown()
            throws Exception
    {
        assertRefused(show(message("what is waiting on me?"), "{\"op\":\"open\",\"page\":\"settings/models\",\"words\":\"open settings\"}"),
                "Those words aren't in your message", "open settings");
        assertRefused(show(message("a"), "{\"op\":\"open\",\"page\":\"home\",\"words\":\"a\"}"), "Those words aren't in your message", "a");
        assertRefused(show(message("don't open the model settings, just tell me"), "{\"op\":\"open\",\"page\":\"settings/models\",\"words\":\"open the model settings\"}"),
                "Your message says not to do that", "open the model settings");
        assertThat(shown).allSatisfy(receipt -> assertThat(receipt.receipt().orElseThrow().target()).isEmpty());
    }

    @Test
    void opensOnlyAnItemOnScreenOrHandedOutThatStillExists()
            throws Exception
    {
        assertRefused(show(message("open #1"), "{\"op\":\"open\",\"ref\":\"pr:owner/repository#1\",\"words\":\"open #1\"}"), "That isn't on screen", "open #1");
        // Handed out earlier today, but no longer in any store.
        assertRefused(show(message("open the work item"), "{\"op\":\"open\",\"ref\":\"work:w22\",\"words\":\"open the work item\"}"),
                "That isn't in ByteQuay any more", "open the work item");

        assertThat(error(show(message(), "{\"op\":\"open\",\"ref\":\"" + PULL + "\",\"page\":\"home\",\"words\":\"waiting\"}")))
                .isEqualTo("open takes a ref or a page");
        assertThat(error(show(message(), "{\"op\":\"open\",\"words\":\"waiting\"}"))).isEqualTo("open takes a ref or a page");
        assertThat(error(show(message(), "{\"op\":\"open\",\"page\":\"home\"}"))).isEqualTo("open needs the user's words");
        assertThat(error(show(message(), "{\"op\":\"open\",\"page\":\"terminal\",\"words\":\"waiting\"}"))).startsWith("page must be one of home, workspaces");
        assertThat(error(show(message(), "{\"op\":\"open\",\"ref\":\"ws:ws1\",\"words\":\"waiting\"}"))).isEqualTo("open takes an item's ref or a page");
        assertThat(error(show(message(), "{\"op\":\"open\",\"page\":\"home\",\"words\":\"waiting\",\"title\":\"Home\"}"))).isEqualTo("open takes no title");
        assertThat(shown).hasSize(2);
    }

    @Test
    void draftsANewGitHubIssueForTheUserToEditAndCreate()
            throws Exception
    {
        JsonNode answer = show(message(), "{\"op\":\"draft_epic\",\"workspace\":\"ws:ws1\",\"title\":\"Iceberg snapshot metadata\",\"body\":\"Track the snapshot work.\"}");

        assertThat(answer.path("outcome").asText()).isEqualTo("done");
        AssistantItem draft = shown.getLast();
        assertThat(draft.kind()).isEqualTo(AssistantItem.Kind.DRAFT);
        assertThat(draft.text()).isEqualTo("Iceberg snapshot metadata");
        AssistantDraft drafted = draft.draft().orElseThrow();
        assertThat(drafted.workspaceId()).isEqualTo("ws1");
        assertThat(drafted.repository()).isEqualTo("owner/repository");
        assertThat(drafted.body()).isEqualTo("Track the snapshot work.");
        // The work item the user's click creates is named now, so a retried click creates it once.
        assertThat(UUID.fromString(drafted.workItemId())).isNotNull();

        // Only a repository the day handed out, and the assistant writes nothing to GitHub itself.
        assertThat(show(message(), "{\"op\":\"draft_epic\",\"workspace\":\"ws:ws2\",\"title\":\"Elsewhere\"}").path("outcome").asText()).isEqualTo("refused");
        assertThat(error(show(message(), "{\"op\":\"draft_epic\",\"workspace\":\"ws:ws1\"}"))).isEqualTo("draft_epic needs a title");
        assertThat(error(show(message(), "{\"op\":\"draft_epic\",\"workspace\":\"repository\",\"title\":\"Mine\"}"))).isEqualTo("draft_epic needs a ws: ref");
        assertThat(error(show(message(), "{\"op\":\"draft_epic\",\"workspace\":\"ws:ws1\",\"title\":\"Mine\",\"words\":\"draft it\"}")))
                .isEqualTo("draft_epic takes no words");
        assertThat(shown).hasSize(1);
    }

    private void assertRefused(JsonNode answer, String reason, String words)
    {
        assertThat(answer.path("outcome").asText()).isEqualTo("refused");
        assertThat(answer.path("message").asText()).isEqualTo(reason);
        AssistantItem receipt = shown.getLast();
        assertThat(receipt.text()).isEqualTo(reason);
        assertThat(receipt.receipt().orElseThrow().operation()).isEqualTo(OPEN);
        assertThat(receipt.receipt().orElseThrow().outcome()).isEqualTo(REFUSED);
        assertThat(receipt.receipt().orElseThrow().words()).contains(words);
    }

    private static AssistantItem message(String text)
    {
        return AssistantItem.user(DAY, NOW, text, ImmutableList.of("approval:a1"), Optional.of(PULL), "key-" + text);
    }

    private static AssistantItem message()
    {
        return AssistantItem.user(DAY, NOW, "what is waiting on me?", ImmutableList.of("approval:a1"), Optional.of(PULL), "key-1");
    }

    private static String list(List<String> refs)
    {
        return "{\"op\":\"list\",\"title\":\"Snapshot work\",\"refs\":[" + refs.stream().map(ref -> "\"" + ref + "\"").collect(joining(",")) + "]}";
    }

    private JsonNode show(AssistantItem message, String arguments)
            throws Exception
    {
        return show(message, SERVED, arguments);
    }

    /** One call through a tool made for the message, as a run makes one. */
    private JsonNode show(AssistantItem message, Map<String, ServedRef> served, String arguments)
            throws Exception
    {
        AssistantContext context = new AssistantContext(mock(AssistantReader.class), DAY, message.visible(), message.onScreen(), served, mapper);
        return mapper.readTree(show.tool(message, context, shown::add).call().apply(arguments));
    }

    private static String error(JsonNode answer)
    {
        assertThat(answer.path("outcome").asText()).isEqualTo("error");
        return answer.path("message").asText();
    }
}
