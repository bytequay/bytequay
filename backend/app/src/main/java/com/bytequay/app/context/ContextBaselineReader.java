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
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Reads bounded regular-file evidence from the monitored baseline already frozen with a review. */
@Service
public class ContextBaselineReader
{
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final int INVENTORY_BYTES = 8 * 1024;
    private static final int BLOB_BYTES = 128 * 1024;
    private static final int EXCERPT_BYTES = 16 * 1024;
    private static final int SOURCE_LINES = 200;

    private final GitCommandRunner git;
    private final Clock clock;

    public ContextBaselineReader(GitCommandRunner git, Clock clock)
    {
        this.git = requireNonNull(git, "git is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Captures an exact source window from a regular blob at the run's immutable monitored baseline.
     *
     * @param workspace trusted local repository routing, resolved by the application
     * @param run owning context-enabled run; its captured baseline is the only accepted revision
     * @param path literal repository-relative file path
     * @param startLine first one-based line; an empty file accepts only line one
     * @param endLine optional inclusive last line, capped to 200 lines from the start
     * @return backend-observed source identity and a line-numbered UTF-8 excerpt of at most 16 KiB
     * @throws IllegalArgumentException for unavailable context, mismatched repository identity, invalid ranges,
     *         non-regular files, incomplete Git output or unsupported binary/non-UTF-8 content
     *
     * No worktree/index content, replacement object, lazy fetch or model-provided revision participates.
     * A complete blob must fit within 128 KiB before line counts or excerpts are reported.
     */
    public Read read(WorkspaceRepositoryContext workspace, ReviewRun run, String path, int startLine, Optional<Integer> endLine)
    {
        requireNonNull(workspace, "workspace is null");
        requireNonNull(run, "run is null");
        requireNonNull(endLine, "endLine is null");
        ContextLimits.text(path, "path", 1000);
        ContextLimits.pathPrefix(path + "/");
        checkArgument(startLine > 0, "The starting line must be positive");
        checkArgument(endLine.isEmpty() || endLine.orElseThrow() >= startLine, "The ending line must not precede the starting line");
        ReviewContextSnapshot snapshot = run.context().filter(ReviewContextSnapshot::enabled)
                .orElseThrow(() -> new IllegalArgumentException("Repository context was not enabled for this run"));
        checkArgument(snapshot.repositoryId() == RepositoryContextReader.repositoryId(workspace), "The monitored repository differs from the frozen context");
        String baseline = snapshot.baselineSha().orElseThrow(() -> new IllegalArgumentException("The frozen monitored baseline is unavailable"));
        GitCommandRunner.Result inventory = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, INVENTORY_BYTES,
                ImmutableList.of("--literal-pathspecs", "ls-tree", "-z", "--full-tree", baseline, "--", path));
        checkArgument(inventory.successful() && !inventory.truncated() && ContextLimits.bytes(inventory.output()) <= INVENTORY_BYTES,
                "The baseline file identity could not be read completely");
        String blob = regularBlob(inventory.output(), path);
        GitCommandRunner.Result content = git.runLocalBounded(workspace.repository(), COMMAND_TIMEOUT, BLOB_BYTES, ImmutableList.of("cat-file", "blob", blob));
        checkArgument(content.successful() && !content.truncated() && ContextLimits.bytes(content.output()) <= BLOB_BYTES,
                "The baseline file could not be read completely within the 128 KiB limit");
        checkArgument(content.output().indexOf('\0') < 0 && content.output().indexOf('\uFFFD') < 0, "The baseline file is not supported UTF-8 text");
        List<String> lines = content.output().lines().collect(ImmutableList.toImmutableList());
        checkArgument(startLine <= lines.size() || (lines.isEmpty() && startLine == 1), "The starting line is outside the baseline file");
        int end = (int) Math.min(lines.size(), Math.min((long) startLine + SOURCE_LINES - 1, endLine.map(Integer::longValue).orElse(Long.MAX_VALUE)));
        StringBuilder window = new StringBuilder();
        for (int line = startLine; line <= end; line++) {
            window.append(line).append("  ").append(lines.get(line - 1)).append('\n');
        }
        String excerpt = ContextText.prefix(window.toString(), EXCERPT_BYTES);
        return new Read(new ContextReference.RepositoryFile(snapshot.repositoryId(), workspace.monitoredRepository(), path, baseline), blob,
                Instant.now(clock), startLine, end, lines.size(), excerpt, excerpt.length() < window.length());
    }

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

    public record Read(ContextReference.RepositoryFile reference, String blobId, Instant readAt, int startLine, int endLine, int totalLines, String excerpt, boolean truncated) {}
}
