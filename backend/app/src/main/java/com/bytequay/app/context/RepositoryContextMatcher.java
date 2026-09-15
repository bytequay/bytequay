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

import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/** Deterministic local relevance matching; source applicability and packet budgets belong to the reader. */
@Component
public class RepositoryContextMatcher
{
    private static final int MAX_CHANGED_PATHS = 10_000;
    private static final int MAX_PATH_BYTES = 1024 * 1024;
    private static final int MAX_TASK_TEXT_BYTES = 256 * 1024;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String WORD_CHARACTERS = "[\\p{L}\\p{M}\\p{N}_]";

    /**
     * Matches enabled notes and scopes using explicit selection, literal paths, and whole terms.
     *
     * @param notebook captured workspace notebook; this method does not read workspace settings
     * @param changedPaths normalized repository-relative file paths, including both sides of renames
     * @param taskText user request and selected PR/commit text; empty text is permitted
     * @param explicitEntryIds requested note identities; disabled or suppressed notes stay excluded
     * @return saved-order matches within each rank, plus relevant scopes whose canonical knowledge is missing
     * @throws IllegalArgumentException for unsafe paths, unknown explicit identities, or input bounds
     *
     * Relevance does not verify a source version or authorize its use as proof. The caller owns
     * the captured On/Off choice, source checks, and the smaller delivery budget. Nothing is truncated here.
     */
    public Selection select(RepositoryNotebook notebook, List<String> changedPaths, String taskText, List<String> explicitEntryIds)
    {
        requireNonNull(notebook, "notebook is null");
        requireNonNull(changedPaths, "changedPaths is null");
        checkArgument(changedPaths.size() <= MAX_CHANGED_PATHS, "Too many changed paths");
        long pathBytes = 0;
        for (String path : changedPaths) {
            requireNonNull(path, "changed path is null");
            ContextLimits.pathPrefix(path + "/");
            pathBytes += ContextLimits.bytes(path);
        }
        checkArgument(pathBytes <= MAX_PATH_BYTES, "Changed paths exceed UTF-8 limit");
        checkArgument(ContextLimits.bytes(taskText) <= MAX_TASK_TEXT_BYTES, "Task text exceeds UTF-8 limit");
        String normalizedText = normalizeText(taskText);
        ContextLimits.unique(requireNonNull(explicitEntryIds, "explicitEntryIds is null"), "explicit entries", ContextLimits.MAX_ENTRIES);
        explicitEntryIds.forEach(id -> ContextLimits.text(id, "explicit entry id", 1200));
        Set<String> explicit = ImmutableSet.copyOf(explicitEntryIds);
        Set<String> known = ImmutableSet.<String>builder().addAll(notebook.entries().stream().map(ContextEntry::id).toList())
                .addAll(notebook.suppressedKnowledgeIds()).build();
        checkArgument(known.containsAll(explicit), "Unknown explicit entry");

        Map<String, ScopeMatch> naturalMatches = new HashMap<>();
        Set<String> enabledScopes = new HashSet<>();
        List<ScopeMatch> scopes = new ArrayList<>();
        for (ContextScope scope : notebook.scopes()) {
            if (!scope.enabled()) {
                continue;
            }
            enabledScopes.add(scope.id());
            boolean missingKnowledge = notebook.entries().stream().noneMatch(entry -> entry.id().equals(ContextEntry.knowledgeId(scope.id())))
                    && !notebook.suppressedKnowledgeIds().contains(ContextEntry.knowledgeId(scope.id()));
            Optional<ScopeMatch> natural = matchScope(scope, changedPaths, normalizedText, missingKnowledge);
            natural.ifPresent(match -> naturalMatches.put(scope.id(), match));
            boolean explicitlySelected = notebook.entries().stream()
                    .anyMatch(entry -> entry.scopeId().equals(scope.id()) && entry.enabled() && explicit.contains(entry.id()));
            if (explicitlySelected) {
                scopes.add(new ScopeMatch(scope, explicitReasons(natural.map(ScopeMatch::matchReasons).orElse(ImmutableList.of())), Rank.EXPLICIT, missingKnowledge));
            }
            else {
                natural.ifPresent(scopes::add);
            }
        }

        List<Match> entries = new ArrayList<>();
        for (ContextEntry entry : notebook.entries()) {
            if (!entry.enabled() || !enabledScopes.contains(entry.scopeId()) || notebook.suppressedKnowledgeIds().contains(entry.id())) {
                continue;
            }
            Optional<ScopeMatch> natural = Optional.ofNullable(naturalMatches.get(entry.scopeId()));
            if (explicit.contains(entry.id())) {
                entries.add(new Match(entry, explicitReasons(natural.map(ScopeMatch::matchReasons).orElse(ImmutableList.of())), Rank.EXPLICIT));
            }
            else {
                natural.ifPresent(match -> entries.add(new Match(entry, match.matchReasons(), match.rank())));
            }
        }
        // Saved positions are unique; stable rank sorting therefore resolves every tie without changing saved order.
        entries.sort(comparing(Match::rank));
        scopes.sort(comparing(ScopeMatch::rank));
        return new Selection(entries, scopes);
    }

