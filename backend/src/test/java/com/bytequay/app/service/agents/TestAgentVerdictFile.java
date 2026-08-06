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
package com.bytequay.app.service.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentVerdictFile
{
    private final AgentVerdictFile verdicts = new AgentVerdictFile(new ObjectMapper());

    @Test
    void readsWhatTheAgentWrote(@TempDir Path worktree)
            throws Exception
    {
        write(worktree, "{\"status\":\"Resolved\",\"summary\":\"kept the fork's prefix\"}");

        AgentVerdictFile.Verdict verdict = verdicts.read(worktree).orElseThrow();

        // Status is matched lower-case so a capitalised write still lands.
        assertThat(verdict.status()).isEqualTo("resolved");
        assertThat(verdict.summary()).isEqualTo("kept the fork's prefix");
    }

    @Test
    void anythingUnusableReadsAsAbsentSoTheAgentIsAskedAgain(@TempDir Path worktree)
            throws Exception
    {
        assertThat(verdicts.read(worktree)).isEmpty();

        write(worktree, "not json at all");
        assertThat(verdicts.read(worktree)).isEmpty();

        // Half-written, or written without deciding anything.
        write(worktree, "{\"summary\":\"I did some things\"}");
        assertThat(verdicts.read(worktree)).isEmpty();
    }

    @Test
    void aStaleVerdictCanNeverBeReadAsThisTurns(@TempDir Path worktree)
            throws Exception
    {
        write(worktree, "{\"status\":\"resolved\",\"summary\":\"last turn\"}");

        verdicts.clear(worktree);

        // Without this, a turn that wrote nothing would inherit the previous
        // turn's answer — the worst possible failure for this contract.
        assertThat(verdicts.read(worktree)).isEmpty();
        assertThat(Files.exists(worktree.resolve(AgentVerdictFile.relativePath()))).isFalse();
    }

    @Test
    void clearingIsSafeWhenThereIsNothingToClear(@TempDir Path worktree)
    {
        verdicts.clear(worktree);
        verdicts.clear(worktree);
    }

    private static void write(Path worktree, String body)
            throws Exception
    {
        Path file = worktree.resolve(AgentVerdictFile.relativePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
