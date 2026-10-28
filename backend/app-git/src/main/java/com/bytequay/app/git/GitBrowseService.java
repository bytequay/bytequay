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

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.git.command.GitCommandRunner.Result;
import com.google.common.base.Splitter;
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
    private static final Pattern PULL_REQUEST_SUFFIX = Pattern.compile("\\(#(\\d+)\\)\\s*$");
    private static final char FIELD = '\u001f';
    private static final char RECORD = '\u001e';
    private static final String LOG_FORMAT = "--format=%H" + FIELD + "%an" + FIELD + "%cn" + FIELD + "%aI" + FIELD + "%cI" + FIELD + "%s" + FIELD + "%b" + RECORD;
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
                .map(name -> new Remote(name, require(repository, "config", "--get", "remote." + name + ".url").strip(), remoteHeadBranch(repository, name)))
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
        String output = require(repository, "log", "--no-show-signature", LOG_FORMAT, "-n", String.valueOf(limit), ref, "--");
        return parseCommits(output);
    }

    private static List<Commit> parseCommits(String output)
    {
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
        return readDiff(repository, diffBase(repository, base), head);
    }

    private String diffBase(Path repository, String base)
    {
        if (!base.endsWith("^") || git.run(repository, "rev-parse", "--verify", "--quiet", base).successful()) {
            return base;
        }
        // Read the actual parents: a shallow boundary is not an initial commit.
        String commit = require(repository, "cat-file", "commit", base.substring(0, base.length() - 1));
        if (commit.lines().takeWhile(line -> !line.isEmpty()).anyMatch(line -> line.startsWith("parent "))) {
            return base;
        }
        return require(repository, "hash-object", "-t", "tree", "/dev/null").strip();
    }

    private List<DiffFile> readDiff(Path repository, String... revisions)
    {
        String output = require(repository, ImmutableList.<String>builder()
                .add("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--submodule=short", "-M", "--raw", "-z", "--patch")
                .addAll(ImmutableList.copyOf(revisions)).add("--").build().toArray(String[]::new));
        return splitPatch(output);
    }

    public FileContent diffFileContent(Path repository, String base, String head, String path)
    {
        checkArgument(!base.isBlank(), "base is blank");
        checkArgument(!head.isBlank(), "head is blank");
        checkArgument(!path.isBlank(), "path is blank");
        checkArgument(!path.startsWith("/"), "path is absolute");
        return new FileContent(fileAtRevision(repository, diffBase(repository, base), path), fileAtRevision(repository, head, path));
    }

    private Optional<String> fileAtRevision(Path repository, String revision, String path)
    {
        Result result = git.runStdout(repository, "show", revision + ":" + path);
        if (!result.successful()) {
            return Optional.empty();
        }
        return Optional.of(result.requireOutput(message -> new IllegalArgumentException("Git failed: " + message)));
    }

    public WorktreeStatus status(Path repository)
    {
        String branch = require(repository, "rev-parse", "--abbrev-ref", "HEAD").strip();
        Iterable<String> untracked = Splitter.on('\0').omitEmptyStrings().split(require(repository, "ls-files", "--others", "--exclude-standard", "-z"));
        List<DiffFile> tracked = readDiff(repository, "HEAD");

        ImmutableList.Builder<DiffFile> files = ImmutableList.builder();
        files.addAll(tracked);
        int untrackedPatches = 0;
        for (String path : untracked) {
            String patch = "";
            if (untrackedPatches < MAXIMUM_UNTRACKED_PATCHES) {
                untrackedPatches++;
                // Exit code 1 means "differences found"; that is success here.
                Result diff = git.runStdout(repository, 1, "diff", "--no-color", "--no-ext-diff", "--no-textconv", "--no-index", "--", "/dev/null", path);
                if (diff.exitCode() == 1 && !diff.truncated() && diff.output().startsWith("diff --git ")) {
                    patch = diff.output();
                }
            }
            files.add(diffFile(path, "A", patch));
        }
        return new WorktreeStatus(branch, files.build());
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
        return squash(repository, branch, sha, sha, message);
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
        checkArgument(require(repository, "status", "--porcelain").isBlank(), "The working tree has uncommitted changes; commit or stash them first");
        if (!git.run(repository, "merge-base", "--is-ancestor", baseSha, tipSha).successful()
                || !git.run(repository, "merge-base", "--is-ancestor", tipSha, "HEAD").successful()) {
            throw new IllegalArgumentException("The commits are not an in-order span of " + branch);
        }
        checkArgument(git.run(repository, "rev-parse", "--verify", "--quiet", baseSha + "^^{commit}").successful(), "The repository's first commit cannot be rewritten");
        checkArgument(require(repository, "rev-list", "--merges", baseSha + "^.." + "HEAD").isBlank(),
                "The span or the commits above it contain a merge commit; rewriting would flatten it");
    }

    /** New commit with {@code tipSha}'s tree and author on {@code baseSha}'s parent. */
    private String replacementCommit(Path repository, String tipSha, String baseSha, String message)
    {
        checkArgument(!message.isBlank(), "message is blank");
        String[] author = require(repository, "log", "-1", "--format=%an" + FIELD + "%ae" + FIELD + "%aI", tipSha)
                .strip()
                .split(String.valueOf(FIELD), 3);
        Map<String, String> environment = ImmutableMap.of("GIT_AUTHOR_NAME", author[0], "GIT_AUTHOR_EMAIL", author[1], "GIT_AUTHOR_DATE", author[2]);
        Result created = git.run(repository, Duration.ofSeconds(60), environment, ImmutableList.of("commit-tree", tipSha + "^{tree}", "-p", baseSha + "^", "-m", message));
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
        return git.runStdout(repository, arguments).requireOutput(message -> new IllegalArgumentException("Git failed: " + message));
    }

    private static List<String> lines(String output)
    {
        return output.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(toImmutableList());
    }

    private static List<DiffFile> splitPatch(String output)
    {
        if (output.isEmpty()) {
            return ImmutableList.of();
        }
        // One Git read pairs literal NUL-delimited filenames with patches, regardless of header quoting or concurrent edits.
        int boundary = output.indexOf("\0\0");
        checkArgument(boundary >= 0, "Git returned incomplete diff metadata");
        List<String> entries = Splitter.on('\0').splitToList(output.substring(0, boundary));
        List<String> patches = Splitter.on("\ndiff --git ").splitToList(output.substring(boundary + 2));
        ImmutableList.Builder<DiffFile> files = ImmutableList.builder();
        int patchIndex = 0;
        for (int index = 0; index < entries.size(); ) {
            String metadata = entries.get(index++);
            String status = metadata.substring(metadata.lastIndexOf(' ') + 1);
            checkArgument(index < entries.size(), "Git returned incomplete diff paths");
            String path = entries.get(index++);
            if (status.startsWith("R") || status.startsWith("C")) {
                checkArgument(index < entries.size(), "Git returned incomplete rename paths");
                path = entries.get(index++);
            }
            checkArgument(patchIndex < patches.size(), "Git returned incomplete file patches");
            String patch = (patchIndex == 0 ? "" : "diff --git ") + patches.get(patchIndex++);
            if (status.equals("T")) {
                // Git represents a type change as deletion and addition patches for one raw record.
                checkArgument(patchIndex < patches.size(), "Git returned an incomplete type-change patch");
                patch += "\ndiff --git " + patches.get(patchIndex++);
            }
            files.add(diffFile(path, status, patch));
        }
        checkArgument(patchIndex == patches.size(), "Git returned unmatched file patches");
        return files.build();
    }

    private static DiffFile diffFile(String path, String status, String patch)
    {
        String kind = switch (status.charAt(0)) {
            case 'A', 'C' -> "A";
            case 'D' -> "D";
            case 'R' -> "R";
            default -> "M";
        };
        int additions = 0;
        int deletions = 0;
        boolean hunk = false;
        for (String line : Splitter.on('\n').split(patch)) {
            if (line.startsWith("diff --git ")) {
                hunk = false;
            }
            else if (line.startsWith("@@ ")) {
                hunk = true;
            }
            else if (hunk) {
                additions += line.startsWith("+") ? 1 : 0;
                deletions += line.startsWith("-") ? 1 : 0;
            }
        }
        return new DiffFile(path, kind, additions, deletions, patch.isEmpty() ? Optional.empty() : Optional.of(patch));
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
