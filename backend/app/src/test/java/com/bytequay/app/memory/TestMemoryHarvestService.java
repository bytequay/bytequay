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
package com.bytequay.app.memory;

import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.context.ContextBuild;
import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.repository.github.GitHubHarvestClient;
import com.bytequay.app.repository.github.GitHubHarvestClient.ClosedPull;
import com.bytequay.app.repository.github.GitHubHarvestClient.Note;
import com.bytequay.app.repository.github.GitHubHarvestClient.PullDiscussion;
import com.bytequay.app.repository.github.GitHubHarvestClient.ReviewThread;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.memory.MemorySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMemoryHarvestService
{
    private static final RepoRef REPOSITORY = RepoRef.of("trinodb", "trino");
    private static final String HEAD = "a".repeat(40);
    private final GitHubHarvestClient github = mock(GitHubHarvestClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final MemoryHarvestService harvests = new MemoryHarvestService(github, credentials, workspaces, builds, settings,
            new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()), Clock.fixed(NOW, ZoneOffset.UTC));
    private final ContextBuildService.View view = new ContextBuildService.View(1, false, Optional.empty());

    @BeforeEach
    void setUp()
    {
        when(workspaces.requireReadyRepository("workspace")).thenReturn(new WorkspaceRepositoryContext("workspace", Path.of("/tmp/repository"), 1, REPOSITORY,
                OptionalLong.empty(), REPOSITORY, "origin", "origin", "master", ImmutableList.of()));
        when(builds.baseline("workspace")).thenReturn(Optional.of(new ContextBuildService.Baseline("master", HEAD)));
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("token", new UserProfile("alice", "", "", "", 0, 0, 0, "", "", "", "")));
        when(builds.harvest(eq("workspace"), any(), eq(HEAD), any())).thenReturn(view);
        when(builds.get("workspace")).thenReturn(view);
    }

    @Test
    void findsPullRequestsToLearnFromInTheMonitoredRepositoryAndReadsNothingForABlankQuery()
    {
        ClosedPull found = new ClosedPull(31354, "Fix checkpoint write", at(1), true);
        when(github.findClosedPulls("token", REPOSITORY, "checkpoint")).thenReturn(ImmutableList.of(found));

        assertThat(harvests.findPulls("workspace", "checkpoint")).containsExactly(found);
        assertThat(harvests.findPulls("workspace", "  ")).isEmpty();
        verify(github, never()).findClosedPulls("token", REPOSITORY, "  ");
        assertThatThrownBy(() -> harvests.findPulls("workspace", "x".repeat(201))).hasMessageContaining("at most 200 characters");
    }

    @Test
    void keepsTheThreadsAMaintainerSpokeInAndSkipsBotsUnexplainedClosesAndThreadsReadBefore()
    {
        PullDiscussion merged = new PullDiscussion(31201, "Add a property", url(31201), true, at(1), ImmutableList.of(
                thread("t1", note("n1", "bo", true, "Please document the property."), note("n2", "alice", false, "Done.")),
                thread("t2", note("n3", "carol", false, "Nice.")),
                thread("t3", note("n4", "ci[bot]", false, "Build passed.")),
                thread("t4", note("n5", "bo", true, "Already read."))),
                ImmutableList.of(note("r1", "bo", true, "Use the existing helper."), note("r2", "dave", false, "LGTM")),
                ImmutableList.of(note("k1", "bo", true, "Thanks, merging.")));
        PullDiscussion unexplained = new PullDiscussion(31202, "Rejected idea", url(31202), false, at(2), ImmutableList.of(
                thread("t5", note("n6", "bo", true, "Hmm."))), ImmutableList.of(), ImmutableList.of(note("k2", "alice", false, "Closing.")));
        PullDiscussion explained = new PullDiscussion(31203, "Delete in finish()", url(31203), false, at(3), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(note("k3", "dana", true, "Closing: the abort path already owns this cleanup.")));

        MemoryHarvestService.Harvested harvested = MemoryHarvestService.harvest(ImmutableList.of(merged, unexplained, explained), ImmutableList.of("t4"));

        assertThat(harvested.pulls()).extracting(ContextBuild.HarvestPull::number).containsExactly(31201, 31203);
        assertThat(harvested.threads()).extracting(ContextBuild.HarvestThread::githubId).containsExactly("t1", "r1", "k1", "k3");
        assertThat(harvested.threads()).extracting(ContextBuild.HarvestThread::part).containsExactly(MemorySource.Part.REVIEW_COMMENT, MemorySource.Part.REVIEW,
                MemorySource.Part.COMMENT, MemorySource.Part.CLOSE);
        assertThat(harvested.threads().getFirst().notes()).extracting(ContextBuild.HarvestNote::key).containsExactly("c1", "c2");
        assertThat(harvested.threads().getFirst().notes().getFirst().maintainer()).isTrue();
    }

    @Test
    void learnFromMyPrsReadsThePullRequestsClosedAfterTheCursorAndStartsOneRun()
    {
        settings.put(MemoryHarvestService.STATE + "workspace", "{\"cursor\":{\"number\":100,\"closedAt\":\"" + at(0) + "\"},\"seen\":[],\"last\":null}");
        when(github.closedPulls("token", REPOSITORY, "alice")).thenReturn(ImmutableList.of(new ClosedPull(103, "Newest", at(3), true),
                new ClosedPull(101, "Older", at(1), true), new ClosedPull(99, "Before the cursor", at(-1), true)));
        when(github.discussion("token", REPOSITORY, 103)).thenReturn(Optional.of(discussion(103, at(3), "t103")));
        when(github.discussion("token", REPOSITORY, 101)).thenReturn(Optional.of(discussion(101, at(1), "t101")));

        MemoryHarvestService.Started started = harvests.start("workspace", new ContextBuildService.Request("key", 0), Optional.empty(), Optional.empty());

        assertThat(started).isEqualTo(new MemoryHarvestService.Started(true, 2, 2, view));
        ArgumentCaptor<ContextBuild.Harvest> harvest = ArgumentCaptor.forClass(ContextBuild.Harvest.class);
        verify(builds).harvest(eq("workspace"), eq(new ContextBuildService.Request("key", 0)), eq(HEAD), harvest.capture());
        assertThat(harvest.getValue().label()).isEqualTo("Learn from my PRs");
        assertThat(harvest.getValue().mine()).isTrue();
        assertThat(harvest.getValue().pulls()).extracting(ContextBuild.HarvestPull::number).containsExactly(103, 101);
        verify(github, never()).discussion("token", REPOSITORY, 99);
        assertThat(harvests.state("workspace").last().orElseThrow().outcome()).isEqualTo(MemoryHarvestService.LastRun.Outcome.RUNNING);
        assertThat(harvests.state("workspace").cursor()).contains(new MemoryHarvestService.Cursor(100, at(0)));
    }

    @Test
    void withNothingNewNoRunStartsAndTheCursorMovesPastWhatWasRead()
    {
        when(github.closedPulls("token", REPOSITORY, "alice")).thenReturn(ImmutableList.of(new ClosedPull(103, "Quiet", at(3), true)));
        when(github.discussion("token", REPOSITORY, 103)).thenReturn(Optional.of(new PullDiscussion(103, "Quiet", url(103), true, at(3), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(note("k1", "alice", false, "Thanks")))));

        MemoryHarvestService.Started started = harvests.start("workspace", new ContextBuildService.Request("key", 0), Optional.empty(), Optional.empty());

        assertThat(started.started()).isFalse();
        verify(builds, never()).harvest(any(), any(), any(), any());
        assertThat(harvests.state("workspace").cursor()).contains(new MemoryHarvestService.Cursor(103, at(3)));
        assertThat(harvests.state("workspace").last().orElseThrow().outcome()).isEqualTo(MemoryHarvestService.LastRun.Outcome.NOTHING);
    }

    @Test
    void learnFromThisPullRequestReadsOnlyItAndLeavesTheCursor()
    {
        when(github.discussion("token", REPOSITORY, 42)).thenReturn(Optional.of(discussion(42, at(5), "t42")));

        assertThatThrownBy(() -> harvests.start("workspace", new ContextBuildService.Request("key", 0), Optional.of(42), Optional.of("someone/else")))
                .hasMessageContaining("Only a pull request of trinodb/trino");
        harvests.start("workspace", new ContextBuildService.Request("key", 0), Optional.of(42), Optional.of("TrinoDB/Trino"));

        ArgumentCaptor<ContextBuild.Harvest> harvest = ArgumentCaptor.forClass(ContextBuild.Harvest.class);
        verify(builds).harvest(eq("workspace"), any(), eq(HEAD), harvest.capture());
        assertThat(harvest.getValue().label()).isEqualTo("Learn from #42");
        assertThat(harvest.getValue().mine()).isFalse();
        verify(github, never()).closedPulls(any(), any(), any());
    }

    @Test
    void aFinishedRunMovesTheCursorAndRemembersItsThreadsAndAFailedOneLeavesBoth()
    {
        ContextBuild.Harvest harvest = MemoryHarvestService.harvest(ImmutableList.of(discussion(101, at(1), "t101"), discussion(103, at(3), "t103")), ImmutableList.of())
                .toHarvest("harvest-1", "Learn from my PRs", true);
        ContextBuild build = build(harvest).withCapture(ContextBuild.Capture.empty().withAttempts(ImmutableMap.of(MemoryHarvestTools.SAVED + "m1", "1",
                MemoryHarvestTools.SAVED + "m2", "1", MemoryHarvestTools.SEEN_AGAIN + "m3:1", "1", MemoryHarvestTools.REPLACED + "m4", "1")));

        harvests.finish(build, false);
        assertThat(harvests.state("workspace").cursor()).isEmpty();
        assertThat(harvests.state("workspace").seen()).isEmpty();
        assertThat(harvests.state("workspace").last().orElseThrow().outcome()).isEqualTo(MemoryHarvestService.LastRun.Outcome.FAILED);

        harvests.finish(build, true);
        MemoryHarvestService.HarvestState state = harvests.state("workspace");
        assertThat(state.cursor()).contains(new MemoryHarvestService.Cursor(103, at(3)));
        assertThat(state.seen()).containsExactly("t101", "t103");
        MemoryHarvestService.LastRun last = state.last().orElseThrow();
        assertThat(last.outcome()).isEqualTo(MemoryHarvestService.LastRun.Outcome.DONE);
        assertThat(last.saved()).containsExactlyInAnyOrder("m1", "m2");
        assertThat(last.seenAgain()).isEqualTo(1);
        assertThat(last.replaced()).isEqualTo(1);
    }

    private static PullDiscussion discussion(int number, Instant closedAt, String threadId)
    {
        return new PullDiscussion(number, "Pull " + number, url(number), true, closedAt, ImmutableList.of(thread(threadId, note(threadId + "-n", "bo", true,
                "Please keep this rule."))), ImmutableList.of(), ImmutableList.of());
    }

    private static ReviewThread thread(String id, Note... notes)
    {
        return new ReviewThread(id, true, false, Optional.of("plugin/x/A.java"), ImmutableList.copyOf(notes));
    }

    private static Note note(String id, String author, boolean maintainer, String body)
    {
        return new Note(id, author, author.endsWith("[bot]"), maintainer, body, "https://github.com/trinodb/trino/pull/1#" + id, NOW);
    }

    private static String url(int number)
    {
        return "https://github.com/trinodb/trino/pull/" + number;
    }

    private static Instant at(int hours)
    {
        return NOW.plusSeconds(3600L * hours);
    }

    private static ContextBuild build(ContextBuild.Harvest harvest)
    {
        return new ContextBuild(1, "key", "sha256:" + "b".repeat(64), "workspace", "11111111-1111-4111-8111-111111111111", new RepositoryIdentity("trinodb", "trino"), 1,
                REPOSITORY, HEAD, "bytequay/learn", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW, ContextBuild.Capture.empty(),
                Optional.empty(), Optional.of(harvest), Optional.empty());
    }
}
