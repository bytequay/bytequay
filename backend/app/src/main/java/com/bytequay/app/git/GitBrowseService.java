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
package com.bytequay.app.git;

import com.bytequay.app.git.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Read and rewrite operations for browsing a local checkout: the Commits tab's
 * History and Changes views run on this.
 *
 * <p>History rewrites (reword, squash) build the replacement commit with
 * {@code commit-tree} — the rewritten commit keeps the original tree, so the
 * follow-up {@code rebase --onto} replays descendants without conflicts.
 */
@Component
public class GitBrowseService
{
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(3);
    private static final Pattern PULL_REQUEST_SUFFIX = Pattern.compile("\\(#(\\d+)\\)\\s*$");
    private static final char FIELD = '\u001f';
    private static final char RECORD = '\u001e';
    private static final int MAXIMUM_UNTRACKED_PATCHES = 50;

    private final GitCommandRunner git;

    public GitBrowseService(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    public RepoOverview overview(Path repository)
    {
        String currentBranch = require(repository, "rev-parse", "--abbrev-ref", "HEAD").strip();
        List<String> branches = lines(require(repository, "for-each-ref", "--format=%(refname:short)", "refs/heads"));
        List<Remote> remotes = lines(require(repository, "remote")).stream()
                .map(name -> new Remote(
                        name,
                        require(repository, "config", "--get", "remote." + name + ".url").strip(),
                        remoteHeadBranch(repository, name)))
                .collect(toImmutableList());
        return new RepoOverview(currentBranch, branches, remotes);
    }

    /** The remote's default branch, when its HEAD symref is known locally. */
    private Optional<String> remoteHeadBranch(Path repository, String remote)
    {
        Result result = git.run(repository, "symbolic-ref", "--quiet", "refs/remotes/" + remote + "/HEAD");
        if (!result.successful()) {
            return Optional.empty();
        }
        String prefix = "refs/remotes/" + remote + "/";
        String target = result.output().strip();
        if (!target.startsWith(prefix)) {
            return Optional.empty();
        }
        return Optional.of(target.substring(prefix.length()));
    }

    public List<String> remoteBranches(Path repository, String remote)
    {
        checkArgument(!remote.isBlank(), "remote is blank");
        String prefix = "refs/remotes/" + remote + "/";
        return lines(require(repository, "for-each-ref", "--format=%(refname:short)", prefix)).stream()
                .map(name -> name.substring(remote.length() + 1))
                .filter(name -> !name.equals("HEAD"))
                .collect(toImmutableList());
    }

    public List<Commit> log(Path repository, String ref, int limit)
    {
        checkArgument(!ref.isBlank(), "ref is blank");
        checkArgument(limit > 0, "limit is not positive");
        String output = require(
                repository,
                "log",
                "--no-show-signature",
                "--format=%H" + FIELD + "%an" + FIELD + "%cn" + FIELD + "%aI" + FIELD + "%cI" + FIELD + "%s" + FIELD + "%b" + RECORD,
                "-n", String.valueOf(limit),
                ref,
                "--");
        ImmutableList.Builder<Commit> commits = ImmutableList.builder();
        for (String record : output.split(String.valueOf(RECORD))) {
            String entry = record.strip();
            if (entry.isEmpty()) {
                continue;
            }
            String[] fields = entry.split(String.valueOf(FIELD), 7);
            if (fields.length < 6) {
                continue;
            }
            String title = fields[5];
            Matcher pullRequest = PULL_REQUEST_SUFFIX.matcher(title);
            commits.add(new Commit(
                    fields[0],
                    title,
                    fields.length > 6 ? fields[6].strip() : "",
                    fields[1],
                    fields[2],
                    fields[3],
                    fields[4],
                    pullRequest.find() ? Optional.of(Integer.parseInt(pullRequest.group(1))) : Optional.empty()));
        }
        return commits.build();
    }

    public List<DiffFile> diff(Path repository, String base, String head)
    {
        checkArgument(!base.isBlank(), "base is blank");
        checkArgument(!head.isBlank(), "head is blank");
        Map<String, int[]> numstat = numstat(require(repository, "diff", "--numstat", "-M", base, head, "--"));
        String patch = require(repository, "diff", "-M", base, head, "--");
        return splitPatch(patch, numstat);
    }

    public FileContent diffFileContent(Path repository, String base, String head, String path)
    {
        checkArgument(!base.isBlank(), "base is blank");
        checkArgument(!head.isBlank(), "head is blank");
        checkArgument(!path.isBlank(), "path is blank");
        checkArgument(!path.startsWith("/"), "path is absolute");
        return new FileContent(fileAtRevision(repository, base, path), fileAtRevision(repository, head, path));
    }

    private Optional<String> fileAtRevision(Path repository, String revision, String path)
    {
        Result result = git.run(repository, "show", revision + ":" + path);
        if (!result.successful() || result.truncated()) {
            return Optional.empty();
        }
        return Optional.of(result.output());
    }

    public WorktreeStatus status(Path repository)
    {
        String branch = require(repository, "rev-parse", "--abbrev-ref", "HEAD").strip();
        List<String> porcelain = lines(require(repository, "status", "--porcelain"));

        Map<String, int[]> numstat = numstat(require(repository, "diff", "--numstat", "HEAD", "--"));
        List<DiffFile> tracked = splitPatch(require(repository, "diff", "HEAD", "--"), numstat);

        ImmutableList.Builder<DiffFile> files = ImmutableList.builder();
        files.addAll(tracked);
        int untrackedPatches = 0;
        for (String line : porcelain) {
            if (!line.startsWith("??")) {
                continue;
            }
            String path = line.substring(3);
            Optional<String> patch = Optional.empty();
            int additions = 0;
            if (untrackedPatches < MAXIMUM_UNTRACKED_PATCHES) {
                untrackedPatches++;
                // Exit code 1 means "differences found"; that is success here.
                Result diff = git.run(repository, "diff", "--no-index", "--", "/dev/null", path);
                if (diff.exitCode() == 1 && !diff.truncated()) {
                    patch = Optional.of(diff.output());
                    additions = (int) diff.output().lines().filter(row -> row.startsWith("+") && !row.startsWith("+++")).count();
                }
            }
            files.add(new DiffFile(path, "A", additions, 0, patch));
        }
        return new WorktreeStatus(branch, files.build());
    }

    public void fetch(Path repository, List<String> remotes)
    {
        checkArgument(!remotes.isEmpty(), "remotes is empty");
        for (String remote : remotes) {
            Result result = git.run(repository, FETCH_TIMEOUT, ImmutableList.of("fetch", remote));
            if (!result.successful()) {
                throw new IllegalArgumentException("Fetching " + remote + " failed: " + result.output().strip());
            }
        }
    }

    public String commit(Path repository, String summary, String description)
    {
        checkArgument(!summary.isBlank(), "summary is blank");
        require(repository, "add", "-A");
        List<String> arguments = new ArrayList<>(ImmutableList.of("commit", "-m", summary));
        if (!description.isBlank()) {
            arguments.add("-m");
            arguments.add(description);
        }
        require(repository, arguments.toArray(String[]::new));
        return require(repository, "rev-parse", "HEAD").strip();
    }

    public String reword(Path repository, String branch, String sha, String message)
    {
        checkRewriteAllowed(repository, branch, sha, sha);
        String replacement = replacementCommit(repository, sha, sha, message);
        replay(repository, replacement, sha, branch);
        return replacement;
    }

    /**
     * Squashes the contiguous {@code baseSha..tipSha} span into one commit
     * carrying {@code message}, authored like the tip commit.
     */
    public String squash(Path repository, String branch, String baseSha, String tipSha, String message)
    {
        checkRewriteAllowed(repository, branch, baseSha, tipSha);
        String replacement = replacementCommit(repository, tipSha, baseSha, message);
        replay(repository, replacement, tipSha, branch);
        return replacement;
    }

    private void checkRewriteAllowed(Path repository, String branch, String baseSha, String tipSha)
    {
        checkArgument(!branch.isBlank(), "branch is blank");
        checkArgument(!baseSha.isBlank(), "baseSha is blank");
        checkArgument(!tipSha.isBlank(), "tipSha is blank");
        String currentBranch = require(repository, "rev-parse", "--abbrev-ref", "HEAD").strip();
        if (!currentBranch.equals(branch)) {
            throw new IllegalArgumentException("Branch " + branch + " is not checked out; " + currentBranch + " is");
        }
        if (!require(repository, "status", "--porcelain").isBlank()) {
            throw new IllegalArgumentException("The working tree has uncommitted changes; commit or stash them first");
        }
        if (!git.run(repository, "merge-base", "--is-ancestor", baseSha, tipSha).successful()
                || !git.run(repository, "merge-base", "--is-ancestor", tipSha, "HEAD").successful()) {
            throw new IllegalArgumentException("The commits are not an in-order span of " + branch);
        }
        if (!git.run(repository, "rev-parse", "--verify", "--quiet", baseSha + "^^{commit}").successful()) {
            throw new IllegalArgumentException("The repository's first commit cannot be rewritten");
        }
        if (!require(repository, "rev-list", "--merges", baseSha + "^.." + "HEAD").isBlank()) {
            throw new IllegalArgumentException("The span or the commits above it contain a merge commit; rewriting would flatten it");
        }
    }

    /** New commit with {@code tipSha}'s tree and author on {@code baseSha}'s parent. */
    private String replacementCommit(Path repository, String tipSha, String baseSha, String message)
    {
        checkArgument(!message.isBlank(), "message is blank");
        String[] author = require(repository, "log", "-1", "--format=%an" + FIELD + "%ae" + FIELD + "%aI", tipSha)
                .strip()
                .split(String.valueOf(FIELD), 3);
        Map<String, String> environment = ImmutableMap.of(
                "GIT_AUTHOR_NAME", author[0],
                "GIT_AUTHOR_EMAIL", author[1],
                "GIT_AUTHOR_DATE", author[2]);
        Result created = git.run(
                repository,
                Duration.ofSeconds(60),
                environment,
                ImmutableList.of("commit-tree", tipSha + "^{tree}", "-p", baseSha + "^", "-m", message));
        if (!created.successful()) {
            throw new IllegalArgumentException("Git failed: " + created.output().strip());
        }
        return created.output().strip();
    }

    /**
     * Moves {@code branch} so the commits above {@code oldTip} sit on
     * {@code replacement}. The replacement carries {@code oldTip}'s exact tree,
     * so the replay never conflicts.
     */
    private void replay(Path repository, String replacement, String oldTip, String branch)
    {
        Result result = git.run(repository, "rebase", "--onto", replacement, oldTip, branch);
        if (!result.successful()) {
            git.run(repository, "rebase", "--abort");
            throw new IllegalStateException("Git rebase failed unexpectedly: " + result.output().strip());
        }
    }

    private String require(Path repository, String... arguments)
    {
        Result result = git.run(repository, arguments);
        if (!result.successful()) {
            throw new IllegalArgumentException("Git failed: " + result.output().strip());
        }
        return result.output();
    }

    private static List<String> lines(String output)
    {
        return output.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(toImmutableList());
    }

    private static Map<String, int[]> numstat(String output)
    {
        ImmutableMap.Builder<String, int[]> stats = ImmutableMap.builder();
        for (String line : output.lines().collect(toImmutableList())) {
            String[] fields = line.split("\t");
            if (fields.length < 3) {
                continue;
            }
            int additions = fields[0].equals("-") ? 0 : Integer.parseInt(fields[0]);
            int deletions = fields[1].equals("-") ? 0 : Integer.parseInt(fields[1]);
            // Renames appear as "old => new" or "prefix{old => new}suffix"; key by the new path.
            stats.put(renameTarget(fields[2]), new int[] {additions, deletions});
        }
        return stats.buildKeepingLast();
    }

    /** Git pads +++/--- header paths containing spaces with a trailing tab. */
    private static String headerPath(String value)
    {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\t') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String renameTarget(String path)
    {
        int brace = path.indexOf('{');
        int arrow = path.indexOf(" => ");
        if (arrow < 0) {
            return path;
        }
        if (brace < 0) {
            return path.substring(arrow + 4);
        }
        int close = path.indexOf('}', brace);
        String middle = path.substring(brace + 1, close);
        int middleArrow = middle.indexOf(" => ");
        String replaced = middleArrow < 0 ? middle : middle.substring(middleArrow + 4);
        return (path.substring(0, brace) + replaced + path.substring(close + 1)).replace("//", "/");
    }

    private static List<DiffFile> splitPatch(String patch, Map<String, int[]> numstat)
    {
        ImmutableList.Builder<DiffFile> files = ImmutableList.builder();
        List<String> lines = patch.lines().collect(toImmutableList());
        int index = 0;
        while (index < lines.size()) {
            if (!lines.get(index).startsWith("diff --git ")) {
                index++;
                continue;
            }
            int start = index;
            index++;
            while (index < lines.size() && !lines.get(index).startsWith("diff --git ")) {
                index++;
            }
            List<String> segment = lines.subList(start, index);
            String status = "M";
            String path = null;
            for (String line : segment) {
                if (line.startsWith("new file mode")) {
                    status = "A";
                }
                else if (line.startsWith("deleted file mode")) {
                    status = "D";
                }
                else if (line.startsWith("rename to ")) {
                    status = "R";
                    path = headerPath(line.substring("rename to ".length()));
                }
                else if (line.startsWith("+++ b/")) {
                    path = headerPath(line.substring("+++ b/".length()));
                }
                else if (path == null && line.startsWith("--- a/")) {
                    path = headerPath(line.substring("--- a/".length()));
                }
            }
            if (path == null) {
                // Binary or mode-only entry: fall back to the header's b-side path.
                String header = segment.getFirst();
                int bSide = header.indexOf(" b/");
                path = bSide < 0 ? header : header.substring(bSide + 3);
            }
            int[] stats = numstat.getOrDefault(path, new int[] {0, 0});
            files.add(new DiffFile(path, status, stats[0], stats[1], Optional.of(String.join("\n", segment))));
        }
        return files.build();
    }

    public record Remote(String name, String url, Optional<String> headBranch) {}

    public record RepoOverview(String currentBranch, List<String> branches, List<Remote> remotes) {}

    public record Commit(
            String sha,
            String title,
            String body,
            String author,
            String committer,
            String authoredAt,
            String committedAt,
            Optional<Integer> pullRequestNumber)
    {}

    public record DiffFile(String path, String status, int additions, int deletions, Optional<String> patch) {}

    public record FileContent(Optional<String> oldContent, Optional<String> newContent)
    {
        public FileContent
        {
            requireNonNull(oldContent, "oldContent is null");
            requireNonNull(newContent, "newContent is null");
        }
    }

    public record WorktreeStatus(String branch, List<DiffFile> files) {}
}
