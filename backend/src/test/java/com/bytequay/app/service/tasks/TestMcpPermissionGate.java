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
}
