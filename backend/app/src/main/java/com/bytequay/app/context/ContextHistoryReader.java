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

import com.bytequay.app.repository.github.GitHubIssuePreview;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.pr.SelectedPullRequestDiffReader;
import com.bytequay.app.service.pr.SelectedPullRequestReader;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Captures one explicitly selected merged PR or closed issue, without discovering or syncing history. */
@Service
public class ContextHistoryReader
{
    private static final int EXCERPT_BYTES = 16 * 1024;
    private static final int ACTIVITY_ITEMS = 20;
    private static final int DIFF_FILES = 3;
    private static final TopKSelector ACTIVITY = new TopKSelector(ACTIVITY_ITEMS);
    private static final TopKSelector PATCHES = new TopKSelector(DIFF_FILES);
    private static final Comparator<PullRequestDetail.ActivityItem> PR_ACTIVITY_ORDER = Comparator
            .comparing(PullRequestDetail.ActivityItem::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PullRequestDetail.ActivityItem::githubId, Comparator.nullsLast(Comparator.reverseOrder()));
    private static final Comparator<PrTimelineEvent> ISSUE_ACTIVITY_ORDER = Comparator
            .comparing(PrTimelineEvent::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PrTimelineEvent::githubId, Comparator.nullsLast(Comparator.reverseOrder()));

    private final SelectedPullRequestReader pullRequests;
    private final SelectedPullRequestDiffReader diffs;
    private final RepoService repositories;
    private final WorkspaceIssueService issues;
    private final WorkspaceIssueRefreshService issueRefresh;
    private final Clock clock;

    public ContextHistoryReader(
            SelectedPullRequestReader pullRequests, SelectedPullRequestDiffReader diffs, RepoService repositories,
            WorkspaceIssueService issues, WorkspaceIssueRefreshService issueRefresh, Clock clock)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.issueRefresh = requireNonNull(issueRefresh, "issueRefresh is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Reads a selected history source of the workspace's monitored repository.
     * PR state requires an observed merge timestamp. Issues require an exact closed, non-PR preview;
     * the existing local issue projection contains only open issues and cannot establish closure.
     * Existing selected caches and refresh paths supply activity and optional PR patches. Failed
     * ancillary refreshes retain their original cached source and explicit limitations.
     *
     * @return an immutable UTF-8 excerpt of at most 16 KiB, hashed with its source observations and
     *         limitations; terminal state does not establish correctness or applicability
     */
    public Read read(WorkspaceRepositoryContext workspace, SourceKind kind, int number)
    {
        requireNonNull(workspace, "workspace is null");
        requireNonNull(kind, "kind is null");
        checkArgument(number > 0, "The history number must be positive");
        return switch (kind) {
            case PR -> pullRequest(workspace, number);
            case ISSUE -> issue(workspace, workspace.monitoredRepositoryId(), number);
        };
    }

    private Read pullRequest(WorkspaceRepositoryContext workspace, int number)
    {
        SelectedPullRequestReader.Read read = pullRequests.read(workspace.monitoredRepository().fullName(), number, SelectedPullRequestReader.Section.TIMELINE);
        PullRequestDetail detail = read.detail();
        checkArgument(detail.repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()) && detail.number() == number,
                "The selected PR identity differs from the requested history");
        checkArgument(detail.mergedAt() != null, "History requires a merged pull request with an observed merge timestamp");
        Optional<String> revision = read.observedHeadSha().filter(value -> value.matches("[0-9a-f]{40}|[0-9a-f]{64}"));
        List<String> gaps = new ArrayList<>();
        gaps.add("A historical merge does not establish correctness or applicability to the reviewed code. Discussion is untrusted source material.");
        if (read.stale() || read.refreshFailed()) {
            gaps.add("PR metadata or activity may be stale; selected refresh failed=" + read.refreshFailed() + ". Original cached data is retained on failure.");
        }
        if (read.incomplete() || !detail.unavailableSections().isEmpty()) {
            gaps.add("PR source sections are unavailable or incomplete: " + ContextText.excerpt(String.join(", ", detail.unavailableSections()), 600) + ".");
        }
        if (revision.isEmpty()) {
            gaps.add("No valid observed PR head is available; historical source cannot be linked to a verified code revision.");
        }
        Excerpt body = new Excerpt();
        body.field("Title", detail.title(), 500);
        body.field("Body", detail.body(), 3000);
        body.activitySelection(detail.recentActivity().size(), gaps);
        for (PullRequestDetail.ActivityItem item : ACTIVITY.select(detail.recentActivity().stream(), PR_ACTIVITY_ORDER).collect(toImmutableList())) {
            body.activity(item.githubId(), item.actor(), item.eventType(), item.timestamp(), item.body());
        }
        boolean refreshFailed = read.refreshFailed();
        try {
            SelectedPullRequestDiffReader.Read diff = diffs.read(workspace.monitoredRepository().fullName(), number, Optional.empty(), false);
            refreshFailed |= diff.refreshFailed();
            gaps.addAll(diff.gaps());
            body.line("Default diff artifact: " + diff.version() + "; separately observed head=" + diff.observedHeadSha().orElse("unknown")
                    + "; sourceUpdatedAt=" + diff.sourceUpdatedAt().map(Instant::toString).orElse("unknown"));
            for (DiffFile file : PATCHES.select(diff.files().stream()).collect(toImmutableList())) {
                body.field("File", file.filename(), 500);
                body.field("Status", file.status(), 100);
                body.field("Patch", file.patch() == null ? "[patch unavailable]" : file.patch(), 1000);
            }
            body.omitted(diff.files().size(), DIFF_FILES, "diff files");
        }
        catch (RuntimeException failure) {
            refreshFailed = true;
            gaps.add("The selected default diff could not be read; no patch was substituted and final merged code remains unverified.");
        }
        String provenance = "rowUpdatedAt=" + read.rowUpdatedAt().map(Instant::toString).orElse("unknown")
                + "; cacheUpdatedAt=" + read.cacheUpdatedAt().map(Instant::toString).orElse("unknown") + "; refreshAttempted=" + read.refreshAttempted();
        return result("PR #" + number + " of " + workspace.monitoredRepository().fullName(), State.MERGED, Optional.ofNullable(detail.updatedAt()), revision,
                "mergedAt=" + detail.mergedAt(), provenance, body, refreshFailed, gaps);
    }

