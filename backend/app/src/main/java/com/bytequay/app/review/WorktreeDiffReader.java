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
package com.bytequay.app.review;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Bounded, local-only Git reads for task changes, without taking Git's optional index lock or executing external diff drivers. */
@Component
public class WorktreeDiffReader
{
    private static final int MAXIMUM_COMMITS = 500;
    private static final List<String> DIFF_OPTIONS = ImmutableList.of(
            "--no-color", "--no-ext-diff", "--no-textconv", "--submodule=short", "-M", "--src-prefix=a/", "--dst-prefix=b/");
    private final GitCommandRunner git;

    public WorktreeDiffReader(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    public Result read(Path checkout, String... arguments)
    {
        return git.runLocalBoundedStdout(checkout, Duration.ofSeconds(30), GitCommandRunner.MAXIMUM_OUTPUT_BYTES, ImmutableList.<String>builder()
                .add("--no-optional-locks", "--literal-pathspecs", "-c", "diff.autoRefreshIndex=false", "-c", "core.quotePath=false")
                .addAll(ImmutableList.copyOf(arguments)).build());
    }

    public String require(Path checkout, String... arguments)
    {
        Result result = read(checkout, arguments);
        checkState(result.successful() && !result.truncated(), "Cannot read local changes: %s", result.output().strip());
        return result.output();
    }

    public String commit(Path checkout, String revision)
    {
        checkArgument(revision.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "A full commit ID is required");
        return require(checkout, "rev-parse", "--verify", revision + "^{commit}").strip();
    }

    public History history(Path checkout, String base, String head)
    {
        String output = require(checkout, "log", "--no-show-signature", "--format=%H%x00%P%x00%an%x00%aI%x00%B%x00", "-n", "501", base + ".." + head, "--");
        List<String> fields = Splitter.on('\0').splitToList(output);
        ImmutableList.Builder<DiffCommit> commits = ImmutableList.builder();
        for (int index = 0; index + 4 < fields.size() && index / 5 < MAXIMUM_COMMITS; index += 5) {
            String parent = Splitter.on(' ').splitToList(fields.get(index + 1)).getFirst();
            commits.add(new DiffCommit(fields.get(index).strip(), parent, fields.get(index + 4).strip(), fields.get(index + 2), Instant.parse(fields.get(index + 3))));
        }
        return new History(commits.build(), fields.size() / 5 > MAXIMUM_COMMITS);
    }

    public CodeHistory historyPage(Path checkout, String revision, Optional<String> path, int offset)
    {
        checkArgument(offset >= 0 && offset <= 1_000_000, "Invalid history offset");
        path.ifPresent(ReviewCodeService::checkPath);
        String output = require(checkout, ImmutableList.<String>builder()
                .add("log", "--no-show-signature", "--no-ext-diff", "--no-textconv", "--date-order", "--format=%H%x00%P%x00%an%x00%aI%x00%B%x00")
                .add("--skip=" + offset, "-n", "101").addAll(path.isPresent() ? ImmutableList.of("--follow") : ImmutableList.of())
                .add(revision, "--").addAll(path.stream().iterator()).build().toArray(String[]::new));
        List<CodeHistory.Commit> commits = historyCommits(output);
        return new CodeHistory(revision, commits.stream().limit(100).collect(ImmutableList.toImmutableList()),
                commits.size() > 100 ? OptionalInt.of(offset + 100) : OptionalInt.empty());
    }

    public CodeHistory.Comparison historyCommit(Path checkout, String revision, Optional<String> requestedParent)
    {
        CodeHistory.Commit commit = historyCommits(require(checkout, "show", "--no-patch", "--no-show-signature",
                "--format=%H%x00%P%x00%an%x00%aI%x00%B%x00", revision, "--")).getFirst();
        Optional<String> parent = requestedParent.or(() -> commit.parents().stream().findFirst());
        checkArgument(parent.isEmpty() || commit.parents().contains(parent.orElseThrow()), "The selected revision is not a parent of this commit");
        List<String> args = parent.isPresent()
                ? ImmutableList.of("diff", "--raw", "-z", "-M", "--no-ext-diff", "--no-textconv", parent.orElseThrow(), revision, "--")
                : ImmutableList.of("diff-tree", "--root", "--no-commit-id", "--raw", "-z", "-r", "-M", "--no-ext-diff", "--no-textconv", revision, "--");
        Result result = read(checkout, args.toArray(String[]::new));
        checkState(result.successful(), "Cannot read commit changes: %s", result.output().strip());
        List<String> entries = ReviewCodeService.entries(result.output(), result.truncated());
        ImmutableList.Builder<CodeHistory.Change> files = ImmutableList.builder();
        for (int index = 0; index + 1 < entries.size(); ) {
            String metadata = entries.get(index++);
            String status = metadata.substring(metadata.lastIndexOf(' ') + 1);
            String path = entries.get(index++);
            Optional<String> original = Optional.empty();
            if (status.startsWith("R") || status.startsWith("C")) {
                if (index == entries.size()) {
                    break;
                }
                original = Optional.of(path);
                path = entries.get(index++);
            }
            files.add(new CodeHistory.Change(path, original, status));
        }
        return new CodeHistory.Comparison(commit, parent, files.build(), result.truncated());
    }

    private static List<CodeHistory.Commit> historyCommits(String output)
    {
        List<String> fields = Splitter.on('\0').splitToList(output);
        ImmutableList.Builder<CodeHistory.Commit> commits = ImmutableList.builder();
        for (int index = 0; index + 4 < fields.size(); index += 5) {
            commits.add(new CodeHistory.Commit(fields.get(index).strip(), Splitter.on(' ').omitEmptyStrings().splitToList(fields.get(index + 1)),
                    fields.get(index + 2), Instant.parse(fields.get(index + 3)), fields.get(index + 4).strip()));
        }
        return commits.build();
    }

    public Diff diff(Path checkout, String base, Optional<String> head)
    {
        List<String> selection = ImmutableList.<String>builder().add(base).addAll(head.stream().iterator()).add("--").build();
        Result result = read(checkout, ImmutableList.<String>builder().add("diff").addAll(DIFF_OPTIONS)
                .add("--raw", "-z", "--patch", "--unified=3").addAll(selection).build().toArray(String[]::new));
        checkState(result.successful(), "Cannot read local diff: %s", result.output().strip());
        // One Git invocation owns the file list and its patches; a writer between separate reads could pair different files.
        int boundary = result.output().indexOf("\0\0");
        String raw = boundary < 0 ? result.output() : result.output().substring(0, boundary + 1);
        String patch = boundary < 0 ? "" : result.output().substring(boundary + 2);
        List<String> entries = ReviewCodeService.entries(raw, result.truncated() && boundary < 0);
        List<String> patches = Splitter.on("\ndiff --git ").splitToList(patch);
        ImmutableList.Builder<DiffFile> files = ImmutableList.builder();
        int patchIndex = 0;
        for (int index = 0; index + 1 < entries.size(); ) {
            String metadata = entries.get(index++);
            String status = metadata.substring(metadata.lastIndexOf(' ') + 1);
            String path = entries.get(index++);
            if (status.startsWith("R") || status.startsWith("C")) {
                if (index >= entries.size()) {
                    break;
                }
                path = entries.get(index++);
            }
            String filePatch = patchIndex < patches.size() ? patches.get(patchIndex) : "";
            if (patchIndex > 0 && !filePatch.isEmpty()) {
                filePatch = "diff --git " + filePatch;
            }
            if (result.truncated() && patchIndex == patches.size() - 1) {
                filePatch = "";
            }
            patchIndex++;
            files.add(file(path, status, filePatch));
        }
        return new Diff(files.build(), result.truncated());
    }

    public DiffFile untracked(Path checkout, String path)
    {
        return untracked(checkout, path, Optional.empty());
    }

    /** A staged deletion can leave an untracked replacement at the same path; compare that file with its original committed contents. */
    public DiffFile replacement(Path checkout, String base, String path)
    {
        return untracked(checkout, path, Optional.of(base));
    }

    private DiffFile untracked(Path checkout, String path, Optional<String> base)
    {
        ReviewCodeService.checkPath(path);
        byte[] after = ReviewCodeService.readCheckout(checkout, path);
        String code = base.isPresent() ? "M" : "A";
        if (after.length > ReviewCodeService.MAXIMUM_FILE_BYTES) {
            return file(path, code, "");
        }
        byte[] before = new byte[0];
        if (base.isPresent()) {
            Result original = read(checkout, "cat-file", "blob", base.orElseThrow() + ":" + path);
            checkState(original.successful(), "Cannot read original file: %s", path);
            before = original.output().getBytes(UTF_8);
            if (original.truncated() || before.length > ReviewCodeService.MAXIMUM_FILE_BYTES) {
                return file(path, code, "");
            }
        }
        // Copy the validated bytes so an agent replacing the path after validation cannot redirect or change this comparison.
        try {
            Path snapshot = Files.createTempDirectory("bytequay-local-diff-");
            Path oldFile = snapshot.resolve("before");
            Path newFile = snapshot.resolve("after");
            try {
                Files.write(oldFile, before);
                Files.write(newFile, after);
                List<String> arguments = ImmutableList.<String>builder().add("diff").addAll(DIFF_OPTIONS)
                        .add("--no-index", "--", oldFile.toString(), newFile.toString()).build();
                Result patch = git.runLocalBounded(snapshot, Duration.ofSeconds(30), GitCommandRunner.MAXIMUM_OUTPUT_BYTES, arguments);
                checkState(patch.exitCode() == 1 || patch.successful(), "Cannot read local file: %s", path);
                return file(path, code, patch.truncated() ? "" : patch.output());
            }
            finally {
                Files.deleteIfExists(oldFile);
                Files.deleteIfExists(newFile);
                Files.deleteIfExists(snapshot);
            }
        }
        catch (IOException failure) {
            throw new IllegalStateException("Cannot compare local file: " + path, failure);
        }
    }

    private static DiffFile file(String path, String code, String patch)
    {
        String status = switch (code.charAt(0)) {
            case 'A', 'C' -> "added";
            case 'D' -> "removed";
            case 'R' -> "renamed";
            default -> "modified";
        };
        int start = patch.indexOf("@@ ");
        if (start < 0) {
            return new DiffFile(path, status, 0, 0, null);
        }
        String hunks = patch.substring(start);
        int additions = (int) hunks.lines().filter(line -> line.startsWith("+")).count();
        int deletions = (int) hunks.lines().filter(line -> line.startsWith("-")).count();
        return new DiffFile(path, status, additions, deletions, hunks);
    }

    public record Diff(List<DiffFile> files, boolean truncated)
    {
        public Diff
        {
            files = ImmutableList.copyOf(files);
        }
    }

    public record History(List<DiffCommit> commits, boolean truncated)
    {
        public History
        {
            commits = ImmutableList.copyOf(commits);
        }
    }
}
