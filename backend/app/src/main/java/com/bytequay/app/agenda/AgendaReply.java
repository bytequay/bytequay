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

import com.bytequay.app.agenda.AgendaSnapshot.RefInfo;
import com.bytequay.core.domain.AgendaItem;
import com.bytequay.core.domain.AgendaRecap;
import com.bytequay.core.domain.AgendaRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The agent's reply, kept only as far as the app can stand behind it: refs a page handed out this run, actions that fit what they
 * name, at most five items, and text cut to its limits. Everything dropped is recorded with its reason.
 */
record AgendaReply(String summary, List<AgendaItem> items, Optional<AgendaRecap> recap, List<AgendaRun.Pick> picked, List<AgendaRun.Drop> dropped)
{
    static final int ITEMS = 5;
    private static final int SUMMARY = 240;
    private static final int TITLE = 80;
    private static final int LABEL = 32;
    private static final int WHY = 200;
    private static final int RECAP = 400;
    private static final int RECAP_REFS = 10;
    private static final Map<AgendaItem.Action, Set<String>> FITS = ImmutableMap.<AgendaItem.Action, Set<String>>builder()
            .put(AgendaItem.Action.START, ImmutableSet.of("work", "issue", "jira"))
            .put(AgendaItem.Action.REVIEW, ImmutableSet.of("pr", "review"))
            .put(AgendaItem.Action.REPLY, ImmutableSet.of("pr", "task", "jira"))
            .put(AgendaItem.Action.FIX, ImmutableSet.of("pr", "task"))
            .put(AgendaItem.Action.MERGE, ImmutableSet.of("pr", "task"))
            .put(AgendaItem.Action.ANSWER, ImmutableSet.of("task", "review"))
            .put(AgendaItem.Action.DECIDE, ImmutableSet.of("epic"))
            .put(AgendaItem.Action.FOLLOW_UP, ImmutableSet.of("pr", "issue", "jira", "task", "review", "work", "epic"))
            .buildOrThrow();

    AgendaReply
    {
        requireNonNull(summary, "summary is null");
        items = ImmutableList.copyOf(items);
        requireNonNull(recap, "recap is null");
        picked = ImmutableList.copyOf(picked);
        dropped = ImmutableList.copyOf(dropped);
    }

    /**
     * @param served the refs this run's pages handed out, with the kind that handed each out first
     * @param mine the user's own pull requests, which they do not review
     * @throws IllegalArgumentException when the reply holds no JSON object
     */
    static AgendaReply parse(String reply, AgendaSnapshot snapshot, Map<String, String> served, Set<String> mine, ObjectMapper mapper)
    {
        JsonNode root = object(reply, mapper);
        ImmutableList.Builder<AgendaItem> items = ImmutableList.builder();
        ImmutableList.Builder<AgendaRun.Pick> picked = ImmutableList.builder();
        ImmutableList.Builder<AgendaRun.Drop> dropped = ImmutableList.builder();
        Set<String> seen = new HashSet<>();
        int kept = 0;
        for (JsonNode node : root.path("items")) {
            String ref = node.path("ref").asText("");
            Optional<String> refused = refusal(node, ref, served, mine, seen, kept);
            if (refused.isPresent()) {
                dropped.add(new AgendaRun.Drop(ref, refused.orElseThrow()));
                continue;
            }
            seen.add(ref);
            kept++;
            AgendaItem.Action action = action(node).orElseThrow();
            RefInfo info = snapshot.refs().get(ref);
            String title = clip(node.path("title").asText(), TITLE);
            String label = clip(text(node, "label").orElse(title), LABEL);
            items.add(new AgendaItem(ref, action, title, label, clip(node.path("why").asText(""), WHY), info.at(), info.workspaceId(), info.target()));
            picked.add(new AgendaRun.Pick(ref, action.name().toLowerCase(Locale.ROOT), served.get(ref)));
        }
        Optional<AgendaRecap> recap = snapshot.overview().recapDay() && root.path("recap").isObject()
                ? Optional.of(new AgendaRecap(clip(root.path("recap").path("text").asText(""), RECAP),
                links(root.path("recap").path("merged"), snapshot, served), links(root.path("recap").path("slipped"), snapshot, served)))
                : Optional.empty();
        return new AgendaReply(clip(root.path("summary").asText(""), SUMMARY), items.build(), recap, picked.build(), dropped.build());
    }

    private static Optional<String> refusal(JsonNode node, String ref, Map<String, String> served, Set<String> mine, Set<String> seen, int kept)
    {
        if (!served.containsKey(ref)) {
            return Optional.of("unknown ref");
        }
        if (seen.contains(ref)) {
            return Optional.of("duplicate");
        }
        Optional<AgendaItem.Action> action = action(node);
        if (action.isEmpty()) {
            return Optional.of("unknown action");
        }
        if (!FITS.get(action.orElseThrow()).contains(AgendaRefs.kind(ref)) || action.orElseThrow() == AgendaItem.Action.REVIEW && mine.contains(ref)) {
            return Optional.of("action does not fit");
        }
        if (text(node, "title").isEmpty()) {
            return Optional.of("no title");
        }
        if (kept >= ITEMS) {
            return Optional.of("over five");
        }
        return Optional.empty();
    }

    private static Optional<AgendaItem.Action> action(JsonNode node)
    {
        String action = node.path("action").asText("").toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return action.isEmpty() ? Optional.empty() : Optional.of(AgendaItem.Action.valueOf(action));
        }
        catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static List<AgendaRecap.Link> links(JsonNode refs, AgendaSnapshot snapshot, Map<String, String> served)
    {
        ImmutableList.Builder<String> kept = ImmutableList.builder();
        refs.forEach(ref -> kept.add(ref.asText("")));
        return kept.build().stream().filter(served::containsKey).distinct().limit(RECAP_REFS)
                .map(ref -> new AgendaRecap.Link(ref, snapshot.refs().get(ref).target()))
                .collect(toImmutableList());
    }

    private static Optional<String> text(JsonNode node, String field)
    {
        return Optional.of(node.path(field).asText("")).filter(value -> !value.isBlank());
    }

    /** The reply's JSON object, also when the model wrapped it in a code fence or a sentence. */
    private static JsonNode object(String reply, ObjectMapper mapper)
    {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("The agent's reply held no agenda");
        }
        try {
            JsonNode root = mapper.readTree(reply.substring(start, end + 1));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("The agent's reply held no agenda");
            }
            return root;
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("The agent's reply was not valid JSON", invalid);
        }
    }
}
