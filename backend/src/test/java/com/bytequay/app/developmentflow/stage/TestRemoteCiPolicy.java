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
package com.bytequay.app.developmentflow.stage;

import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.CANCELED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.FAILED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.MISSING;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.NEUTRAL;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.NONE;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PASSED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PENDING;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.QUEUED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.SKIPPED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.PolicyOutcome.ACCEPTED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.PolicyOutcome.WAITING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRemoteCiPolicy
{
    @Test
    void canonicalizesThePreviouslyPersistedGitHubCheckRunAlias()
    {
        RemoteCiPolicy.Check check = new RemoteCiPolicy.Check(
                "GITHUB_CHECK_RUN", "github-check:1", "build",
                RemoteCiPolicy.CheckState.PASSED, "completed", "success",
                null, null, "{}");

        assertThat(check.kind()).isEqualTo("CHECK_RUN");
    }

    @Test
    void rejectsEveryUnknownCheckKind()
    {
        assertThatThrownBy(() -> new RemoteCiPolicy.Check(
                "PROVIDER_SPECIFIC_CHECK", "provider-check:1", "build",
                RemoteCiPolicy.CheckState.PASSED, "completed", "success",
                null, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown CI check kind");
    }

    @Test
    void evaluatesEveryExplicitNonGreenState()
    {
        RemoteCiPolicy.Policy policy = policy();

        assertThat(RemoteCiPolicy.evaluate(List.of(), ImmutableSet.of(), policy).outcome())
                .isEqualTo(ACCEPTED);
        assertThat(evaluate(MISSING, policy))
                .isEqualTo(RemoteCiPolicy.PolicyOutcome.FAILED);
        assertThat(evaluate(QUEUED, policy)).isEqualTo(WAITING);
        assertThat(evaluate(PENDING, policy)).isEqualTo(WAITING);
        assertThat(evaluate(PASSED, policy)).isEqualTo(ACCEPTED);
        assertThat(evaluate(FAILED, policy))
                .isEqualTo(RemoteCiPolicy.PolicyOutcome.FAILED);
        assertThat(evaluate(NEUTRAL, policy)).isEqualTo(WAITING);
        assertThat(evaluate(SKIPPED, policy)).isEqualTo(ACCEPTED);
        assertThat(evaluate(CANCELED, policy))
                .isEqualTo(RemoteCiPolicy.PolicyOutcome.FAILED);
    }

    @Test
    void synthesizesMissingRequiredChecksAndFailedDominatesWaiting()
    {
        RemoteCiPolicy.Evaluation result = RemoteCiPolicy.evaluate(
                List.of(check("build", QUEUED), check("lint", FAILED)),
                ImmutableSet.of("build", "lint", "security"), policy());

        assertThat(result.outcome()).isEqualTo(
                RemoteCiPolicy.PolicyOutcome.FAILED);
        assertThat(result.normalizedStatus()).isEqualTo(FAILED);
        assertThat(result.checkCount()).isEqualTo(3);
        assertThat(result.missingRequiredCount()).isEqualTo(1);
        assertThat(result.checks()).extracting(RemoteCiPolicy.Check::name)
                .containsExactly("build", "lint", "security");
    }

    private static RemoteCiPolicy.PolicyOutcome evaluate(
            RemoteCiPolicy.CheckState state, RemoteCiPolicy.Policy policy)
    {
        if (state == NONE) {
            return RemoteCiPolicy.evaluate(List.of(), ImmutableSet.of(), policy).outcome();
        }
        RemoteCiPolicy.Check check = state == MISSING
                ? new RemoteCiPolicy.Check(
                        "REQUIRED_MISSING", "missing:build", "build", state,
                        null, null, null, null, null)
                : check("build", state);
        return RemoteCiPolicy.evaluate(List.of(check), ImmutableSet.of(), policy).outcome();
    }

    private static RemoteCiPolicy.Check check(
            String name, RemoteCiPolicy.CheckState state)
    {
        return new RemoteCiPolicy.Check(
                "CHECK_RUN", "id:" + name, name, state,
                "completed", state.name(), null, null, null);
    }

    private static RemoteCiPolicy.Policy policy()
    {
        EnumMap<RemoteCiPolicy.CheckState, RemoteCiPolicy.PolicyOutcome> values =
                new EnumMap<>(RemoteCiPolicy.CheckState.class);
        values.put(NONE, ACCEPTED);
        values.put(MISSING, RemoteCiPolicy.PolicyOutcome.FAILED);
        values.put(QUEUED, WAITING);
        values.put(PENDING, WAITING);
        values.put(NEUTRAL, WAITING);
        values.put(SKIPPED, ACCEPTED);
        values.put(CANCELED, RemoteCiPolicy.PolicyOutcome.FAILED);
        return new RemoteCiPolicy.Policy(values);
    }
}
