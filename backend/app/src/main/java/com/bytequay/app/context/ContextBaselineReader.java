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

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextReference;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Reads bounded regular-file evidence from the monitored baseline already frozen with a review. */
@Service
public class ContextBaselineReader
{
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final int INVENTORY_BYTES = 8 * 1024;
    private static final int BLOB_BYTES = 2 * 1024 * 1024;
    private static final int EXCERPT_BYTES = 16 * 1024;
    private static final int DEFAULT_SOURCE_LINES = 200;
    private static final int DIRECTORY_BYTES = 128 * 1024;
    private static final int DIRECTORY_PAGE_SIZE = 100;
    private static final int DIRECTORY_PAGES = 10;
    private static final int SEARCH_BYTES = 5 * 1024 * 1024;
    private static final int SEARCH_PAGE_SIZE = 50;

    private final GitCommandRunner git;
    private final Clock clock;

    public ContextBaselineReader(GitCommandRunner git, Clock clock)
    {
        this.git = requireNonNull(git, "git is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Reads the same bounded evidence for an application-owned learning task without requiring a PR or review run. */
    public Read read(WorkspaceRepositoryContext workspace, long repositoryId, String baseline, String path, int startLine, Optional<Integer> endLine)
    {
        checkBaseline(workspace, repositoryId, baseline);
        requireNonNull(endLine, "endLine is null");
        ContextLimits.text(path, "path", 1000);
        ContextLimits.pathPrefix(path + "/");
        checkArgument(startLine > 0, "The starting line must be positive");
        checkArgument(endLine.isEmpty() || endLine.orElseThrow() >= startLine, "The ending line must not precede the starting line");
        GitCommandRunner.Result inventory = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, INVENTORY_BYTES,
                ImmutableList.of("--literal-pathspecs", "ls-tree", "-z", "--full-tree", baseline, "--", path));
        checkArgument(inventory.successful() && !inventory.truncated() && ContextLimits.bytes(inventory.output()) <= INVENTORY_BYTES,
                "The baseline file identity could not be read completely");
        String blob = regularBlob(inventory.output(), path);
        GitCommandRunner.Result content = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, BLOB_BYTES, ImmutableList.of("cat-file", "blob", blob));
        checkArgument(content.successful() && !content.truncated() && ContextLimits.bytes(content.output()) <= BLOB_BYTES,
                "The baseline file could not be read completely within the 2 MiB limit");
        checkArgument(content.output().indexOf('\0') < 0 && content.output().indexOf('\uFFFD') < 0, "The baseline file is not supported UTF-8 text");
        List<String> lines = content.output().lines().collect(ImmutableList.toImmutableList());
        checkArgument(startLine <= lines.size() || (lines.isEmpty() && startLine == 1), "The starting line is outside the baseline file");
        int end = (int) Math.min(lines.size(), endLine.map(Integer::longValue).orElse((long) startLine + DEFAULT_SOURCE_LINES - 1));
        StringBuilder window = new StringBuilder();
        for (int line = startLine; line <= end; line++) {
            window.append(line).append("  ").append(lines.get(line - 1)).append('\n');
        }
        return new Read(new ContextReference.RepositoryFile(repositoryId, workspace.monitoredRepository(), path, baseline), blob,
                Instant.now(clock), startLine, end, lines.size(), window.toString(), false).limitedTo(EXCERPT_BYTES);
    }