    private static Optional<ScopeMatch> matchScope(ContextScope scope, List<String> paths, String text, boolean missingKnowledge)
    {
        if (scope.match() instanceof ContextMatch.Repository) {
            return Optional.of(new ScopeMatch(scope, ImmutableList.of("Repository-wide scope"), Rank.REPOSITORY, missingKnowledge));
        }
        ContextMatch.Selected selected = (ContextMatch.Selected) scope.match();
        List<String> reasons = new ArrayList<>();
        for (String prefix : selected.pathPrefixes()) {
            if (paths.stream().anyMatch(path -> path.startsWith(prefix))) {
                reasons.add("Changed path matches prefix: " + prefix);
            }
        }
        boolean pathMatched = !reasons.isEmpty();
        for (String term : selected.terms()) {
            if (matchesNormalizedTerm(text, term)) {
                reasons.add("Task text contains term: " + term);
            }
        }
        if (reasons.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ScopeMatch(scope, reasons, pathMatched ? Rank.PATH : Rank.TERM, missingKnowledge));
    }

    /** Applies the same bounded literal phrase semantics to local historical candidate text. */
    static boolean matchesTerm(String text, String term)
    {
        checkArgument(ContextLimits.bytes(text) <= MAX_TASK_TEXT_BYTES, "Task text exceeds UTF-8 limit");
        ContextLimits.text(term, "term", 100);
        return matchesNormalizedTerm(normalizeText(text), term);
    }

    private static boolean matchesNormalizedTerm(String text, String term)
    {
        String normalized = normalizeText(term);
        checkArgument(!normalized.isEmpty(), "Scope term has no text after whitespace normalization");
        Pattern phrase = Pattern.compile("(?<!" + WORD_CHARACTERS + ")" + Pattern.quote(normalized) + "(?!" + WORD_CHARACTERS + ")",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return phrase.matcher(text).find();
    }

    private static String normalizeText(String text)
    {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }

    private static List<String> explicitReasons(List<String> natural)
    {
        return ImmutableList.<String>builder().add("Explicitly selected entry").addAll(natural).build();
    }

    public enum Rank
    {
        EXPLICIT,
        REPOSITORY,
        PATH,
        TERM
    }

    public record Selection(List<Match> entries, List<ScopeMatch> scopes)
    {
        public Selection
        {
            entries = ImmutableList.copyOf(entries);
            scopes = ImmutableList.copyOf(scopes);
        }
    }

    public record Match(ContextEntry entry, List<String> matchReasons, Rank rank)
    {
        public Match
        {
            requireNonNull(entry, "entry is null");
            matchReasons = ImmutableList.copyOf(matchReasons);
            requireNonNull(rank, "rank is null");
        }
    }

    public record ScopeMatch(ContextScope scope, List<String> matchReasons, Rank rank, boolean missingKnowledge)
    {
        public ScopeMatch
        {
            requireNonNull(scope, "scope is null");
            matchReasons = ImmutableList.copyOf(matchReasons);
            requireNonNull(rank, "rank is null");
        }
    }
}
