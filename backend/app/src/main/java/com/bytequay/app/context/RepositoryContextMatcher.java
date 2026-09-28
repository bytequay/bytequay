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

import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Deterministic scope routing from configured path prefixes and whole terms. Note relevance belongs to lookup. */
@Component
public class RepositoryContextMatcher
{
    private static final int MAX_CHANGED_PATHS = 10_000;
    private static final int MAX_PATH_BYTES = 1024 * 1024;
    private static final int MAX_TASK_TEXT_BYTES = 256 * 1024;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String WORD_CHARACTERS = "[\\p{L}\\p{M}\\p{N}_]";

    /** Matches enabled scopes without selecting, ranking, or injecting notebook entries. */
    public Selection select(RepositoryNotebook notebook, List<String> changedPaths, String taskText)
    {
        requireNonNull(notebook, "notebook is null");
        requireNonNull(changedPaths, "changedPaths is null");
        checkArgument(changedPaths.size() <= MAX_CHANGED_PATHS, "Too many changed paths");
        long pathBytes = 0;
        for (String path : changedPaths) {
            requireNonNull(path, "changed path is null");
            ContextLimits.pathPrefix(path.endsWith("/") ? path : path + "/");
            pathBytes += ContextLimits.bytes(path);
        }
        checkArgument(pathBytes <= MAX_PATH_BYTES, "Changed paths exceed UTF-8 limit");
        checkArgument(ContextLimits.bytes(taskText) <= MAX_TASK_TEXT_BYTES, "Task text exceeds UTF-8 limit");
        String normalizedText = normalizeText(taskText);
        ImmutableList.Builder<ScopeMatch> matches = ImmutableList.builder();
        for (ContextScope scope : notebook.scopes()) {
            if (!scope.enabled()) {
                continue;
            }
            boolean missingKnowledge = notebook.entries().stream().noneMatch(entry -> entry.kind() == KNOWLEDGE && entry.enabled()
                    && entry.scopeId().equals(scope.id()));
            matchScope(scope, changedPaths, normalizedText, missingKnowledge).ifPresent(matches::add);
        }
        return new Selection(matches.build());
    }

    private static Optional<ScopeMatch> matchScope(ContextScope scope, List<String> paths, String text, boolean missingKnowledge)
    {
        if (scope.match() instanceof ContextMatch.Repository) {
            return Optional.of(new ScopeMatch(scope, ImmutableList.of("Repository-wide scope"), missingKnowledge));
        }
        ContextMatch.Selected selected = (ContextMatch.Selected) scope.match();
        List<String> reasons = new ArrayList<>();
        for (String prefix : selected.pathPrefixes()) {
            if (paths.stream().anyMatch(path -> path.startsWith(prefix))) {
                reasons.add("Changed path matches prefix: " + prefix);
            }
        }
        for (String term : selected.terms()) {
            if (matchesNormalizedTerm(text, term)) {
                reasons.add("Task text contains term: " + term);
            }
        }
        if (reasons.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ScopeMatch(scope, reasons, missingKnowledge));
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

    public record Selection(List<ScopeMatch> scopes)
    {
        public Selection
        {
            scopes = ImmutableList.copyOf(scopes);
        }
    }

    public record ScopeMatch(ContextScope scope, List<String> matchReasons, boolean missingKnowledge)
    {
        public ScopeMatch
        {
            requireNonNull(scope, "scope is null");
            matchReasons = ImmutableList.copyOf(matchReasons);
        }
    }
}