    /**
     * Lists one directory at the immutable monitored baseline, without reading the worktree, following symlinks, or fetching objects.
     * Directory paths may have one trailing slash; root is the empty path. Pages are zero-based, contain at most 100 entries, and are limited to ten pages per directory.
     * The complete local listing must fit in 128 KiB; incomplete output is rejected rather than presented as complete structure.
     * Returned entries are discovery metadata, not evidence that their file content was read or that no useful knowledge exists.
     */
    public Directory directory(WorkspaceRepositoryContext workspace, long repositoryId, String baseline, String path, int page)
    {
        checkBaseline(workspace, repositoryId, baseline);
        requireNonNull(path, "path is null");
        if (!path.isEmpty()) {
            ContextLimits.text(path, "directory path", 1000);
            String prefix = path.endsWith("/") ? path : path + "/";
            ContextLimits.pathPrefix(prefix);
            path = prefix.substring(0, prefix.length() - 1);
        }
        checkArgument(page >= 0 && page < DIRECTORY_PAGES, "Directory page must be between zero and nine; narrow the selected directory");
        String tree = baseline;
        if (!path.isEmpty()) {
            GitCommandRunner.Result identity = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, INVENTORY_BYTES,
                    ImmutableList.of("--literal-pathspecs", "ls-tree", "-z", "--full-tree", baseline, "--", path));
            checkArgument(identity.successful() && !identity.truncated() && ContextLimits.bytes(identity.output()) <= INVENTORY_BYTES,
                    "The baseline directory identity could not be read completely");
            List<TreeEntry> entries = treeEntries(identity.output(), "");
            checkArgument(entries.size() == 1 && entries.getFirst().path().equals(path) && entries.getFirst().kind().equals("DIRECTORY"),
                    "The selected baseline path is not a directory");
            tree = entries.getFirst().objectId();
        }
        GitCommandRunner.Result listing = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, DIRECTORY_BYTES,
                ImmutableList.of("ls-tree", "-z", tree));
        checkArgument(listing.successful() && !listing.truncated() && ContextLimits.bytes(listing.output()) <= DIRECTORY_BYTES,
                "The baseline directory listing exceeds the complete 128 KiB read budget or is unavailable");
        List<TreeEntry> entries = treeEntries(listing.output(), path.isEmpty() ? "" : path + "/");
        int start = page * DIRECTORY_PAGE_SIZE;
        checkArgument(start < entries.size() || (page == 0 && entries.isEmpty()), "Directory page is outside the listing");
        List<TreeEntry> selected = new TopKSelector(DIRECTORY_PAGE_SIZE).select(entries.stream().skip(start)).collect(ImmutableList.toImmutableList());
        return new Directory(path, baseline, selected, page, start + selected.size() < entries.size());
    }

    /** Finds filenames anywhere in the frozen tree, including directories never visited with {@link #directory}. */
    public PathSearch searchPaths(WorkspaceRepositoryContext workspace, long repositoryId, String baseline, String query, int page)
    {
        checkBaseline(workspace, repositoryId, baseline);
        checkSearch(query, page);
        GitCommandRunner.Result listing = git.runLocalBoundedStdout(workspace.repository(), COMMAND_TIMEOUT, SEARCH_BYTES,
                ImmutableList.of("ls-tree", "-r", "-z", "--name-only", baseline));
        checkArgument(listing.successful() && !listing.truncated() && (listing.output().isEmpty() || listing.output().endsWith("\0")),
                "Frozen filename inventory exceeds the complete 5 MiB read budget or is unavailable; narrow by directory");
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> names = Arrays.stream(listing.output().split("\0", -1))
                .filter(name -> !name.isEmpty() && name.toLowerCase(Locale.ROOT).contains(needle))
                .collect(ImmutableList.toImmutableList());
        int start = page * SEARCH_PAGE_SIZE;
        checkArgument(start < names.size() || (page == 0 && names.isEmpty()), "Filename search page is outside the matches");
        List<String> matches = names.stream().skip(start).limit(SEARCH_PAGE_SIZE).collect(ImmutableList.toImmutableList());
        return new PathSearch(query, matches, page, start + matches.size() < names.size());
    }

    /** Literal case-insensitive text search at the frozen revision; broad queries must be narrowed when output exceeds the bound. */
    public TextSearch searchText(WorkspaceRepositoryContext workspace, long repositoryId, String baseline, String query, int page)
    {
        return searchText(workspace, repositoryId, baseline, query, page, ImmutableList.of());
    }

    public TextSearch searchText(WorkspaceRepositoryContext workspace, long repositoryId, String baseline, String query, int page, List<String> paths)
    {
        paths.forEach(path -> ContextLimits.pathPrefix(path.endsWith("/") ? path : path + "/"));
        checkBaseline(workspace, repositoryId, baseline);
        checkSearch(query, page);
        GitCommandRunner.Result output = git.runLocalBoundedStdout(workspace.repository(), COMMAND_TIMEOUT, SEARCH_BYTES,
                ImmutableList.<String>builder().add("grep", "--no-color", "-I", "-n", "-z", "-F", "-i", "-m", "2", "-e", query, baseline, "--")
                        .addAll(paths.stream().map(path -> ":(literal)" + path).toList()).build());
        checkArgument((output.successful() || output.exitCode() == 1) && !output.truncated(),
                "Frozen text search exceeded the complete 5 MiB read budget or is unavailable; use a narrower term");
        List<TextMatch> found = parseTextMatches(output.output(), baseline);
        int start = page * SEARCH_PAGE_SIZE;
        checkArgument(start < found.size() || (page == 0 && found.isEmpty()), "Text search page is outside the matches");
        List<TextMatch> matches = found.stream().skip(start).limit(SEARCH_PAGE_SIZE).collect(ImmutableList.toImmutableList());
        return new TextSearch(query, matches, page, start + matches.size() < found.size(), 2);
    }

    private static void checkSearch(String query, int page)
    {
        ContextLimits.text(query, "search query", 100);
        checkArgument(query.chars().noneMatch(Character::isISOControl), "Search query has a control character");
        checkArgument(page >= 0 && page <= Integer.MAX_VALUE / SEARCH_PAGE_SIZE, "Search page is invalid");
    }

    private static List<TextMatch> parseTextMatches(String output, String baseline)
    {
        ImmutableList.Builder<TextMatch> matches = ImmutableList.builder();
        int index = 0;
        while (index < output.length()) {
            int pathEnd = output.indexOf('\0', index);
            int lineEnd = pathEnd < 0 ? -1 : output.indexOf('\0', pathEnd + 1);
            int textEnd = lineEnd < 0 ? -1 : output.indexOf('\n', lineEnd + 1);
            checkArgument(pathEnd > index && lineEnd > pathEnd && textEnd > lineEnd, "Incomplete frozen text search result");
            String identity = output.substring(index, pathEnd);
            checkArgument(identity.startsWith(baseline + ":"), "Text search result differs from the frozen revision");
            String path = identity.substring(baseline.length() + 1);
            ContextLimits.pathPrefix(path + "/");
            int line = Integer.parseInt(output.substring(pathEnd + 1, lineEnd));
            matches.add(new TextMatch(path, line, ContextText.prefix(output.substring(lineEnd + 1, textEnd), 300)));
            index = textEnd + 1;
        }
        return matches.build();
    }

    public record PathSearch(String query, List<String> matches, int page, boolean hasMore)
    {
        public PathSearch
        {
            matches = ImmutableList.copyOf(matches);
        }
    }

    public record TextSearch(String query, List<TextMatch> matches, int page, boolean hasMore, int maxMatchesPerFile)
    {
        public TextSearch
        {
            matches = ImmutableList.copyOf(matches);
        }
    }

    public record TextMatch(String path, int line, String excerpt) {}

    private static void checkBaseline(WorkspaceRepositoryContext workspace, long repositoryId, String baseline)
    {
        requireNonNull(workspace, "workspace is null");
        checkArgument(repositoryId == RepositoryContextReader.repositoryId(workspace), "The monitored repository differs from the frozen context");
        checkArgument(baseline.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid frozen monitored baseline");
    }

    private static List<TreeEntry> treeEntries(String listing, String prefix)
    {
        checkArgument(listing.isEmpty() || listing.endsWith("\0"), "Incomplete baseline tree listing");
        if (listing.isEmpty()) {
            return ImmutableList.of();
        }
        return Arrays.stream(listing.substring(0, listing.length() - 1).split("\0", -1)).map(line -> {
            int tab = line.indexOf('\t');
            checkArgument(tab > 0, "Malformed baseline tree entry");
            String[] fields = line.substring(0, tab).split(" ", -1);
            checkArgument(fields.length == 3 && fields[2].matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Malformed baseline tree identity");
            String kind = switch (fields[0] + " " + fields[1]) {
                case "040000 tree" -> "DIRECTORY";
                case "100644 blob", "100755 blob" -> "FILE";
                case "120000 blob" -> "SYMLINK";
                case "160000 commit" -> "SUBMODULE";
                default -> throw new IllegalArgumentException("Unsupported baseline tree entry");
            };
            return new TreeEntry(prefix + line.substring(tab + 1), kind, fields[2]);
        }).collect(ImmutableList.toImmutableList());
    }

    public record Directory(String path, String commitSha, List<TreeEntry> entries, int page, boolean hasMore)
    {
        public Directory
        {
            entries = ImmutableList.copyOf(entries);
        }
    }

    public record TreeEntry(String path, String kind, String objectId) {}

    private static String regularBlob(String inventory, String path)
    {
        checkArgument(!inventory.isEmpty(), "The requested file does not exist at the frozen baseline");
        checkArgument(inventory.endsWith("\0") && inventory.indexOf('\0') == inventory.length() - 1, "The baseline file identity is malformed or ambiguous");
        int tab = inventory.indexOf('\t');
        checkArgument(tab > 0 && inventory.substring(tab + 1, inventory.length() - 1).equals(path), "The baseline file identity does not match the requested path");
        String[] fields = inventory.substring(0, tab).split(" ", -1);
        checkArgument(fields.length == 3 && (fields[0].equals("100644") || fields[0].equals("100755")) && fields[1].equals("blob")
                && fields[2].matches("[0-9a-f]{40}|[0-9a-f]{64}"), "The baseline source must be a regular file with a valid blob identity");
        return fields[2];
    }

    public record Read(ContextReference.RepositoryFile reference, String blobId, Instant readAt, int startLine, int endLine, int totalLines, String excerpt, boolean truncated)
    {
        /** Prefer complete lines so the next range can start at endLine + 1; an oversized first line remains explicitly partial. */
        public Read limitedTo(int maximumBytes)
        {
            checkArgument(maximumBytes > 0, "Source excerpt budget must be positive");
            String limited = ContextText.prefix(excerpt, maximumBytes);
            if (limited.equals(excerpt)) {
                return this;
            }
            int newline = limited.lastIndexOf('\n');
            if (newline >= 0) {
                limited = limited.substring(0, newline + 1);
            }
            int returnedEnd = startLine + (int) limited.lines().count() - 1;
            return new Read(reference, blobId, readAt, startLine, returnedEnd, totalLines, limited, true);
        }
    }
}
