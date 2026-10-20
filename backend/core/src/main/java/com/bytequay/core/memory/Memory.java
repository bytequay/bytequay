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

import com.bytequay.core.context.ContextLimits;
import com.google.common.collect.ImmutableList;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * One short rule an agent cannot read from the repository at its commit: a convention maintainers enforce, a way to build or test
 * that the docs don't give, or a specification that governs code, with the paths it applies to and the sources it came from. Agents never change a memory the user owns,
 * and a report flags a memory without editing or hiding it.
 *
 * @param title the situation it covers, so a reader can tell whether it applies before reading the text
 * @param scope path prefixes ending in {@code /}, file paths or globs; empty applies to the whole repository
 * @param version counts changes to the title, text and scope, for optimistic edits
 * @param previous the title, text and scope the last change replaced, one level deep
 * @param delivered how many agent turns received it
 * @param cited how many findings or answers cited it
 */
public record Memory(
        String id,
        String workspaceId,
        Kind kind,
        String title,
        String text,
        List<String> scope,
        List<MemorySource> sources,
        Origin origin,
        long version,
        Optional<Previous> previous,
        int delivered,
        int cited,
        List<MemoryReport> reports,
        Instant createdAt,
        Instant updatedAt)
{
    public static final int MAX_TITLE = 80;
    public static final int MAX_TEXT_BYTES = 600;
    public static final int MAX_SCOPE = 8;
    public static final int MAX_SOURCES = 8;
    public static final int MAX_REPORTS = 8;
    private static final int MAX_SCOPE_PATH = 300;
    private static final Pattern ID = Pattern.compile("m[1-9][0-9]{0,17}");

    /** RULE: what to do or avoid, when, and why. RECIPE: how to build, test or run something here, with the command. SPEC: a specification that governs code. */
    public enum Kind
    {
        RULE,
        RECIPE,
        SPEC,
    }

    public Memory
    {
        requireNonNull(id, "id is null");
        checkArgument(ID.matcher(id).matches(), "memory id %s is not m followed by a number", id);
        checkText(workspaceId, "workspaceId");
        requireNonNull(kind, "kind is null");
        checkTitle(title);
        checkMemoryText(text);
        scope = checkScope(scope);
        sources = ImmutableList.copyOf(sources);
        checkArgument(!sources.isEmpty() && sources.size() <= MAX_SOURCES, "a memory has 1 to %s sources", MAX_SOURCES);
        requireNonNull(origin, "origin is null");
        checkArgument(version >= 1, "version must be positive");
        requireNonNull(previous, "previous is null");
        checkArgument(delivered >= 0 && cited >= 0, "counts must not be negative");
        reports = ImmutableList.copyOf(reports);
        checkArgument(reports.size() <= MAX_REPORTS, "a memory keeps at most %s reports", MAX_REPORTS);
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    public long number()
    {
        return Long.parseLong(id.substring(1));
    }

    public boolean repositoryWide()
    {
        return scope.isEmpty();
    }

    /**
     * How closely the scope matches a repository path: the length of the longest scope entry that covers it, or empty when none
     * does. A directory path also matches the entries under it, so searching a directory finds what applies inside it.
     */
    public OptionalInt match(String path)
    {
        return scope.stream().filter(entry -> covers(entry, path)).mapToInt(String::length).max();
    }

    private static boolean covers(String entry, String path)
    {
        if (entry.chars().anyMatch(character -> character == '*' || character == '?' || character == '[' || character == '{')) {
            try {
                return FileSystems.getDefault().getPathMatcher("glob:" + entry).matches(Path.of(path));
            }
            // An invalid glob or path matches nothing.
            catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        String directoryEntry = entry.endsWith("/") ? entry : entry + "/";
        String directoryPath = path.endsWith("/") ? path : path + "/";
        return directoryPath.equals(directoryEntry) || path.startsWith(directoryEntry) || entry.startsWith(directoryPath);
    }

    /** The user's edit makes the memory theirs and settles its reports: they read it before changing it. */
    public Memory edited(String newTitle, String newText, Instant at)
    {
        return new Memory(id, workspaceId, kind, newTitle, newText, scope, sources, Origin.USER_EDIT, version + 1, Optional.of(new Previous(title, text, scope)),
                delivered, cited, ImmutableList.of(), createdAt, at);
    }

    /** Swaps the title, text and scope with the ones the last change replaced, so a second restore undoes the first. */
    public Memory restored(Instant at)
    {
        Previous replaced = previous.orElseThrow(() -> new IllegalStateException("Memory " + id + " has no previous version"));
        Origin restorer = origin.user() ? origin : Origin.USER_EDIT;
        return new Memory(id, workspaceId, kind, replaced.title(), replaced.text(), replaced.scope(), sources, restorer, version + 1,
                Optional.of(new Previous(title, text, scope)), delivered, cited, reports, createdAt, at);
    }

    /** An agent saw the rule again: the new sources are added, oldest kept first, up to {@link #MAX_SOURCES}; the text stays. */
    public Memory withSources(List<MemorySource> added, Instant at)
    {
        checkState(!origin.user(), "Memory %s is the user's; agents never change it", id);
        List<MemorySource> merged = Stream.concat(sources.stream(), added.stream().filter(source -> sources.stream().noneMatch(kept -> kept.key().equals(source.key()))))
                .limit(MAX_SOURCES).collect(ImmutableList.toImmutableList());
        return new Memory(id, workspaceId, kind, title, text, scope, merged, origin, version, previous, delivered, cited, reports, createdAt, at);
    }

    /** A newer source overrides the rule: the title, text and scope are replaced, the old ones kept as the previous version, and both sources stay. */
    public Memory replaced(String newTitle, String newText, List<String> newScope, List<MemorySource> added, Instant at)
    {
        Memory sourced = withSources(added, at);
        return new Memory(id, workspaceId, kind, newTitle, newText, newScope, sourced.sources(), origin, version + 1, Optional.of(new Previous(title, text, scope)),
                delivered, cited, ImmutableList.of(), createdAt, at);
    }

    /** An agent changes where a memory it saved applies; the scope it replaces is kept as the previous version. */
    public Memory rescoped(List<String> newScope, Instant at)
    {
        checkState(!origin.user(), "Memory %s is the user's; agents never change it", id);
        return new Memory(id, workspaceId, kind, title, text, newScope, sources, origin, version + 1, Optional.of(new Previous(title, text, scope)),
                delivered, cited, reports, createdAt, at);
    }

    /** Keeps the newest reports: the memory stays flagged until the user keeps, edits or deletes it. */
    public Memory reported(MemoryReport report, Instant at)
    {
        List<MemoryReport> kept = ImmutableList.<MemoryReport>builder().addAll(reports.size() < MAX_REPORTS ? reports : reports.subList(1, reports.size()))
                .add(report).build();
        return new Memory(id, workspaceId, kind, title, text, scope, sources, origin, version, previous, delivered, cited, kept, createdAt, at);
    }

    /** The user read the reports and keeps the memory as it is. */
    public Memory kept(Instant at)
    {
        return new Memory(id, workspaceId, kind, title, text, scope, sources, origin, version, previous, delivered, cited, ImmutableList.of(), createdAt, at);
    }

    public static void checkTitle(String title)
    {
        requireNonNull(title, "title is null");
        checkArgument(!title.isBlank() && title.codePointCount(0, title.length()) <= MAX_TITLE, "memory title must have 1 to %s characters", MAX_TITLE);
        checkArgument(title.chars().noneMatch(Character::isISOControl), "memory title is one line");
    }

    /** A title cut from longer words, such as a finding's title or an address: whole words up to {@value #MAX_TITLE} characters, marked when cut. */
    public static String titleFrom(String words)
    {
        String line = words.replaceAll("[\\s\\p{Cc}]+", " ").strip();
        if (line.codePointCount(0, line.length()) <= MAX_TITLE) {
            return line;
        }
        String cut = line.substring(0, line.offsetByCodePoints(0, MAX_TITLE - 1));
        int space = cut.lastIndexOf(' ');
        return (space > MAX_TITLE / 2 ? cut.substring(0, space) : cut).replaceAll("[\\s,;:.]+$", "") + "…";
    }

    public static void checkMemoryText(String text)
    {
        ContextLimits.text(text, "memory text", MAX_TEXT_BYTES);
        checkArgument(text.indexOf('\n') < 0 && text.indexOf('\r') < 0, "memory text is one paragraph without line breaks");
    }

    public static List<String> checkScope(List<String> scope)
    {
        List<String> paths = ImmutableList.copyOf(scope);
        ContextLimits.unique(paths, "scope", MAX_SCOPE);
        for (String path : paths) {
            checkArgument(!path.isBlank() && path.length() <= MAX_SCOPE_PATH, "scope path must have 1 to %s characters", MAX_SCOPE_PATH);
            checkArgument(!path.startsWith("/") && !path.startsWith("-") && !path.contains("\\") && path.chars().noneMatch(Character::isISOControl),
                    "scope path %s must be relative to the repository root", path);
            checkArgument(Arrays.stream(path.split("/")).noneMatch(part -> part.equals(".") || part.equals("..")), "scope path %s must not contain . or ..", path);
        }
        return paths;
    }

    /**
     * Who saved the memory.
     *
     * @param user whether the user saved or edited it; agents never change such a memory
     * @param description what saved it, as the Memory page states it
     * @param taskId the agent task that saved it, when an agent did
     */
    public record Origin(boolean user, String description, Optional<String> taskId)
    {
        public static final Origin USER_EDIT = new Origin(true, "Edited by you", Optional.empty());

        public Origin
        {
            checkText(description, "origin description");
            requireNonNull(taskId, "taskId is null");
            checkArgument(!user || taskId.isEmpty(), "a memory the user saved has no saving task");
        }
    }

    public record Previous(String title, String text, List<String> scope)
    {
        public Previous
        {
            checkTitle(title);
            checkMemoryText(text);
            scope = checkScope(scope);
        }
    }
}
