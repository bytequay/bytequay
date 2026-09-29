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

import com.bytequay.app.context.ContextSourceCheck.Status;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextSourceEvidence;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Compares a note's cited repository lines with one commit, reading only local Git objects: no fetch, worktree or index.
 * A file whose blob changed still counts as unchanged while the cited lines appear in it, in order, wherever they now are.
 */
@Service
public class ContextSourceChecker
{
    private static final String SHA = "[0-9a-f]{40}|[0-9a-f]{64}";
    private static final String GIT_BLOB = "git-blob:";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final int HEAD_BYTES = 256;
    private static final int INVENTORY_BYTES = 64 * 1024;
    private static final int BLOB_BYTES = 2 * 1024 * 1024;
    // ContextBaselineReader saves every captured line as "<line number>  <text>".
    private static final Pattern NUMBERED_LINE = Pattern.compile("(\\d{1,9})  (.*)", Pattern.DOTALL);
    private static final Compared UNCHECKED = new Compared(Status.UNCHECKED, Optional.empty());

    private final GitCommandRunner git;
    private final WorkspaceRepositoryService workspaces;

    public ContextSourceChecker(GitCommandRunner git, WorkspaceRepositoryService workspaces)
    {
        this.git = requireNonNull(git, "git is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    /** The committed HEAD of a task checkout, ignoring uncommitted edits; empty when Git cannot resolve it. */
    public Optional<String> head(Path worktree)
    {
        requireNonNull(worktree, "worktree is null");
        try {
            GitCommandRunner.Result result = git.runLocalBounded(worktree, COMMAND_TIMEOUT, HEAD_BYTES, ImmutableList.of("rev-parse", "--verify", "HEAD^{commit}"));
            String head = result.output().strip();
            return result.successful() && !result.truncated() && head.matches(SHA) ? Optional.of(head) : Optional.empty();
        }
        catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /**
     * @param sources note sources keyed by the reference the note text uses, in note order
     * @return one result per source; a failed lookup leaves sources UNCHECKED with a gap instead of throwing
     */
    public ContextSourceCheck check(String workspaceId, Optional<String> commit, Map<String, ContextSourceEvidence> sources)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(commit, "commit is null");
        requireNonNull(sources, "sources is null");
        List<String> gaps = new ArrayList<>();
        Optional<String> compared = commit.filter(sha -> sha.matches(SHA));
        Map<String, Compared> results = new HashMap<>();
        if (compared.isEmpty()) {
            gaps.add("No commit was available to compare cited lines with.");
        }
        else {
            try {
                results.putAll(compare(workspaces.requireLocalRepository(workspaceId), compared.orElseThrow(), sources, gaps));
            }
            catch (RuntimeException failure) {
                gaps.add("Cited lines could not be compared: " + ContextText.excerpt(Optional.ofNullable(failure.getMessage()).orElse("unknown failure"), 300));
            }
        }
        ImmutableList.Builder<ContextSourceCheck.Source> checked = ImmutableList.builder();
        sources.forEach((ref, source) -> {
            Optional<ContextReference.RepositoryFile> file = source.reference() instanceof ContextReference.RepositoryFile found ? Optional.of(found) : Optional.empty();
            Compared result = results.getOrDefault(ref, UNCHECKED);
            checked.add(new ContextSourceCheck.Source(ref, source.reference().getKind(), file.map(ContextReference.RepositoryFile::path),
                    file.flatMap(ignored -> excerpt(source.excerpt())).map(excerpt -> range(excerpt.start(), excerpt.lines().size())),
                    result.currentLines(), file.map(ContextReference.RepositoryFile::commit), result.status()));
        });
        return new ContextSourceCheck(compared, checked.build(), gaps.stream().distinct().collect(toImmutableList()));
    }

    private Map<String, Compared> compare(WorkspaceRepositoryContext workspace, String commit, Map<String, ContextSourceEvidence> sources, List<String> gaps)
    {
        Map<String, Cited> cited = new LinkedHashMap<>();
        sources.forEach((ref, source) -> cited(workspace, source).ifPresent(found -> cited.put(ref, found)));
        if (cited.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<String, String> tree = inventory(workspace, commit, cited.values().stream().map(Cited::path).distinct().collect(toImmutableList()));
        Map<String, Optional<List<String>>> files = new HashMap<>();
        Map<String, Compared> results = new HashMap<>();
        cited.forEach((ref, source) -> results.put(ref, compare(workspace, source, Optional.ofNullable(tree.get(source.path())), files, gaps)));
        return results;
    }

    private Compared compare(WorkspaceRepositoryContext workspace, Cited cited, Optional<String> identity, Map<String, Optional<List<String>>> files, List<String> gaps)
    {
        String[] fields = identity.map(value -> value.split(" ", -1)).orElse(new String[0]);
        if (fields.length != 3 || !fields[1].equals("blob") || !(fields[0].equals("100644") || fields[0].equals("100755"))) {
            return new Compared(Status.ABSENT, Optional.empty());
        }
        if (fields[2].equals(cited.blob())) {
            return new Compared(Status.UNCHANGED, Optional.empty());
        }
        if (cited.excerpt().isEmpty()) {
            gaps.add("The saved lines of " + cited.path() + " end in a partial line, so its changed file cannot be compared.");
            return UNCHECKED;
        }
        Optional<List<String>> lines = files.computeIfAbsent(fields[2], blob -> lines(workspace, blob, gaps));
        if (lines.isEmpty()) {
            return UNCHECKED;
        }
        Excerpt excerpt = cited.excerpt().orElseThrow();
        List<Integer> found = occurrences(lines.orElseThrow(), excerpt.lines());
        if (found.isEmpty()) {
            return new Compared(Status.CHANGED, Optional.empty());
        }
        int start = found.getFirst() + 1;
        return new Compared(Status.UNCHANGED, found.size() == 1 && start != excerpt.start() ? Optional.of(range(start, excerpt.lines().size())) : Optional.empty());
    }

    private Map<String, String> inventory(WorkspaceRepositoryContext workspace, String commit, List<String> paths)
    {
        GitCommandRunner.Result result = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, INVENTORY_BYTES,
                ImmutableList.<String>builder().add("--literal-pathspecs", "ls-tree", "-z", "--full-tree", commit, "--").addAll(paths).build());
        checkArgument(result.successful() && !result.truncated(), "the cited files could not be listed at commit %s", commit);
        return ContextInputReader.parse(result.output());
    }

    private Optional<List<String>> lines(WorkspaceRepositoryContext workspace, String blob, List<String> gaps)
    {
        try {
            GitCommandRunner.Result content = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, BLOB_BYTES, ImmutableList.of("cat-file", "blob", blob));
            checkArgument(content.successful() && !content.truncated() && content.output().indexOf('\0') < 0 && content.output().indexOf('�') < 0,
                    "Unreadable blob");
            return Optional.of(content.output().lines().collect(toImmutableList()));
        }
        catch (RuntimeException failure) {
            gaps.add("A changed cited file could not be read as UTF-8 text within 2 MiB.");
            return Optional.empty();
        }
    }

    private static Optional<Cited> cited(WorkspaceRepositoryContext workspace, ContextSourceEvidence source)
    {
        if (!(source.reference() instanceof ContextReference.RepositoryFile file) || source.capture() != ContextSourceEvidence.Capture.BYTEQUAY_READ
                || (file.repositoryId() != RepositoryContextReader.repositoryId(workspace) && file.repositoryId() != workspace.selectedRepositoryId())) {
            return Optional.empty();
        }
        return source.contentHash().map(hash -> hash.startsWith(GIT_BLOB) ? hash.substring(GIT_BLOB.length()) : hash).filter(blob -> blob.matches(SHA))
                .map(blob -> new Cited(file.path(), blob, excerpt(source.excerpt())));
    }

    /** The saved line numbers and text, or empty when the excerpt is not complete consecutive numbered lines. */
    static Optional<Excerpt> excerpt(String saved)
    {
        if (saved.isEmpty() || !saved.endsWith("\n")) {
            return Optional.empty();
        }
        ImmutableList.Builder<String> lines = ImmutableList.builder();
        int start = -1;
        int next = -1;
        for (String numbered : saved.substring(0, saved.length() - 1).split("\n", -1)) {
            Matcher matcher = NUMBERED_LINE.matcher(numbered);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            int number = Integer.parseInt(matcher.group(1));
            if (number < 1 || (next >= 0 && number != next)) {
                return Optional.empty();
            }
            start = start < 0 ? number : start;
            next = number + 1;
            lines.add(matcher.group(2));
        }
        return Optional.of(new Excerpt(start, lines.build()));
    }

    // ponytail: a plain scan costs file lines × cited lines, bounded by the 2 MiB file limit; stops at a second match.
    private static List<Integer> occurrences(List<String> file, List<String> cited)
    {
        ImmutableList.Builder<Integer> found = ImmutableList.builder();
        int count = 0;
        for (int start = 0; start + cited.size() <= file.size() && count < 2; start++) {
            if (file.subList(start, start + cited.size()).equals(cited)) {
                found.add(start);
                count++;
            }
        }
        return found.build();
    }

    private static String range(int start, int size)
    {
        return start + "-" + (start + size - 1);
    }

    record Excerpt(int start, List<String> lines) {}

    private record Cited(String path, String blob, Optional<Excerpt> excerpt) {}

    private record Compared(Status status, Optional<String> currentLines) {}
}
