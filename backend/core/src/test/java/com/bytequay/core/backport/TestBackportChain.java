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
package com.bytequay.core.backport;

import com.bytequay.core.backport.BackportChain.Acceptance;
import com.bytequay.core.backport.BackportChain.Hop;
import com.bytequay.core.backport.BackportChain.Plan;
import com.bytequay.core.backport.BackportChain.Route;
import com.bytequay.core.backport.BackportChain.Selection;
import com.bytequay.core.backport.BackportChain.State;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.MergedPullRequestRange;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestBackportChain
{
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final String BASE = "a".repeat(40);
    private static final String TIP = "b".repeat(40);

    @Test
    void eachHopUpdateChangesOnlyItsOwnFields()
    {
        Hop hop = hop(route(3));
        MergedPullRequestRange landed = new MergedPullRequestRange(
                PullRequestRef.of("test", "p4", 7), 4, "main", NOW, TIP, ImmutableList.of("c".repeat(40)), BASE, ImmutableList.of(TIP));
        Selection selection = new Selection(BASE, TIP, ImmutableList.of(TIP), NOW);
        Plan plan = new Plan(TIP, new PullRequestDraft("Backport the fix", "body"));
        PullRequestRef pullRequest = PullRequestRef.of("test", "p3", 9);

        assertOnlyChanged(hop, hop.withSource(landed), "landedSource");
        assertThat(hop.withSource(landed).landedSource()).contains(landed);
        assertOnlyChanged(hop, hop.withSelection(selection), "selection");
        assertThat(hop.withSelection(selection).selection()).contains(selection);
        assertOnlyChanged(hop, hop.withPlan(plan), "plan");
        assertThat(hop.withPlan(plan).plan()).contains(plan);
        assertOnlyChanged(hop, hop.withPullRequest(pullRequest), "pullRequest");
        assertThat(hop.withPullRequest(pullRequest).pullRequest()).contains(pullRequest);

        Hop executing = hop.withExecution("execution-1");
        assertOnlyChanged(hop, executing, "executionId");
        assertThat(executing.executionId()).contains("execution-1");
        assertOnlyChanged(executing, executing.publishing(), "publicationAttempted");
        assertThat(executing.publishing().publicationAttempted()).isTrue();

        assertOnlyChanged(hop, hop.finish(false), "completed");
        assertThat(hop.finish(false).completed()).isTrue();
        assertOnlyChanged(hop, hop.finish(true), "completed", "noOp");
        assertThat(hop.finish(true).noOp()).isTrue();
    }

    @Test
    void eachChainUpdateChangesOnlyItsOwnFields()
    {
        BackportChain chain = chain();
        PullRequestRef source = PullRequestRef.of("test", "p4", 7);

        BackportChain stopping = chain.withState(State.STOPPING, Optional.of("Stop requested"), NOW.plusSeconds(1));
        assertOnlyChanged(chain, stopping, "state", "reason", "updatedAt");
        assertThat(stopping.state()).isEqualTo(State.STOPPING);
        assertThat(stopping.reason()).contains("Stop requested");
        assertThat(stopping.updatedAt()).isEqualTo(NOW.plusSeconds(1));

        BackportChain sourced = chain.withSource(source, NOW.plusSeconds(1));
        assertOnlyChanged(chain, sourced, "sourcePullRequest", "updatedAt");
        assertThat(sourced.sourcePullRequest()).contains(source);

        BackportChain merged = sourced.withSourceMerged(NOW.plusSeconds(2));
        assertOnlyChanged(sourced, merged, "sourceMerged", "updatedAt");
        assertThat(merged.sourceMerged()).isTrue();

        Hop executing = chain.hops().getFirst().withExecution("execution-1");
        BackportChain stepped = chain.withHop(0, executing, NOW.plusSeconds(1));
        assertOnlyChanged(chain, stepped, "hops", "updatedAt");
        assertThat(stepped.hops()).containsExactly(executing);

        Acceptance extension = new Acceptance("extend-key", Optional.empty(), ImmutableList.of("p5"), ImmutableList.of(0));
        Hop next = new Hop(0, route(5), "codex", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty(), false, false);
        BackportChain completed = chain.withState(State.COMPLETED, Optional.empty(), NOW.plusSeconds(1));
        BackportChain extended = completed.append(extension, ImmutableList.of(next), NOW.plusSeconds(2));
        assertOnlyChanged(completed, extended, "acceptances", "hops", "state", "updatedAt");
        assertThat(extended.acceptances()).containsExactly(chain.acceptances().getFirst(), extension);
        assertThat(extended.hops()).containsExactly(chain.hops().getFirst(), next);
        assertThat(extended.state()).isEqualTo(State.ACTIVE);

        BackportChain versioned = chain.withVersion(3);
        assertOnlyChanged(chain, versioned, "version");
        assertThat(versioned.version()).isEqualTo(3);
    }

    /** An update must leave every field it does not name exactly as it was. */
    private static void assertOnlyChanged(Object before, Object after, String... changed)
    {
        assertThat(after).usingRecursiveComparison().ignoringFields(changed).isEqualTo(before);
    }

    private static BackportChain chain()
    {
        return new BackportChain("chain-1", "p4", Optional.of("request"), Optional.of("source"), "task-branch", route(4), Optional.empty(), false,
                ImmutableList.of(new Acceptance("request-key", Optional.of("turn"), ImmutableList.of("p3"), ImmutableList.of(-1))),
                ImmutableList.of(hop(route(3))), State.ACTIVE, Optional.empty(), 0, NOW, NOW);
    }

    private static Hop hop(Route route)
    {
        return new Hop(-1, route, "codex", Optional.of("model"), Optional.of("high"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty(), false, false);
    }

    private static Route route(int number)
    {
        RepoRef repository = RepoRef.of("test", "p" + number);
        return new Route("p" + number, number, repository, number, repository, "https://github.com/" + repository.fullName() + ".git",
                "https://github.com/" + repository.fullName() + ".git", "origin", "main");
    }
}
