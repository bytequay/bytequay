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
package com.bytequay.app.service.recent;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RecentTouch;
import com.bytequay.core.domain.RecentWordMatch;
import com.bytequay.core.domain.RecentWordMatch.MatchRange;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.RecentStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Service
public class RecentServiceImpl
        implements RecentService
{
    /** Timeline events that count as somebody replying to the user. */
    private static final Set<String> REPLY_EVENTS = ImmutableSet.of("commented", "reviewed", "review_commented");
    private static final Set<String> FAILED_CONCLUSIONS =
            ImmutableSet.of("failure", "timed_out", "action_required", "startup_failure");
    private static final int MAX_SENTENCE = 240;
    /**
     * How many bodies the most selective token may pull back before the
     * remaining tokens are applied here. Retention bounds the whole corpus to a
     * fortnight of one person's writing, so this is a ceiling that is never
     * reached in practice rather than a page size.
     */
    private static final int TEXT_CANDIDATES = 200;

    private final RecentStore store;
    private final RecentTouchRecorder recorder;
    private final PrDetailStore details;
    private final PrViewStateStore viewStates;
    private final WorkspaceStore workspaces;
    private final CredentialService credentials;
    private final Clock clock;

    public RecentServiceImpl(
            RecentStore store,
            RecentTouchRecorder recorder,
            PrDetailStore details,
            PrViewStateStore viewStates,
            WorkspaceStore workspaces,
            CredentialService credentials,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.recorder = requireNonNull(recorder, "recorder is null");
        this.details = requireNonNull(details, "details is null");
        this.viewStates = requireNonNull(viewStates, "viewStates is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public RecentDayPage stream(RecentFilter filter, LocalDate cursor)
    {
        requireNonNull(filter, "filter is null");
        List<RecentTouch> matching = matching(filter);
        List<LocalDate> activeDays = matching.stream()
                .map(RecentTouch::day)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(toImmutableList());
        Optional<LocalDate> day = activeDays.stream()
                .filter(candidate -> cursor == null || !candidate.isAfter(cursor))
                .findFirst();
        if (day.isEmpty()) {
            return new RecentDayPage(null, ImmutableList.of(), null, true, activeDays, RETENTION_DAYS);
        }
        LocalDate selected = day.orElseThrow();
        LocalDate next = activeDays.stream().filter(candidate -> candidate.isBefore(selected)).findFirst().orElse(null);
        List<RecentRow> rows = matching.stream()
                .filter(touch -> touch.day().equals(selected))
                .map(this::toRow)
                .collect(toImmutableList());
        return new RecentDayPage(selected, rows, next, next == null, activeDays, RETENTION_DAYS);
    }

    @Override
    public List<RecentRow> search(String query, int limit)
    {
        List<RecentTouch> window = store.findTouches(windowStart(), today());
        List<String> tokens = tokens(query);
        Instant now = clock.instant();
        List<RecentTouch> ranked = tokens.isEmpty()
                ? window
                : window.stream()
                        .map(touch -> new Scored(touch, score(touch, tokens, now)))
                        .filter(scored -> scored.score() > 0)
                        .sorted(Comparator.comparingDouble(Scored::score).reversed()
                                .thenComparing(scored -> scored.touch().touchedAt(), Comparator.reverseOrder()))
                        .map(Scored::touch)
                        .collect(toImmutableList());
        Set<String> seen = new HashSet<>();
        return ranked.stream()
                .filter(touch -> seen.add(touch.itemKey()))
                .limit(Math.max(limit, 0))
                .map(this::toRow)
                .collect(toImmutableList());
    }

    /**
     * The database narrows on the single most selective token — the longest one
     * — and the rest of the query is applied here, so a multi-word search needs
     * no dynamic SQL. Hits come back newest first, which is what the mode is
     * for: the sentence you wrote most recently, not the best-scoring one.
     */
    @Override
    public List<RecentWordHit> words(String query, int limit)
    {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty() || limit <= 0) {
            return ImmutableList.of();
        }
        String selective = tokens.stream().max(Comparator.comparingInt(String::length)).orElseThrow();
        return store.findTextContaining(selective, TEXT_CANDIDATES).stream()
                .filter(text -> containsAll(text.body(), tokens))
                .limit(limit)
                .map(text -> toHit(text, tokens))
                .collect(toImmutableList());
    }

    private static boolean containsAll(String body, List<String> tokens)
    {
        String lowered = body.toLowerCase(Locale.ROOT);
        return tokens.stream().allMatch(lowered::contains);
    }

    @Override
    public List<RecentChange> changes()
    {
        List<RecentTouch> window = store.findTouches(windowStart(), today());
        Map<Long, RecentTouch> newestByPr = new HashMap<>();
        for (RecentTouch touch : window) {
            if (touch.type() == RecentItemType.PULL_REQUEST && touch.prId() != null) {
                newestByPr.merge(touch.prId(), touch,
                        (existing, fresh) -> fresh.touchedAt().isAfter(existing.touchedAt()) ? fresh : existing);
            }
        }
        if (newestByPr.isEmpty()) {
            return ImmutableList.of();
        }
        Map<Long, PrViewState> states = viewStates.findByPrIds(ImmutableSet.copyOf(newestByPr.keySet()));
        Optional<String> viewer = credentials.cachedLogin();
        return newestByPr.entrySet().stream()
                .map(entry -> change(entry.getValue(), states.get(entry.getKey()), viewer))
                .filter(change -> change.moved() || change.wantsYou())
                .collect(toImmutableList());
    }

    @Override
    public void recordViewerTouch(ViewerTouch touch)
    {
        recorder.viewerTouch(touch);
    }

    @Override
    public void recordDraft(RecentDraft draft)
    {
        requireNonNull(draft, "draft is null");
        recorder.draft(draft.type(), draft.repo(), draft.number(), draft.workspaceId(), draft.body());
    }

    @Override
    public void clear()
    {
        store.deleteAll();
    }

    @Override
    public boolean clear(String touchId)
    {
        return store.deleteTouch(touchId);
    }

    @Override
    public int sweep()
    {
        return store.deleteBefore(windowStart());
    }

    @Override
    public LocalDate windowStart()
    {
        return today().minusDays(RETENTION_DAYS - 1L);
    }

    // ── Stream filtering ──────────────────────────────────────────────────────

    private List<RecentTouch> matching(RecentFilter filter)
    {
        // Clamp both ends to what is actually kept. Days are stored as ISO text,
        // so an open-ended bound would compare lexicographically against a
        // sentinel date and silently match nothing.
        LocalDate from = filter.from().isBefore(windowStart()) ? windowStart() : filter.from();
        LocalDate to = filter.to().isAfter(today()) ? today() : filter.to();
        List<RecentTouch> window = store.findTouches(from, to);
        Set<String> moved = filter.changedSinceRead()
                ? changes().stream().filter(RecentChange::moved).map(RecentChange::itemKey).collect(toImmutableSet())
                : ImmutableSet.of();
        Optional<String> narrow = filter.narrow().map(value -> value.toLowerCase(Locale.ROOT));
        Optional<String> scope = filter.workspace().map(this::watchedRepository);
        return window.stream()
                .filter(touch -> filter.types().isEmpty() || filter.types().contains(touch.type()))
                .filter(touch -> filter.reason().map(reason -> reason == touch.reason()).orElse(true))
                .filter(touch -> inWorkspace(touch, filter.workspace(), scope))
                .filter(touch -> !filter.changedSinceRead() || moved.contains(touch.itemKey()))
                .filter(touch -> narrow.map(value -> narrowed(touch, value)).orElse(true))
                .collect(toImmutableList());
    }

    /**
     * A workspace scopes the stream to the repository it watches, not to the
     * touches that happened to be recorded from inside it. The same pull
     * request read from the global list carries no workspace of its own, and
     * scoping on that stamp would hide almost everything.
     */
    private String watchedRepository(String workspaceId)
    {
        return workspaces.findById(workspaceId)
                .map(Workspace::monitoredRepository)
                .map(repository -> repository.fullName())
                .orElse(null);
    }

    private static boolean inWorkspace(RecentTouch touch, Optional<String> workspaceId, Optional<String> repository)
    {
        if (workspaceId.isEmpty()) {
            return true;
        }
        if (workspaceId.get().equals(touch.workspaceId())) {
            return true;
        }
        return repository.isPresent()
                && touch.repo() != null
                && touch.repo().equalsIgnoreCase(repository.orElseThrow());
    }

    private static boolean narrowed(RecentTouch touch, String lowered)
    {
        return contains(touch.title(), lowered)
                || contains(touch.repo(), lowered)
                || contains(touch.subtitle(), lowered)
                || touch.number() != null && String.valueOf(touch.number()).contains(lowered);
    }

    private static boolean contains(String value, String lowered)
    {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowered);
    }

    // ── Rows ──────────────────────────────────────────────────────────────────

    private RecentRow toRow(RecentTouch touch)
    {
        Optional<RecentText> text = store.findTextByItemKey(touch.itemKey()).stream().findFirst();
        return new RecentRow(
                touch.id(),
                touch.type(),
                touch.itemKey(),
                touch.number(),
                touch.title(),
                touch.repo(),
                touch.workspaceId(),
                touch.mine(),
                touch.touchedAt(),
                touch.reason(),
                reasonLabel(touch),
                touch.htmlUrl(),
                touch.subtitle(),
                text.map(RecentText::body).orElse(null),
                text.map(RecentText::id).orElse(null));
    }

    static String reasonLabel(RecentTouch touch)
    {
        return switch (touch.reason()) {
            case READ -> touch.reasonCount() > 0 ? "Read " + touch.reasonCount() + "m" : "Read";
            case EDITED -> "Edited";
            case PROMPTED -> "You prompted";
            case PUSHED -> touch.reasonCount() > 0 ? "Pushed " + touch.reasonCount() : "Pushed";
            case COMMENTED -> "Commented";
            case CHANGES_REQUESTED -> "Changes requested";
            case APPROVED -> "Approved";
            case MERGED -> "Merged";
        };
    }

    // ── Items-mode ranking ────────────────────────────────────────────────────

    /**
     * Substring scoring per token over the fields worth remembering, lifted by
     * how recent the touch is. Fuzzy enough for a fourteen-day window; nothing
     * here needs an index.
     */
    private double score(RecentTouch touch, List<String> tokens, Instant now)
    {
        double score = 0;
        String title = lower(touch.title());
        String repo = lower(touch.repo());
        String body = lower(store.findTextByItemKey(touch.itemKey()).stream()
                .map(RecentText::body)
                .reduce("", (left, right) -> left + "\n" + right));
        String number = touch.number() == null ? "" : String.valueOf(touch.number());
        for (String token : tokens) {
            double tokenScore = 0;
            if (title.contains(token)) {
                tokenScore += title.startsWith(token) ? 4 : 3;
            }
            if (number.startsWith(token)) {
                tokenScore += 5;
            }
            if (repo.contains(token)) {
                tokenScore += 1;
            }
            if (body.contains(token)) {
                tokenScore += 2;
            }
            if (tokenScore == 0) {
                return 0;
            }
            score += tokenScore;
        }
        long ageDays = Math.max(Duration.between(touch.touchedAt(), now).toDays(), 0);
        return score + Math.max(RETENTION_DAYS - ageDays, 0) / (double) RETENTION_DAYS * 2;
    }

    private static String lower(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static List<String> tokens(String query)
    {
        if (query == null || query.isBlank()) {
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_#]+")).stream()
                .map(token -> token.startsWith("#") ? token.substring(1) : token)
                .filter(token -> !token.isEmpty())
                .collect(toImmutableList());
    }

    // ── Words-mode hits ───────────────────────────────────────────────────────

    private static RecentWordHit toHit(RecentText text, List<String> tokens)
    {
        RecentWordMatch match = matchIn(text, tokens);
        return new RecentWordHit(
                text.id(),
                text.type(),
                text.itemKey(),
                text.kind(),
                kindLabel(text.kind()),
                text.kind() == RecentTextKind.DRAFT,
                match.sentence(),
                match.highlights(),
                text.body(),
                text.number(),
                text.repo(),
                text.title(),
                text.htmlUrl(),
                text.writtenAt());
    }

    /** Picks the sentence the term actually appears in, and the offsets inside it. */
    static RecentWordMatch matchIn(RecentText text, List<String> tokens)
    {
        String body = text.body();
        for (String sentence : sentences(body)) {
            List<MatchRange> highlights = highlights(sentence, tokens);
            if (!highlights.isEmpty()) {
                return new RecentWordMatch(text, sentence, highlights);
            }
        }
        String opening = body.length() > MAX_SENTENCE ? body.substring(0, MAX_SENTENCE).strip() + "…" : body.strip();
        return new RecentWordMatch(text, opening, ImmutableList.of());
    }

    private static List<String> sentences(String body)
    {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            boolean terminator = character == '.' || character == '!' || character == '?' || character == '\n';
            if (terminator || index - start >= MAX_SENTENCE) {
                String sentence = body.substring(start, index + 1).strip();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                start = index + 1;
            }
        }
        String tail = body.substring(start).strip();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return ImmutableList.copyOf(sentences);
    }

    private static List<MatchRange> highlights(String sentence, List<String> tokens)
    {
        String lowered = sentence.toLowerCase(Locale.ROOT);
        List<MatchRange> ranges = new ArrayList<>();
        for (String token : tokens) {
            int from = lowered.indexOf(token);
            while (from >= 0) {
                ranges.add(new MatchRange(from, from + token.length()));
                from = lowered.indexOf(token, from + token.length());
            }
        }
        ranges.sort(Comparator.comparingInt(MatchRange::start));
        return ImmutableList.copyOf(ranges);
    }

    private static String kindLabel(RecentTextKind kind)
    {
        return switch (kind) {
            case COMMENT -> "comment";
            case REVIEW_BODY -> "review body";
            case TASK_PROMPT -> "task prompt";
            case DRAFT -> "draft";
        };
    }

    // ── Changes ───────────────────────────────────────────────────────────────

    private RecentChange change(RecentTouch touch, PrViewState state, Optional<String> viewer)
    {
        Optional<StoredPrDetail> stored = details.find(touch.prId());
        if (stored.isEmpty()) {
            return new RecentChange(touch.itemKey(), false, false, null);
        }
        StoredPrDetail detail = stored.orElseThrow();
        Instant since = state == null ? null : state.viewedAt();
        boolean wantsYou = viewer
                .map(login -> detail.raw().requestedReviewers().stream().anyMatch(login::equalsIgnoreCase))
                .orElse(false);
        if (since == null) {
            return new RecentChange(touch.itemKey(), false, wantsYou, null);
        }
        long replies = detail.timeline().stream()
                .filter(event -> REPLY_EVENTS.contains(event.event()))
                .filter(event -> after(event, since))
                .filter(event -> viewer.map(login -> !login.equalsIgnoreCase(event.actor())).orElse(true))
                .count();
        long commits = detail.timeline().stream()
                .filter(event -> "committed".equals(event.event()))
                .filter(event -> after(event, since))
                .count();
        boolean ciRed = detail.checkRuns().stream().anyMatch(RecentServiceImpl::failed)
                && detail.checkRuns().stream()
                        .anyMatch(run -> run.completedAt() != null && run.completedAt().isAfter(since));
        return new RecentChange(
                touch.itemKey(),
                replies > 0 || commits > 0 || ciRed,
                wantsYou,
                summary(replies, commits, ciRed));
    }

    private static boolean failed(PrCheckRunState run)
    {
        return run.conclusion() != null && FAILED_CONCLUSIONS.contains(run.conclusion().toLowerCase(Locale.ROOT));
    }

    private static boolean after(PrTimelineEvent event, Instant since)
    {
        return event.timestamp() != null && event.timestamp().isAfter(since);
    }

    private static String summary(long replies, long commits, boolean ciRed)
    {
        List<String> parts = new ArrayList<>();
        if (replies > 0) {
            parts.add(replies + (replies == 1 ? " new reply" : " new replies"));
        }
        if (commits > 0) {
            parts.add(commits + (commits == 1 ? " new commit" : " new commits"));
        }
        if (ciRed) {
            parts.add("CI went red");
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" and ", parts) + " since you read it";
    }

    private LocalDate today()
    {
        return LocalDate.now(clock);
    }

    private record Scored(RecentTouch touch, double score) {}
}
