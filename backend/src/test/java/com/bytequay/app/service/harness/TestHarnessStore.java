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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.Bucket;
import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Event;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.Rule;
import com.bytequay.app.service.harness.HarnessModels.RuleStatus;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestHarnessStore
{
    @TempDir
    Path tempDir;

    private HarnessStore store;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("harness.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('ws', 'acme/widget', '', 0, 1, 1)
                """);
        store = new HarnessStore(jdbc);
    }

    @Test
    void candidateSubtypeRoundTripsButCannotRouteUntilApproval()
    {
        Rule candidate = store.upsertCandidate(new Rule(
                "rule", "ws", "acme", "widget", "plan mismatch", null,
                "resource:plan_mismatch", "agent", null, RuleStatus.CANDIDATE,
                "agent", 100, "[]", 1, 1, 1, null));

        assertThat(candidate.bucket()).isEqualTo(Bucket.RESOURCE);
        assertThat(candidate.bucketLabel()).isEqualTo("resource:plan_mismatch");
        assertThat(HarnessService.toRule(candidate).bucket()).isEqualTo("resource:plan_mismatch");
        assertThat(store.activeRules("ws", "acme", "widget")).isEmpty();

        Rule active = store.approveRule(candidate.id(), 2);
        assertThat(store.activeRules("ws", "acme", "widget"))
                .singleElement()
                .satisfies(rule -> {
                    assertThat(rule.id()).isEqualTo(active.id());
                    assertThat(rule.bucketLabel()).isEqualTo("resource:plan_mismatch");
                    assertThat(rule.binding()).isEqualTo("agent");
                    assertThat(rule.recipeJson()).isNull();
                });
    }

    @Test
    void thirdSuccessfulRecipeProposalActivatesTheFourthClassification()
    {
        Rule first = new Rule(
                "rule-1", "ws", "acme", "widget", "plan mismatch", null,
                "resource:plan_mismatch", "recipe:refresh_plan", "{\"safe\":true}",
                RuleStatus.CANDIDATE, "agent", 100, "[]", 1, 1, 1, null);
        Rule second = new Rule(
                "rule-2", "ws", "acme", "widget", "plan mismatch", null,
                "resource:plan_mismatch", "recipe:refresh_plan", "{\"safe\":true}",
                RuleStatus.CANDIDATE, "agent", 100, "[]", 1, 2, 2, null);
        Rule third = new Rule(
                "rule-3", "ws", "acme", "widget", "plan mismatch", null,
                "resource:plan_mismatch", "recipe:refresh_plan", "{\"safe\":true}",
                RuleStatus.CANDIDATE, "agent", 100, "[]", 1, 3, 3, null);

        assertThat(store.upsertCandidate(first).status()).isEqualTo(RuleStatus.CANDIDATE);
        assertThat(store.upsertCandidate(second).status()).isEqualTo(RuleStatus.CANDIDATE);
        Rule active = store.upsertCandidate(third);

        assertThat(active.status()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(active.hits()).isEqualTo(3);
        assertThat(active.approvedAtMs()).isEqualTo(3);
        HarnessClassifier.Classification fourth = new HarnessClassifier(store).classify(
                "ws", "acme", "widget", "root", "plan mismatch", "", 4);
        assertThat(fourth.rule())
                .isNotNull()
                .satisfies(rule -> {
                    assertThat(rule.binding()).isEqualTo("recipe:refresh_plan");
                    assertThat(rule.recipeJson()).isEqualTo("{\"safe\":true}");
                });
    }

    @Test
    void candidateBindingsCannotSmuggleOrOmitRecipeDescriptions()
    {
        Rule agentWithRecipe = new Rule(
                "agent", "ws", "acme", "widget", "compile", null,
                "build", "agent", "{}", RuleStatus.CANDIDATE,
                "agent", 100, "[]", 1, 1, 1, null);
        Rule recipeWithoutDescription = new Rule(
                "recipe", "ws", "acme", "widget", "plan", null,
                "resource", "recipe:plan", null, RuleStatus.CANDIDATE,
                "agent", 100, "[]", 1, 1, 1, null);

        assertThatThrownBy(() -> store.upsertCandidate(agentWithRecipe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent candidate");
        assertThatThrownBy(() -> store.upsertCandidate(recipeWithoutDescription))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a recipe description");
    }

    @Test
    void matchingCandidateIdentityFailsClosedWhenProposalsDisagree()
    {
        Rule agent = new Rule(
                "agent-1", "ws", "acme", "widget", "same failure", null,
                "build", "agent", null, RuleStatus.CANDIDATE,
                "agent", 100, "[]", 1, 1, 1, null);
        Rule different = new Rule(
                "agent-2", "ws", "acme", "widget", "same failure", null,
                "test", "agent", null, RuleStatus.CANDIDATE,
                "agent", 100, "[]", 1, 2, 2, null);
        store.upsertCandidate(agent);

        assertThatThrownBy(() -> store.upsertCandidate(different))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disagree");
    }

    @Test
    void steeringIsPersistedAndDatabaseBounded()
    {
        Watch watch = watch();
        store.insertWatch(watch);

        Cycle cycle = store.startCycle("cycle", watch.id(), "manual", "focus on module x", 1);

        assertThat(store.findCycle(cycle.id()).orElseThrow().steeringText())
                .isEqualTo("focus on module x");

        store.finishCycle(cycle.id(), CycleStatus.NO_CHANGE, Phase.PROBE,
                0, null, null, null, null, 2);
        assertThatThrownBy(() -> store.startCycle(
                "too-long", watch.id(), "manual", "x".repeat(4_001), 3))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void failureSubtypeSurvivesEveryPersistenceTransition()
    {
        Watch watch = watch();
        store.insertWatch(watch);
        Cycle cycle = store.startCycle("cycle", watch.id(), "manual", null, 1);
        Failure failure = store.insertFailure(new Failure(
                "failure", cycle.id(), "run", 7L, "build", "root", null, null,
                "plan mismatch", "plan mismatch", "resource:plan_mismatch", null,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1));

        assertThat(failure.bucketLabel()).isEqualTo("resource:plan_mismatch");
        assertThat(HarnessService.toFailure(failure).bucket())
                .isEqualTo("resource:plan_mismatch");

        store.updateFailure(failure.id(), "test:timing_flake", null,
                FailureStatus.DIAGNOSING, null, null, null, null, 2);

        assertThat(store.listFailuresForCycle(cycle.id()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.bucket()).isEqualTo(Bucket.TEST);
                    assertThat(saved.bucketLabel()).isEqualTo("test:timing_flake");
                });
    }

    @Test
    void verifiedHandoffPersistsCycleAndWatchTerminalStateTogether()
    {
        Watch watch = watch();
        store.insertWatch(watch);
        Cycle cycle = store.startCycle("cycle", watch.id(), "manual", null, 1);

        assertThat(store.finishHandoff(
                cycle.id(), watch.id(), 27, "backup", "{\"proof\":true}",
                "green", "{\"reason\":\"verified\"}", 2)).isTrue();

        assertThat(store.findCycle(cycle.id()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(CycleStatus.HANDOFF);
                    assertThat(saved.phase()).isEqualTo(Phase.DONE);
                    assertThat(saved.backupRef()).isEqualTo("backup");
                    assertThat(saved.netNeutralProofJson()).isEqualTo("{\"proof\":true}");
                });
        assertThat(store.findWatch(watch.id()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(WatchStatus.HANDOFF);
                    assertThat(saved.handoffJson()).isEqualTo("{\"reason\":\"verified\"}");
                });
    }

    @Test
    void stoppedWatchAndCancelledCycleRejectAllLaterTransitions()
    {
        Watch watch = watch();
        store.insertWatch(watch);
        Cycle cycle = store.startCycle("cycle", watch.id(), "manual", null, 1);
        assertThat(store.recordCycleBackupIfLive(
                cycle.id(), "bytequay-backup/ci-harness/one", "a".repeat(40), 2)).isTrue();

        store.updateWatchStatus(watch.id(), WatchStatus.STOPPED, null, 3);
        store.finishCycle(cycle.id(), CycleStatus.CANCELLED, cycle.phase(), cycle.costMilliUsd(),
                cycle.backupRef(), cycle.netNeutralProofJson(), cycle.runStatusTail(), "stopped", 3);

        assertThat(store.updateCycleProgress(
                cycle.id(), CycleStatus.RUNNING, Phase.FIX, null, null, null, 4)).isFalse();
        assertThat(store.finishCycleIfLive(
                cycle.id(), CycleStatus.HANDOFF, Phase.DONE, 0, null, null, null, null, 4)).isFalse();
        assertThat(store.updateWatchStatusIfNotStopped(
                watch.id(), WatchStatus.HANDOFF, null, 4)).isFalse();
        assertThat(store.findWatch(watch.id()).orElseThrow().status()).isEqualTo(WatchStatus.STOPPED);
        assertThat(store.findCycle(cycle.id()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(CycleStatus.CANCELLED);
                    assertThat(saved.backupRef()).isEqualTo("bytequay-backup/ci-harness/one");
                    assertThat(saved.originalHead()).isEqualTo("a".repeat(40));
                });
    }

    @Test
    void handoffWatchesRemainPollableForTheNextRemoteHead()
    {
        Watch watch = watch();
        store.insertWatch(watch);
        store.updateWatchStatus(watch.id(), WatchStatus.HANDOFF, "{}", 2);

        assertThat(store.pollableWatches(10, 10))
                .extracting(Watch::id)
                .containsExactly(watch.id());
    }

    @Test
    void bootstrapCompletionIsAtomicAndCannotReviveAStoppedWatch()
    {
        Watch completed = pendingWatch("completed", 7);
        store.insertWatch(completed);

        assertThat(store.completeWatchBootstrap(
                completed.id(), "{\"forge\":\"github-actions\"}",
                "/tmp/isolated", "feature", 90)).isTrue();
        assertThat(store.findWatch(completed.id()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(WatchStatus.WATCHING);
                    assertThat(saved.bootstrapStatus()).isEqualTo("ready");
                    assertThat(saved.localPath()).isEqualTo("/tmp/isolated");
                    assertThat(saved.lastPolledAtMs()).isEqualTo(90);
                });
        assertThat(store.listEventsForWatch(completed.id(), 10))
                .extracting(Event::kind)
                .containsExactly("bootstrap_complete");

        Watch stopped = pendingWatch("stopped", 8);
        store.insertWatch(stopped);
        store.updateWatchStatus(stopped.id(), WatchStatus.STOPPED, null, 2);

        assertThat(store.completeWatchBootstrap(
                stopped.id(), "{}", "/tmp/isolated", "feature", 90)).isFalse();
        assertThat(store.failWatchBootstrap(
                stopped.id(), "{}", "{}", 90)).isFalse();
        assertThat(store.findWatch(stopped.id()).orElseThrow().status())
                .isEqualTo(WatchStatus.STOPPED);
        assertThat(store.listEventsForWatch(stopped.id(), 10)).isEmpty();

        Watch failed = pendingWatch("failed", 9);
        store.insertWatch(failed);
        assertThat(store.failWatchBootstrap(
                failed.id(), "{\"reason\":\"bootstrap_failed\"}",
                "{\"error\":\"invalid workflow\"}", 100)).isTrue();
        assertThat(store.findWatch(failed.id()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(WatchStatus.NEEDS_ATTENTION);
                    assertThat(saved.bootstrapStatus()).isEqualTo("failed");
                });
        assertThat(store.listEventsForWatch(failed.id(), 10))
                .extracting(Event::kind)
                .containsExactly("bootstrap_failed");
    }

    private static Watch watch()
    {
        return new Watch("watch", "ws", "acme", "widget", 7, null,
                "/tmp/widget", "main", "PR", WatchStatus.WATCHING, "head",
                "ready", "{}", 10_000, 0, null, 1, 1, null, null);
    }

    private static Watch pendingWatch(String id, int prNumber)
    {
        return new Watch(id, "ws", "acme", "widget", prNumber, null,
                "/tmp/widget", "feature", "PR", WatchStatus.BOOTSTRAP, null,
                "pending", "{}", 10_000, 0, null, 1, 1, null, null);
    }
}
