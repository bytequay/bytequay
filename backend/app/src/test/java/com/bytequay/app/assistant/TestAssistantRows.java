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
package com.bytequay.app.assistant;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.agenda.AgendaReader;
import com.bytequay.app.agent.RunningTask;
import com.bytequay.app.agent.RunningTaskReader;
import com.bytequay.app.agent.TaskActivity;
import com.bytequay.app.agent.TaskActivityReader;
import com.bytequay.app.agent.TaskMessage;
import com.bytequay.app.assistant.AssistantRow.Kind;
import com.bytequay.app.assistant.AssistantRow.Latest;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraActivity;
import com.bytequay.integration.jira.JiraActivityReader;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAssistantRows
{
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final AgentTask WAITING = task("Expose snapshot id in metadata", "claude", NOW.minusSeconds(7200));
    private static final AgentTask IDLE = task("Add metadata cache", "codex", NOW.minusSeconds(3600));
    private static final AgentTask SETTLED = task("Retry Iceberg commit on conflict", "claude", NOW.minusSeconds(60));
    private static final AgentTask ENDED = task("Drop the old cache key", "claude", NOW.minusSeconds(7200))
            .fail("Stopped by the user", NOW.minusSeconds(30), NOW.plusSeconds(86400));
    private static final Workspace WORKSPACE = new Workspace("ws1", "Repository", 1, RepoRef.of("owner", "repository"), Optional.empty(), Optional.empty(), 1,
            RepoRef.of("owner", "repository"),
            Workspace.Topology.DIRECT, "master", "https://github.com/owner/repository.git", Optional.empty(), Optional.empty(), "/managed/repository", Optional.empty(),
            Optional.empty(), 0, NOW, NOW);

    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final JiraService jira = mock(JiraService.class);
    private final JiraActivityReader jiraActivity = mock(JiraActivityReader.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final RunningTaskReader running = mock(RunningTaskReader.class);
    private final TaskActivityReader activities = mock(TaskActivityReader.class);
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    private final EpicStore epics = mock(EpicStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgendaReader agenda = mock(AgendaReader.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AssistantRows rows = new AssistantRows(pullRequests, details, issues, jira, jiraActivity, tasks, reviews, running, activities, workItems, epics, workspaces,
            agenda, models, credentials);

    @BeforeEach
    void storesHoldOneOfEach()
    {
        when(workspaces.findAll()).thenReturn(ImmutableList.of(WORKSPACE));
        when(workspaces.findById("ws1")).thenReturn(Optional.of(WORKSPACE));
        when(models.get()).thenReturn(new ModelSettingsService.ModelSettings(ImmutableList.of(), ImmutableList.of(provider("claude", "Claude Code"), provider("codex", "Codex")),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));

        when(pullRequests.findIdByRepoAndNumber("owner/repository", 31402)).thenReturn(Optional.of(7L));
        when(pullRequests.findById(7L)).thenReturn(Optional.of(pullRequest("clean")));
        PrRawDetail raw = mock(PrRawDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(raw.headSha()).thenReturn(HEAD);
        // A reviewer's later comment leaves their approval standing; GitHub counts verdicts only.
        when(details.find(7L)).thenReturn(Optional.of(new StoredPrDetail(raw, ImmutableList.of(review("alba-t", "APPROVED", 600), review("bo-t", "CHANGES_REQUESTED", 300),
                review("alba-t", "COMMENTED", 120)), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false)));
        when(details.findLatestActivities(ImmutableSet.of(7L))).thenReturn(ImmutableMap.of(7L, new PrActivitySummary("reviewed", Optional.of("bo-t"), NOW.minusSeconds(300),
                Optional.of("CHANGES_REQUESTED"), Optional.empty(), Optional.empty(), Optional.of("Please split the retry into its own commit"), Optional.empty(),
                OptionalInt.empty())));

        when(issues.find(1, 77)).thenReturn(Optional.of(new RepositoryIssue(901, 77, "Snapshot ids in $snapshots", "body", "marlo-t", "", "https://github.com/owner/repository/issues/77",
                ImmutableList.of(), ImmutableList.of(), 0, NOW.minusSeconds(86400), NOW.minusSeconds(900))));

        JiraIssue ticket = new JiraIssue("connection", "cloud", "10042", "ICE-7", "https://company.atlassian.net/browse/ICE-7", "Expire snapshots by branch", "As described",
                "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                NOW.minusSeconds(1800), Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(),
                Optional.of(new JiraDtos.WorkspaceLink("ws1", "Repository")));
        when(jira.cachedIssue("cloud", "10042")).thenReturn(Optional.of(ticket));
        when(jiraActivity.latest(ticket)).thenReturn(Optional.of(new JiraActivity(JiraActivity.Kind.STATUS_CHANGED, NOW.minusSeconds(1800),
                Optional.of(new JiraDtos.Person("a1", "Marlo T", Optional.empty())), Optional.of(new JiraDtos.Status("1", "To do", "new")),
                Optional.of(new JiraDtos.Status("3", "In progress", "indeterminate")), Optional.empty(), ImmutableList.of())));

        when(tasks.find(WAITING.id())).thenReturn(Optional.of(WAITING));
        when(tasks.find(IDLE.id())).thenReturn(Optional.of(IDLE));
        when(tasks.find(SETTLED.id())).thenReturn(Optional.of(SETTLED));
        when(tasks.find(ENDED.id())).thenReturn(Optional.of(ENDED));
        // The sidebar still lists a turn that completed a minute ago; the task is open and waits for the user.
        when(running.current()).thenReturn(ImmutableList.of(new RunningTask(WAITING.id().toString(), "ws1", WAITING.title(), "owner/repository", RunningTask.Kind.TASK,
                WAITING.id().toString(), RunningTask.Status.WAITING, NOW.minusSeconds(240), Optional.empty(), OptionalInt.empty(), Optional.empty()),
                new RunningTask(SETTLED.id().toString(), "ws1", SETTLED.title(), "owner/repository", RunningTask.Kind.TASK, SETTLED.id().toString(),
                        RunningTask.Status.COMPLETED, NOW.minusSeconds(900), Optional.empty(), OptionalInt.empty(), Optional.empty())));
        when(activities.read(ImmutableList.of(WAITING))).thenReturn(ImmutableMap.of(WAITING.id(), new TaskActivity(Optional.of(new TaskMessage(TaskMessage.Author.AGENT,
                "Which table should hold the snapshot id?", NOW.minusSeconds(240), Optional.empty())), Optional.empty(), Optional.empty())));
        when(reviews.findReview("r7")).thenReturn(Optional.of(new AgentReview("r7", "ws1", "owner/repository", OptionalInt.of(31377), OptionalLong.of(8), IDLE.id().toString(),
                Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty(), Optional.empty())));

        when(workItems.find("w22")).thenReturn(Optional.of(work("w22", Optional.of("LOCAL:w1"), WorkItem.State.WORKING, NOW.minusSeconds(500))));
        when(workItems.list("ws1")).thenReturn(ImmutableList.of(work("w22", Optional.of("LOCAL:w1"), WorkItem.State.WORKING, NOW.minusSeconds(500)),
                work("w23", Optional.of("LOCAL:w1"), WorkItem.State.DONE, NOW.minusSeconds(5000)), work("w24", Optional.of("LOCAL:w1"), WorkItem.State.DROPPED, NOW),
                work("w1", Optional.empty(), WorkItem.State.PLANNED, NOW.minusSeconds(9000))));
        when(workItems.find("w1")).thenReturn(Optional.of(work("w1", Optional.empty(), WorkItem.State.PLANNED, NOW.minusSeconds(9000))));
        when(epics.find("LOCAL:w1")).thenReturn(Optional.of(new Epic("LOCAL:w1", "ws1", ImmutableList.of(), Optional.empty(), false, ImmutableList.of(), ImmutableList.of(),
                Optional.empty(), "", ImmutableList.of())));
        when(agenda.epicSource(any(), anyString())).thenReturn(Optional.empty());
        when(agenda.epicSource(WORKSPACE, "LOCAL:w1"))
                .thenReturn(Optional.of(new AgendaReader.EpicSource("Iceberg write path", AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w1"))));
    }

    @Test
    void drawsEachRefFromTheStoresWithItsKindsFacts()
    {
        String waiting = "task:" + WAITING.id();
        Map<String, AssistantRow> read = rows.rows(ImmutableList.of("pr:owner/repository#31402", "issue:owner/repository#77", "jira:cloud:10042", waiting, "review:r7", "work:w22",
                "epic:LOCAL:w1", "pr:owner/repository#1", "task:not-a-task", "ws:ws1", "pr:owner/repository#31402"));

        // A ref that no longer resolves has no row, so the panel draws it as gone; a repeated ref is read once.
        assertThat(read).containsOnlyKeys("pr:owner/repository#31402", "issue:owner/repository#77", "jira:cloud:10042", waiting, "review:r7", "work:w22", "epic:LOCAL:w1");
        assertThat(read.get("pr:owner/repository#31402")).isEqualTo(new AssistantRow("pr:owner/repository#31402", Kind.PULL, "Retry Iceberg commit on conflict",
                Optional.of("#31402"), Optional.of("open"), Optional.of("failing"), Optional.of("changes_requested"), Optional.of(1), true, false, false, Optional.of(HEAD),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(NOW.minusSeconds(60)), Optional.of(new Latest(Optional.of("bo-t"),
                        "requested changes: Please split the retry into its own commit", NOW.minusSeconds(300))),
                        AgendaTarget.pull("owner/repository", 31402, Optional.of("ws1"))));
        assertThat(read.get("issue:owner/repository#77")).isEqualTo(new AssistantRow("issue:owner/repository#77", Kind.ISSUE, "Snapshot ids in $snapshots", Optional.of("#77"),
                Optional.of("open"), Optional.empty(), Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(NOW.minusSeconds(900)), Optional.empty(), AgendaTarget.issue("owner/repository", 77, "ws1")));
        AssistantRow ticket = read.get("jira:cloud:10042");
        assertThat(ticket.key()).contains("ICE-7");
        assertThat(ticket.state()).contains("In progress");
        assertThat(ticket.latest()).contains(new Latest(Optional.of("Marlo T"), "moved to In progress", NOW.minusSeconds(1800)));
        assertThat(ticket.target()).isEqualTo(AgendaTarget.jira("connection", "cloud", "10042", Optional.of("ws1")));

        // A task's phase is how it runs now, since it began; its last message is the latest event.
        assertThat(read.get(waiting)).isEqualTo(new AssistantRow(waiting, Kind.TASK, "Expose snapshot id in metadata", Optional.empty(), Optional.of("waiting"),
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.of("Claude Code"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(NOW.minusSeconds(240)),
                Optional.of(new Latest(Optional.of("Claude Code"), "Which table should hold the snapshot id?", NOW.minusSeconds(240))),
                AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", WAITING.id().toString())));
        AssistantRow review = read.get("review:r7");
        assertThat(review.kind()).isEqualTo(Kind.REVIEW);
        assertThat(review.key()).contains("#31377");
        assertThat(review.state()).contains("idle");
        assertThat(review.agent()).contains("Codex");
        assertThat(review.at()).contains(IDLE.updatedAt());
        assertThat(review.target()).isEqualTo(AgendaTarget.of(AgendaTarget.Kind.REVIEW, "ws1", "r7"));

        AssistantRow work = read.get("work:w22");
        assertThat(work.state()).contains("working");
        assertThat(work.epic()).contains("Iceberg write path");
        // An Epic counts its finished work against all of it that is not dropped, and opens its source.
        AssistantRow epic = read.get("epic:LOCAL:w1");
        assertThat(epic.title()).isEqualTo("Iceberg write path");
        assertThat(epic.done()).contains(1);
        assertThat(epic.total()).contains(2);
        assertThat(epic.at()).contains(NOW);
        assertThat(epic.target()).isEqualTo(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w1"));

        // Tickets come from Jira's cache; reading rows never asks Jira.
        verify(jira).cachedIssue("cloud", "10042");
        verifyNoMoreInteractions(jira);
    }

    @Test
    void readsAPullRequestAsTheSignedInUserSeesIt()
    {
        when(pullRequests.findById(7L)).thenReturn(Optional.of(pullRequest("blocked")));
        when(credentials.cachedLogin()).thenReturn(Optional.of("Alba-T"));

        AssistantRow approver = rows.rows(ImmutableList.of("pr:owner/repository#31402")).get("pr:owner/repository#31402");
        // The signed-in login decides whose pull request it is, over how it was found, as the pull request list does.
        assertThat(approver.mine()).isFalse();
        assertThat(approver.youApproved()).isTrue();
        assertThat(approver.mergeBlocked()).isTrue();

        when(credentials.cachedLogin()).thenReturn(Optional.of("bo-t"));
        AssistantRow reviewer = rows.rows(ImmutableList.of("pr:owner/repository#31402")).get("pr:owner/repository#31402");
        assertThat(reviewer.youApproved()).isFalse();
        assertThat(reviewer.latest().map(Latest::by)).contains(Optional.of("You"));
    }

    @Test
    void wordsAPullRequestsLatestEventAsNeedsAttentionDoes()
    {
        when(credentials.cachedLogin()).thenReturn(Optional.of("bo-t"));

        assertThat(latestText(activity("head_ref_force_pushed", Optional.empty(), Optional.empty()))).isEqualTo("force-pushed");
        assertThat(latestText(activity("ready_for_review", Optional.empty(), Optional.empty()))).isEqualTo("marked this ready for review");
        assertThat(latestText(activity("reviewed", Optional.of("APPROVED"), Optional.empty()))).isEqualTo("approved these changes");
        assertThat(latestText(activity("review_requested", Optional.empty(), Optional.of("bo-t")))).isEqualTo("requested your review");
        assertThat(latestText(activity("review_requested", Optional.empty(), Optional.of("marlo-t")))).isEqualTo("requested review from marlo-t");
        assertThat(latestText(activity("some_new_event", Optional.empty(), Optional.empty()))).isEqualTo("updated this pull request");
        // A quote keeps its lines, so the panel can fold its Markdown into one.
        assertThat(latestText(new PrActivitySummary("commented", Optional.of("bo-t"), NOW, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("Done\n\n- [x] tests"), Optional.empty(), OptionalInt.empty()))).isEqualTo("commented: Done\n\n- [x] tests");
    }

    @Test
    void readsAnOpenTaskAsIdleOnceItsTurnSettlesAndAnEndedOneAsEnded()
    {
        Map<String, AssistantRow> read = rows.rows(ImmutableList.of("task:" + SETTLED.id(), "task:" + ENDED.id()));

        assertThat(read.get("task:" + SETTLED.id()).state()).contains("idle");
        assertThat(read.get("task:" + SETTLED.id()).at()).contains(SETTLED.updatedAt());
        assertThat(read.get("task:" + ENDED.id()).state()).contains("failed");
    }

    @Test
    void namesAnItemByTheKeyItsNeedsAttentionRowUses()
    {
        Workspace iceberg = new Workspace("ws2", "Iceberg", 2, RepoRef.of("Apache", "Iceberg"), Optional.empty(), Optional.empty(), 2, RepoRef.of("Apache", "Iceberg"),
                Workspace.Topology.DIRECT, "main", "https://github.com/Apache/Iceberg.git", Optional.empty(), Optional.empty(), "/managed/iceberg", Optional.empty(),
                Optional.empty(), 0, NOW, NOW);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(WORKSPACE, iceberg));

        assertThat(rows.attentionKey("pr:owner/repository#31402")).contains("owner/repository#31402");
        // A ref is lowercase; the list keys an issue by its repository as the workspace spells it.
        assertThat(rows.attentionKey("issue:apache/iceberg#12")).contains("Apache/Iceberg#12");
        assertThat(rows.attentionKey("jira:cloud:10042")).contains("jira:cloud:10042");
        assertThat(rows.attentionKey("task:7d0e")).contains("task:7d0e");
        assertThat(rows.attentionKey("pr:owner/repository#1")).isEmpty();
        assertThat(rows.attentionKey("issue:apache/spark#3")).isEmpty();
        assertThat(rows.attentionKey("work:w22")).isEmpty();
    }

    @Test
    void leavesOutARefWhoseStoreCannotBeReadNow()
    {
        when(issues.find(1, 77)).thenThrow(new IllegalStateException("database is locked"));

        assertThat(rows.rows(ImmutableList.of("issue:owner/repository#77", "work:w22"))).containsOnlyKeys("work:w22");
    }

    private String latestText(PrActivitySummary activity)
    {
        when(details.findLatestActivities(ImmutableSet.of(7L))).thenReturn(ImmutableMap.of(7L, activity));
        return rows.rows(ImmutableList.of("pr:owner/repository#31402")).get("pr:owner/repository#31402").latest().orElseThrow().text();
    }

    private static PrActivitySummary activity(String event, Optional<String> state, Optional<String> requested)
    {
        return new PrActivitySummary(event, Optional.of("bo-t"), NOW, state, Optional.empty(), requested, Optional.empty(), Optional.empty(), OptionalInt.empty());
    }

    private static PullRequest pullRequest(String mergeableState)
    {
        return new PullRequest(7, "owner/repository", 31402, "Retry Iceberg commit on conflict", "elric-t", "https://github.com/owner/repository/pull/31402", NOW, NOW,
                PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of("marlo-t"), PullRequestDetail.CiStatus.FAILING,
                10, 2, 3, "open", null, null, true, mergeableState, NOW, "retry", null, NOW.minusSeconds(60), null, "CHANGES_REQUESTED");
    }

    private static PrReviewState review(String login, String state, long secondsAgo)
    {
        return new PrReviewState(1L, login, "", false, state, NOW.minusSeconds(secondsAgo), "", "", "abc");
    }

    private static WorkItem work(String id, Optional<String> epicId, WorkItem.State state, Instant at)
    {
        return new WorkItem(id, "ws1", "Add a snapshot id column " + id, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY, state, 0,
                ImmutableList.of(), ImmutableList.of(), at, at, WorkItem.Priority.NORMAL, ImmutableList.of(), epicId, 0, ImmutableList.of());
    }

    private static AgentTask task(String title, String lane, Instant at)
    {
        return AgentTask.created(TaskId.random(), Optional.of("ws1"), true, title, new AgentLaneId(lane), new RepositoryIdentity("owner", "repository"),
                Path.of("/tmp/assistant-task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), at);
    }

    private static ModelSettingsService.Provider provider(String id, String label)
    {
        return new ModelSettingsService.Provider(id, label, Optional.empty(), AgentProviderStatus.Readiness.READY, Optional.empty(), "", Optional.empty(),
                new AgentCapabilities(true, true, true, ImmutableSet.of(AgentPermissionPolicy.AUTO), ImmutableSet.of()), ImmutableList.of(), "", "");
    }
}
