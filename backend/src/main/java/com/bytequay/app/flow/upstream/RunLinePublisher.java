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
package com.bytequay.app.flow.upstream;

/**
 * Live output of a run's agent turn, while the turn is still going.
 *
 * <p>Everything else about a run is durable and can be read afterwards. This
 * one cannot wait for the end: a repair that compiles for four minutes leaves
 * the run looking stalled, and the whole point of watching is seeing that it
 * is not.
 *
 * <p>Best-effort by contract. A dropped line costs a moment of live view, so
 * an implementation must never fail a turn, and {@link #NONE} is a complete
 * implementation for a deployment that serves no watcher.
 */
@FunctionalInterface
public interface RunLinePublisher
{
    RunLinePublisher NONE = (runId, line) -> {};

    void publish(String runId, String line);
}
