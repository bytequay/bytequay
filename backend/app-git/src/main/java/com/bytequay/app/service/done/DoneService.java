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
package com.bytequay.app.service.done;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.DoneActivity;
import com.bytequay.core.domain.DoneItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.bytequay.core.domain.GoalItem.numberedKey;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/**
 * What the viewer finished in a span of days on their wall clock: GitHub's merges, reviews and closed issues, then the tasks and work
 * items they finished here. A task or work item that delivered a pull request merged in the span is left to that merge.
 */
@Service
public class DoneService
{
    public static final int MAXIMUM_DAYS = 31;

    private static final Logger log = LoggerFactory.getLogger(DoneService.class);
    private static final Duration FRESH = Duration.ofMinutes(5);

    private final GitHubAccountClient accounts;
    private final CredentialService credentials;
    private final WorkspaceStore workspaces;
    private final AgentTaskStore tasks;
    private final WorkItemStore workItems;
    private final TaskPullRequestOwnership ownership;
    private final Clock clock;
    // ponytail: one entry per span read, never evicted; spans are days a person pages through, so it stays small.
    private final Map<Span, Read> reads = new ConcurrentHashMap<>();

    public DoneService(
            GitHubAccountClient accounts,
            CredentialService credentials,
            WorkspaceStore workspaces,
            AgentTaskStore tasks,
            WorkItemStore workItems,
            TaskPullRequestOwnership ownership,
            Clock clock)
    {
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** {@code from} and {@code to} are inclusive days on the user's wall clock. */
    public DoneActivity done(LocalDate from, LocalDate to)
    {
        checkArgument(!to.isBefore(from), "The span ends before it starts");
        checkArgument(from.plusDays(MAXIMUM_DAYS).isAfter(to), "The span is longer than %s days", MAXIMUM_DAYS);
        // The shared clock is UTC; the days follow the user's wall clock.
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime start = from.atStartOfDay(zone);
        ZonedDateTime next = to.plusDays(1).atStartOfDay(zone);
        DoneActivity github = github(start, next.minusSeconds(1));

        List<Workspace> watched = workspaces.findAll();
        Map<String, String> workspaceByRepo = watched.stream().filter(Workspace::isGitHub)
                .collect(toImmutableMap(workspace -> lower(workspace.monitoredRepository().fullName()), Workspace::id, (first, second) -> first));
        List<DoneItem> items = Lists.newArrayList(github.items().stream()
                .map(item -> item.inWorkspace(item.repo().map(DoneService::lower).map(workspaceByRepo::get)))
                .collect(toImmutableList()));
        Set<String> merged = github.items().stream()
                .filter(item -> item.kind() == DoneItem.Kind.MERGED || item.kind() == DoneItem.Kind.MERGED_BY_YOU)
                .map(item -> numberedKey(item.repo().orElseThrow(), item.number().orElseThrow()))
                .collect(toImmutableSet());
        for (Workspace workspace : watched) {
            for (AgentTask task : tasks.listByWorkspace(workspace.id())) {
                Optional<Instant> at = task.terminalAt().filter(time -> within(time, start, next));
                if (task.interactive() && task.state() == TaskState.COMPLETED && at.isPresent()
                        && ownership.linkedPullRequest(task).filter(pr -> merged.contains(numberedKey(pr.repo(), pr.number()))).isEmpty()) {
                    items.add(DoneItem.local(DoneItem.Kind.TASK_DONE, task.title(), workspace.id(), task.id().toString(), at.orElseThrow()));
                }
            }
            // ponytail: a work item's last change stands in for when it was done; its history has no state change to read.
            for (WorkItem item : workItems.list(workspace.id())) {
                if (item.state() == WorkItem.State.DONE && within(item.updatedAt(), start, next)
                        && item.links().stream().noneMatch(link -> link.reference().kind() == GoalItemKind.PULL_REQUEST && merged.contains(lower(link.reference().key())))) {
                    items.add(DoneItem.local(DoneItem.Kind.WORK_DONE, item.title(), workspace.id(), item.id(), item.updatedAt()));
                }
            }
        }
        items.sort(comparing(DoneItem::at).reversed());
        return new DoneActivity(items, github.complete());
    }

    /**
     * A span that ended before today never changes, so it is read once; one that holds today is read again after five minutes, since Home
     * asks every time it opens. Without GitHub the list keeps what was finished here, marked incomplete.
     */
    private DoneActivity github(ZonedDateTime start, ZonedDateTime end)
    {
        Instant now = clock.instant();
        GitHubCredential credential;
        try {
            credential = credentials.resolveGitHubCredential();
        }
        catch (RuntimeException failure) {
            return new DoneActivity(ImmutableList.of(), false);
        }
        Span span = new Span(lower(credential.profile().login()), start, end);
        Read cached = reads.get(span);
        if (cached != null && (end.toInstant().isBefore(cached.at()) || now.isBefore(cached.at().plus(FRESH)))) {
            return cached.activity();
        }
        try {
            DoneActivity activity = accounts.fetchDone(credential.token(), credential.profile().login(), start, end);
            if (activity.complete()) {
                reads.put(span, new Read(now, activity));
            }
            return activity;
        }
        catch (RuntimeException failure) {
            log.warn("Could not read finished work from GitHub for {}..{}: {}", start, end, failure.getMessage());
            return cached == null ? new DoneActivity(ImmutableList.of(), false) : cached.activity();
        }
    }

    private static boolean within(Instant at, ZonedDateTime start, ZonedDateTime next)
    {
        return !at.isBefore(start.toInstant()) && at.isBefore(next.toInstant());
    }

    private static String lower(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }

    private record Span(String login, ZonedDateTime start, ZonedDateTime end) {}

    private record Read(Instant at, DoneActivity activity) {}
}
