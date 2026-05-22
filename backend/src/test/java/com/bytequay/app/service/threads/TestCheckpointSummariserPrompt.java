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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the load-bearing pieces of the workspace-memory prompt. The
 * back-link instruction in particular drives the chip rendering in
 * the frontend's {@code WorkspaceMemoryProposalBanner} — if it
 * silently fell out of the prompt, distilled memory would go back to
 * being a one-way digest with no path back to the source thread.
 */
class TestCheckpointSummariserPrompt
{
    @Test
    void workspaceMemoryPromptAsksHaikuToEmitBackLinkMarkers()
    {
        String prompt = CheckpointSummariser.WORKSPACE_MEMORY_SYSTEM_PROMPT;
        // The marker shape is fixed — both the prompt and the frontend
        // parser must agree on "[thread:<id>]". A drift in either
        // direction silently breaks chip rendering.
        assertThat(prompt).contains("[thread:<id>]");
        assertThat(prompt).contains("back-link");
        // Carry-forward case must be explicit so the model doesn't
        // hallucinate thread IDs for facts that came from the existing
        // workspace memory.
        assertThat(prompt).contains("leave it unmarked");
    }
}
