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
package com.bytequay.app.service.recent;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RecentTouch;
import com.bytequay.core.domain.RecordRecentTouch;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RecentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static com.bytequay.app.service.recent.RecentService.DWELL_THRESHOLD;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Writes the "I worked on this" record. Every entry point swallows its own
 * failures: Recent is a convenience, and losing a row is never worth failing
 * the merge or the comment that produced it.
 */
@Component
public class RecentTouchRecorder
{
    private static final Logger log = LoggerFactory.getLogger(RecentTouchRecorder.class);
    /** Mirrors the stored column's length; bodies are trimmed to it before they are saved. */
    private static final int SNIPPET_LIMIT = 4000;

    private final RecentStore recent;
    private final PullRequestStore pullRequests;
    private final PrDetailStore details;
    private final RepositoryIssueStore issues;
    private final WorkspaceStore workspaces;
    private final CredentialService credentials;
    private final Clock clock;

    public RecentTouchRecorder(
            RecentStore recent,
            PullRequestStore pullRequests,
            PrDetailStore details,
            RepositoryIssueStore issues,
            WorkspaceStore workspaces,
            CredentialService credentials,
            Clock clock)
    {
        this.recent = requireNonNull(recent, "recent is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Records an explicit pull-request action. No dwell, no threshold: doing it is the signal. */
    public void pullRequestAction(String repo, int number, RecentReason reason, int reasonCount)
    {
        guard(() -> pullRequestItem(repo, number, null).ifPresent(item -> save(item, reason, reasonCount)));
    }

    /** Records an explicit issue action. */
    public void issueAction(String workspaceId, int number, RecentReason reason)
    {
        guard(() -> issueItem(workspaceId, number).ifPresent(item -> save(item, reason, 0)));
    }

    /** Records a task the user prompted. Tasks carry no repository number of their own. */
    public void taskPrompted(String taskId, String prompt, String repo, String lane)
    {
        guard(() -> {
            String itemKey = RecentItemType.TASK.prefix() + ":" + taskId;
            Item item = new Item(
                    RecentItemType.TASK, itemKey, null, firstLine(prompt), repo, null, null, true, null, lane);
            save(item, RecentReason.PROMPTED, 0);
            // The prompt is the user's own words, so it belongs in Words mode
            // beside their comments. Keyed per turn, not per task.
            write(item, RecentTextKind.TASK_PROMPT, itemKey + ":prompt:" + clock.millis(), prompt, clock.instant());
        });
    }

    /** Records a commit the user made in a workspace checkout. */
    public void committed(String workspaceId, String summary, String sha)
    {
        guard(() -> {
            String repo = workspaces.findById(workspaceId)
                    .map(workspace -> workspace.monitoredRepository().fullName())
                    .orElse(null);
            String itemKey = RecentItemType.COMMIT.prefix() + ":" + repo + "@" + sha;
            Item item = new Item(
                    RecentItemType.COMMIT,
                    itemKey,
                    null,
                    summary,
                    repo,
                    workspaceId,
                    null,
                    true,
                    null,
                    sha.length() > 7 ? sha.substring(0, 7) : sha);
            save(item, RecentReason.PUSHED, 0);
        });
    }

    /**
     * Records what the renderer saw. The dwell threshold is enforced here rather
     * than in the renderer so there is one number to tune.
     */
    public void viewerTouch(ViewerTouch touch)
    {
        requireNonNull(touch, "touch is null");
        checkArgument(touch.number() > 0, "number must be positive");
        if (touch.signal() == ViewerTouch.Signal.DWELL
                && touch.dwellSeconds() < DWELL_THRESHOLD.toSeconds()) {
            return;
        }
        guard(() -> {
            Optional<Item> item = switch (touch.type()) {
                case PULL_REQUEST -> pullRequestItem(touch.repo(), touch.number(), touch.workspaceId());
                case ISSUE -> issueItem(touch.workspaceId(), touch.number());
                case TASK, COMMIT -> Optional.empty();
            };
            // Opening a diff is intent, and it is worth more than the minute
            // count that a glance happens to have accumulated.
            int minutes = touch.signal() == ViewerTouch.Signal.DIFF_OPENED
                    ? 0
                    : touch.dwellSeconds() / 60;
            item.ifPresent(resolved -> save(resolved, RecentReason.READ, minutes));
        });
    }

    /** Indexes something the user wrote on a pull request. */
    public void wroteOnPullRequest(String repo, int number, RecentTextKind kind, String suffix, String body)
    {
        guard(() -> pullRequestItem(repo, number, null)
                .ifPresent(item -> write(item, kind, item.itemKey() + ":" + suffix, body, clock.instant())));
    }

    /**
     * Keeps the composer's unsent text searchable. An emptied or posted draft
     * is removed rather than left behind as a stale "Never posted" hit.
     */
    public void draft(RecentItemType type, String repo, int number, String workspaceId, String body)
    {
        guard(() -> {
            Optional<Item> item = switch (type) {
                case PULL_REQUEST -> pullRequestItem(repo, number, workspaceId);
                case ISSUE -> issueItem(workspaceId, number);
                case TASK, COMMIT -> Optional.empty();
            };
            item.ifPresent(resolved -> {
                String id = resolved.itemKey() + ":draft";
                if (body == null || body.isBlank()) {
                    recent.deleteText(id);
                    return;
                }
                write(resolved, RecentTextKind.DRAFT, id, body, clock.instant());
            });
        });
    }

    /** Indexes something the user wrote on an issue. */
    public void wroteOnIssue(String workspaceId, int number, RecentTextKind kind, String suffix, String body)
    {
        guard(() -> issueItem(workspaceId, number)
                .ifPresent(item -> write(item, kind, item.itemKey() + ":" + suffix, body, clock.instant())));
    }

    private void save(Item item, RecentReason reason, int reasonCount)
    {
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, clock.getZone());
        recent.recordTouch(new RecentTouch(
                item.itemKey() + "@" + day,
                item.type(),
                item.itemKey(),
                day,
                now,
                reason,
                reasonCount,
                item.number(),
                item.title(),
                item.repo(),
                item.workspaceId(),
                item.htmlUrl(),
                item.mine(),
                item.prId(),
                item.subtitle()));
    }

    private void write(Item item, RecentTextKind kind, String id, String body, Instant writtenAt)
    {
        if (body == null || body.isBlank()) {
            return;
        }
        recent.saveText(new RecentText(
                id,
                item.type(),
                item.itemKey(),
                kind,
                body.length() > SNIPPET_LIMIT ? body.substring(0, SNIPPET_LIMIT) : body,
                writtenAt,
                item.number(),
                item.repo(),
                item.title(),
                item.htmlUrl()));
    }

    private Optional<Item> pullRequestItem(String repo, int number, String workspaceId)
    {
        Optional<Long> prId = pullRequests.findIdByRepoAndNumber(repo, number);
        Optional<StoredPrDetail> detail = prId.flatMap(details::find);
        String title = detail.map(stored -> stored.raw().title()).filter(value -> value != null && !value.isBlank())
                .orElse(repo + " #" + number);
        String htmlUrl = detail.map(stored -> stored.raw().htmlUrl())
                .orElseGet(() -> "https://github.com/" + repo + "/pull/" + number);
        boolean mine = detail.map(stored -> stored.raw().author())
                .flatMap(author -> credentials.cachedLogin().map(author::equalsIgnoreCase))
                .orElse(false);
        String author = detail.map(stored -> stored.raw().author()).orElse(null);
        return Optional.of(new Item(
                RecentItemType.PULL_REQUEST,
                RecordRecentTouch.itemKey(RecentItemType.PULL_REQUEST, repo, number),
                number,
                title,
                repo,
                workspaceId,
                htmlUrl,
                mine,
                prId.orElse(null),
                author));
    }

    private Optional<Item> issueItem(String workspaceId, int number)
    {
        if (workspaceId == null) {
            return Optional.empty();
        }
        Optional<Workspace> workspace = workspaces.findById(workspaceId);
        Optional<RepositoryIssue> issue = workspace
                .flatMap(found -> issues.find(found.monitoredRepositoryId(), number));
        if (workspace.isEmpty()) {
            return Optional.empty();
        }
        String repo = workspace.orElseThrow().monitoredRepository().fullName();
        boolean mine = issue.map(RepositoryIssue::author)
                .flatMap(author -> credentials.cachedLogin().map(author::equalsIgnoreCase))
                .orElse(false);
        return Optional.of(new Item(
                RecentItemType.ISSUE,
                RecordRecentTouch.itemKey(RecentItemType.ISSUE, repo, number),
                number,
                issue.map(RepositoryIssue::title).orElseGet(() -> repo + " #" + number),
                repo,
                workspaceId,
                issue.map(RepositoryIssue::htmlUrl)
                        .orElseGet(() -> "https://github.com/" + repo + "/issues/" + number),
                mine,
                null,
                issue.map(RepositoryIssue::author).orElse(null)));
    }

    private static String firstLine(String prompt)
    {
        String trimmed = prompt == null ? "" : prompt.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.isBlank() ? "Task" : line;
    }

    private static void guard(Runnable work)
    {
        try {
            work.run();
        }
        catch (RuntimeException failure) {
            log.debug("Recent touch not recorded: {}", failure.toString());
        }
    }

    /** One item resolved from local storage, ready to be stamped with a reason. */
    private record Item(
            RecentItemType type,
            String itemKey,
            Integer number,
            String title,
            String repo,
            String workspaceId,
            String htmlUrl,
            boolean mine,
            Long prId,
            String subtitle) {}
}
