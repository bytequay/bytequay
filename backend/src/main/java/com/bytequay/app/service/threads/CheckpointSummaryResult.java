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
package com.bytequay.app.service.threads;

import java.util.List;

/**
 * Output of a single Anthropic call inside the checkpoint pipeline.
 * Keeps the LLM concerns (usage / cost) out of {@code ThreadCheckpoint}
 * so the scheduler can stitch them with the message-range metadata
 * (firstSeq / lastSeq / tokensCovered) before persisting.
 */
public record CheckpointSummaryResult(
        String summaryMd,
        List<String> bulletTitles,
        String modelUsed,
        long promptTokens,
        long completionTokens,
        long costUsdMilli)
{
}
