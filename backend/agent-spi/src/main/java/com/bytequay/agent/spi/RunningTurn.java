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
package com.bytequay.agent.spi;

import java.util.concurrent.CompletionStage;

/**
 * Controls and observes one in-flight provider turn.
 *
 * <p>Provider events are emitted through the sink associated with the owning
 * {@link AgentSession}; this handle supplies only the provider reference,
 * terminal result, and idempotent cancellation operation.
 */
public interface RunningTurn
{
    /**
     * Returns the provider turn reference, or a locally generated opaque reference
     * when the provider exposes none.
     *
     * @return stable reference for this provider invocation
     */
    ProviderTurnRef reference();

    /**
     * Returns a stage completed exactly once with the observed terminal result.
     *
     * @return success, failure, cancellation, or interruption result
     */
    CompletionStage<AgentTurnResult> completion();

    /**
     * Requests provider cancellation without blocking for process exit.
     *
     * <p>The operation is idempotent; {@link #completion()} reports the eventual
     * terminal outcome.
     */
    void cancel();
}
