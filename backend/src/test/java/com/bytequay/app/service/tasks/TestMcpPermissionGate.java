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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.PermissionDecision;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMcpPermissionGate
{
    @Test
    void decideCompletesARegisteredFutureWithTheUsersChoice()
            throws Exception
    {
        McpPermissionGate gate = new McpPermissionGate();
        CompletableFuture<PermissionDecision> future = gate.register("call-1");

        assertThat(future.isDone()).isFalse();

        gate.decide("call-1", PermissionDecision.ALLOW);

        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(PermissionDecision.ALLOW);
    }

    @Test
    void registerIsIdempotentAndReturnsTheSameFuture()
    {
        McpPermissionGate gate = new McpPermissionGate();
        CompletableFuture<PermissionDecision> first = gate.register("call-2");
        CompletableFuture<PermissionDecision> second = gate.register("call-2");

        assertThat(first).isSameAs(second);
    }

    @Test
    void decideAfterCancelIsANoOp()
    {
        McpPermissionGate gate = new McpPermissionGate();
        CompletableFuture<PermissionDecision> future = gate.register("call-3");

        gate.cancel("call-3");
        gate.decide("call-3", PermissionDecision.ALLOW);

        assertThat(future.isCancelled()).isTrue();
        assertThatThrownBy(future::join).isInstanceOf(CancellationException.class);
    }

    @Test
    void decideForUnknownCallIdIsSilentlyDropped()
    {
        McpPermissionGate gate = new McpPermissionGate();

        // Should not throw — replays / out-of-order resolution
        // happen in practice and shouldn't crash the controller.
        gate.decide("never-registered", PermissionDecision.DENY);
    }

    @Test
    void pendingCallIdsForReturnsOnlyOutstandingCallsForThatTool()
    {
        McpPermissionGate gate = new McpPermissionGate();
        CompletableFuture<PermissionDecision> bash1 = gate.register("c-bash-1", "Bash");
        CompletableFuture<PermissionDecision> bash2 = gate.register("c-bash-2", "Bash");
        CompletableFuture<PermissionDecision> edit1 = gate.register("c-edit-1", "Edit");
        CompletableFuture<PermissionDecision> untagged = gate.register("c-untagged");

        assertThat(gate.pendingCallIdsFor("Bash"))
                .containsExactlyInAnyOrder("c-bash-1", "c-bash-2");
        assertThat(gate.pendingCallIdsFor("Edit"))
                .containsExactly("c-edit-1");
        assertThat(bash1.isDone()).isFalse();
        assertThat(bash2.isDone()).isFalse();
        assertThat(edit1.isDone()).isFalse();
        assertThat(untagged.isDone()).isFalse();
    }

    @Test
    void pendingCallIdsForOmitsCallsAlreadyDecidedOrCancelled()
    {
        McpPermissionGate gate = new McpPermissionGate();
        CompletableFuture<PermissionDecision> allowed = gate.register("c-1", "Bash");
        CompletableFuture<PermissionDecision> pending = gate.register("c-2", "Bash");
        CompletableFuture<PermissionDecision> cancelled = gate.register("c-3", "Bash");

        gate.decide("c-1", PermissionDecision.ALLOW);
        gate.cancel("c-3");

        assertThat(gate.pendingCallIdsFor("Bash")).containsExactly("c-2");
        assertThat(allowed).isCompletedWithValue(PermissionDecision.ALLOW);
        assertThat(pending.isDone()).isFalse();
        assertThat(cancelled.isCancelled()).isTrue();
    }
}
