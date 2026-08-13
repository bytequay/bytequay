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
package com.bytequay.app.flow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

final class TestNewFlowAgentPermissions
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anApprovedRequestReturnsTheAllowDecisionWithItsInput()
            throws Exception
    {
        NewFlowAgentPermissions permissions =
                new NewFlowAgentPermissions(MAPPER);
        CompletableFuture<String> decision = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return permissions.ask("run-1", MAPPER.readTree(
                                "{\"tool_name\":\"Bash\","
                                        + "\"input\":{\"command\":\"ls\"}}"));
                    }
                    catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
        // The question appears for exactly this run, then the answer both
        // unblocks the turn and closes the card.
        NewFlowAgentPermissions.PendingApproval pending = await(permissions);
        assertThat(pending.runId()).isEqualTo("run-1");
        assertThat(pending.toolName()).isEqualTo("Bash");
        assertThat(pending.inputJson()).contains("\"command\":\"ls\"");
        assertThat(permissions.pending("run-2")).isEmpty();

        assertThat(permissions.answer(pending.approvalId(), true)).isTrue();
        assertThat(decision.get(5, TimeUnit.SECONDS))
                .contains("\"behavior\":\"allow\"")
                .contains("\"command\":\"ls\"");
        assertThat(permissions.pending("run-1")).isEmpty();
        // The question is gone; a second answer has nothing to decide.
        assertThat(permissions.answer(pending.approvalId(), false)).isFalse();
    }

    @Test
    void aDeniedRequestTellsTheAgentToContinueAnotherWay()
            throws Exception
    {
        NewFlowAgentPermissions permissions =
                new NewFlowAgentPermissions(MAPPER);
        CompletableFuture<String> decision = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return permissions.ask("run-1", MAPPER.readTree(
                                "{\"tool_name\":\"WebFetch\",\"input\":{}}"));
                    }
                    catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
        NewFlowAgentPermissions.PendingApproval pending = await(permissions);

        assertThat(permissions.answer(pending.approvalId(), false)).isTrue();
        assertThat(decision.get(5, TimeUnit.SECONDS))
                .contains("\"behavior\":\"deny\"")
                .contains("continue with");
    }

    private static NewFlowAgentPermissions.PendingApproval await(
            NewFlowAgentPermissions permissions)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var pending = permissions.pending("run-1");
            if (!pending.isEmpty()) {
                return pending.getFirst();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the permission question never appeared");
    }
}
