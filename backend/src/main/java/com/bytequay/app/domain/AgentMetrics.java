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
package com.bytequay.app.domain;

/**
 * Cheap snapshot of an in-flight agent session — what the header
 * strip on the task detail page renders and what we persist back to
 * the {@code tasks} row on every checkpoint.
 *
 * @param runtimeMs       wall-clock since the session began.
 * @param costUsdMilli    USD × 1000; same convention as
 *                        {@link Task#costUsdMilli()}.
 * @param toolCallCount   total {@code ToolCallStarted} events seen.
 * @param filesTouched    distinct paths in the session's
 *                        {@code task_files} rows.
 */
public record AgentMetrics(
        long runtimeMs,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        int toolCallCount,
        int filesTouched)
{
    public static AgentMetrics empty()
    {
        return new AgentMetrics(0L, 0L, 0L, 0L, 0, 0);
    }
}
