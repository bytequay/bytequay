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
package com.bytequay.app.developmentflow.execution;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Fail-closed seam for historical saga APIs; it never admits work. */
@Component
public final class RetiredSagaGate
{
    public Optional<Attempt> tryAcquire(
            String taskId,
            String operationId,
            Set<CapacityManager.CapacityLane> lanes)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(operationId, "operationId is null");
        requireNonNull(lanes, "lanes is null");
        throw new UnsupportedOperationException(
                "LEGACY saga execution is retired; use a typed V2 operation");
    }

    public interface Attempt
            extends AutoCloseable
    {
        void requireLive();

        boolean leaseLost();

        @Override
        void close();
    }
}