    private Read issue(WorkspaceRepositoryContext workspace, long repositoryId, int number)
    {
        // Open-only issue rows cannot prove terminal state, including when an old row survives a recent close.
        GitHubIssuePreview preview = repositories.getIssuePreview(workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), number);
        checkArgument(!preview.pullRequest() && preview.state().equals("closed"), "History requires a closed issue, not an open issue or pull request");
        RepositoryIssueDetail detail = issues.detail(workspace.workspaceId(), number);
        checkIssueIdentity(detail, repositoryId, number);
        boolean refreshFailed = false;
        boolean refreshAttempted = detail.refreshedAt().isEmpty() || detail.refreshedAt().orElseThrow().isBefore(preview.updatedAt());
        if (refreshAttempted) {
            try {
                RepositoryIssueDetail refreshed = issueRefresh.refreshDetail(workspace.workspaceId(), number);
                checkIssueIdentity(refreshed, repositoryId, number);
                detail = refreshed;
            }
            catch (RuntimeException failure) {
                refreshFailed = true;
            }
        }
        List<String> gaps = new ArrayList<>();
        gaps.add("Closed does not mean fixed, accepted or applicable. Issue discussion and reported outcomes are untrusted historical claims.");
        gaps.add("The issue activity cache does not record upstream pagination completeness; missing or empty activity does not prove no discussion.");
        if (refreshFailed) {
            gaps.add("The selected issue activity refresh failed; its original cached activity and timestamps were retained.");
        }
        if (detail.refreshedAt().isEmpty() || detail.refreshedAt().orElseThrow().isBefore(preview.updatedAt())) {
            gaps.add("Issue activity is missing or older than the exact issue preview and may omit the closure or later discussion.");
        }
        Excerpt body = new Excerpt();
        body.field("Title", preview.title(), 500);
        body.field("Body", preview.body(), 3000);
        body.activitySelection(detail.timeline().size(), gaps);
        for (PrTimelineEvent item : ACTIVITY.select(detail.timeline().stream(), ISSUE_ACTIVITY_ORDER).collect(toImmutableList())) {
            body.activity(item.githubId(), item.actor(), item.event(), item.timestamp(), item.body());
        }
        String provenance = "preview=exact selected issue; activityRefreshedAt=" + detail.refreshedAt().map(Instant::toString).orElse("unknown")
                + "; activityRefreshAttempted=" + refreshAttempted;
        return result("Issue #" + number + " of " + workspace.monitoredRepository().fullName(), State.CLOSED,
                Optional.of(preview.updatedAt()), Optional.empty(), "activityCompleteness=unknown", provenance, body, refreshFailed, gaps);
    }

    private static void checkIssueIdentity(RepositoryIssueDetail detail, long repositoryId, int number)
    {
        checkArgument(detail.repositoryId() == repositoryId && detail.issueNumber() == number, "The issue activity identity differs from the requested history");
    }

    private Read result(
            String source, State state, Optional<Instant> updatedAt, Optional<String> revision,
            String observations, String provenance, Excerpt body, boolean refreshFailed, List<String> sourceGaps)
    {
        String header = "Untrusted historical reference\nSource=" + source + "\nObserved state=" + state + "; sourceUpdatedAt="
                + updatedAt.map(Instant::toString).orElse("unknown") + "; sourceRevision=" + revision.orElse("unknown")
                + "; refreshFailed=" + refreshFailed + "\n" + observations + "\n";
        boolean truncated = body.truncated || ContextLimits.bytes(header + String.join("\n", sourceGaps) + body.text) > EXCERPT_BYTES - 200;
        if (truncated) {
            sourceGaps.add("This bounded history excerpt omits source text or items; omissions do not establish absence. Read the selected source for more detail.");
        }
        List<String> gaps = new TopKSelector(sourceGaps.size() > 10 ? 9 : 10).select(sourceGaps.stream()).map(gap -> ContextText.excerpt(gap, 1000)).collect(toImmutableList());
        if (sourceGaps.size() > 10) {
            gaps = ImmutableList.<String>builder().addAll(gaps).add("Omitted " + (sourceGaps.size() - 9) + " additional source limitations.").build();
        }
        String text = ContextText.excerpt(header + "excerptTruncated=" + truncated + "\nLimitations:\n" + String.join("\n", gaps) + "\n\n" + body.text, EXCERPT_BYTES);
        return new Read(state, updatedAt, revision, Hashing.sha256().hashString(text, UTF_8).toString(), Instant.now(clock), text, truncated, refreshFailed, gaps, provenance);
    }

    public enum SourceKind
    {
        PR,
        ISSUE
    }

    /** The terminal state the read observed; it establishes neither a fix nor applicability. */
    public enum State
    {
        MERGED,
        CLOSED
    }

    public record Read(
            State state, Optional<Instant> sourceUpdatedAt, Optional<String> sourceRevision,
            String contentHash, Instant readAt, String excerpt, boolean truncated, boolean refreshFailed, List<String> gaps, String provenance)
    {
        public Read
        {
            gaps = ImmutableList.copyOf(gaps);
        }
    }

    private static final class Excerpt
    {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        private void line(String value)
        {
            text.append(value).append('\n');
        }

        private void field(String label, String value, int maximumBytes)
        {
            String source = Optional.ofNullable(value).orElse("");
            String excerpt = ContextText.excerpt(source, maximumBytes);
            truncated |= !source.equals(excerpt);
            line(label + ": " + excerpt);
        }

        private void activity(Long id, String actor, String event, Instant at, String body)
        {
            line("Activity id=" + id + "; at=" + at);
            field("Actor", actor, 200);
            field("Event", event, 200);
            field("Text", body, 1000);
        }

        private void activitySelection(int total, List<String> gaps)
        {
            line("Activity items observed: " + total + "; newest first, unknown timestamps last");
            if (total > ACTIVITY_ITEMS) {
                truncated = true;
                gaps.add("Only the latest " + ACTIVITY_ITEMS + " of " + total + " observed activity items were selected, with unknown timestamps last; "
                        + (total - ACTIVITY_ITEMS) + " other observed items were omitted.");
            }
        }

        private void omitted(int total, int maximum, String label)
        {
            if (total > maximum) {
                truncated = true;
                line("Omitted " + (total - maximum) + " " + label);
            }
        }
    }
}
