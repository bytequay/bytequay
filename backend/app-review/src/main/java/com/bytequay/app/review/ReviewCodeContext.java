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

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Reads bounded frozen code; the calling review tool owns authorization and evidence persistence. */
final class ReviewCodeContext
{
    private static final int SOURCE_WINDOW = 200;
    private static final int MAX_EXCERPT = 4000;
    private static final int DIFF_PAGE_LINES = 300;

    private final GitCommandRunner git;
    private final WorkspaceRepositoryService workspaces;
    private final ObjectMapper objectMapper;

    ReviewCodeContext(GitCommandRunner git, WorkspaceRepositoryService workspaces, ObjectMapper objectMapper)
    {
        this.git = requireNonNull(git, "git is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    ObjectNode diff(ReviewRun run, Optional<String> path, int page)
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(run.diff());
        if (path.isEmpty()) {
            ObjectNode node = objectMapper.createObjectNode().put("anchors", anchors.size()).put("note", "call again with a path to read its hunks with anchor ids");
            node.putPOJO("files", run.diff().stream().map(file -> ImmutableMap.of(
                    "path", file.filename(), "status", file.status(), "additions", file.additions(), "deletions", file.deletions(),
                    "hunks", anchors.hunks(file.filename()).size(), "textual", file.patch() != null)).collect(toImmutableList()));
            return node;
        }
        DiffFile file = run.diff().stream().filter(candidate -> candidate.filename().equals(path.orElseThrow())).findFirst()
                .orElseThrow(() -> new ReviewInvalidRequestException("path: " + path.orElseThrow() + " is not in this pull request's diff"));
        List<String> lines = new ArrayList<>();
        if (file.patch() == null) {
            lines.add("(binary file or diff unavailable; no inline anchors)");
        }
        for (ReviewDiffAnchors.Hunk hunk : anchors.hunks(file.filename())) {
            lines.add(hunk.header());
            hunk.lines().forEach(line -> lines.add(line.id() + " " + line.kind() + (line.side().equals("LEFT") ? "L" : "R") + line.line() + " " + line.text()));
        }
        int pages = (int) Math.max(1, (lines.size() + (long) DIFF_PAGE_LINES - 1) / DIFF_PAGE_LINES);
        int from = (int) Math.min(lines.size(), (long) (page - 1) * DIFF_PAGE_LINES);
        int to = (int) Math.min(lines.size(), (long) from + DIFF_PAGE_LINES);
        return objectMapper.createObjectNode()
                .put("path", file.filename())
                .put("status", file.status())
                .put("page", page)
                .put("pages", pages)
                .put("format", "anchorId kind side+line text; cite anchors as startAnchor/endAnchor in review_save_finding")
                .put("hunks", String.join("\n", lines.subList(from, to)));
    }

    Source source(String workspaceId, ReviewRun run, String path, String revision, int startLine, Optional<Integer> endLine)
    {
        if (path.startsWith("/") || path.contains("..") || path.isBlank()) {
            throw new ReviewInvalidRequestException("path: use a repository-relative path");
        }
        int maximumEnd = (int) Math.min(Integer.MAX_VALUE, (long) startLine + SOURCE_WINDOW - 1);
        int end = endLine.orElse(maximumEnd);
        if (end < startLine) {
            throw new ReviewInvalidRequestException("endLine: must not precede startLine");
        }
        end = Math.min(end, maximumEnd);
        String sha = switch (revision) {
            case "", "head" -> run.sourceRevision();
            case "base" -> run.mergeBaseSha();
            case "previous" -> run.priorHeadSha().orElseThrow(() -> new ReviewInvalidRequestException("revision: no previously reviewed head"));
            default -> throw new ReviewInvalidRequestException("revision: use head, base (merge base), or previous");
        };
        GitCommandRunner.Result shown = git.run(workspaces.requireReadyRepository(workspaceId).repository(), "show", sha + ":" + path);
        if (!shown.successful()) {
            throw new ReviewInvalidRequestException("path: " + path + " does not exist at the selected frozen revision");
        }
        if (shown.truncated()) {
            throw new ReviewInvalidRequestException("path: the frozen source exceeds the Git output limit; no source evidence was retained");
        }
        List<String> all = shown.output().lines().toList();
        if (startLine > all.size()) {
            throw new ReviewInvalidRequestException("startLine: the file has " + all.size() + " lines");
        }
        end = Math.min(end, all.size());
        StringBuilder window = new StringBuilder();
        for (int index = startLine - 1; index < end; index++) {
            window.append(index + 1).append("  ").append(all.get(index)).append('\n');
        }
        return new Source(sha, end, all.size(), Ascii.truncate(window.toString(), MAX_EXCERPT + 14, "\n… (truncated)"));
    }

    record Source(String commit, int endLine, int totalLines, String excerpt) {}
}
