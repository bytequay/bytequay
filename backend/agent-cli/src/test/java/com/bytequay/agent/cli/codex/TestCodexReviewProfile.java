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
package com.bytequay.agent.cli.codex;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review tools reach the App Server on the task's shared token, which carries no per-caller
 * identity. Root-only access is therefore a launch-profile property, not a prompt instruction.
 */
class TestCodexReviewProfile
{
    @Test
    void reviewSessionsLaunchWithNoChildAgents()
    {
        for (CodexLaunchProfile.ChildProfile configured : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexCliDriver.childProfile(configured, false, true))
                    .as("a review configured for %s", configured)
                    .isEqualTo(CodexLaunchProfile.ChildProfile.NONE);
        }
        assertThat(CodexLaunchProfile.arguments(
                "codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, ImmutableList.of(), false, false))
                .containsSequence("--disable", "multi_agent")
                .containsSequence("--disable", "multi_agent_v2");
    }

    @Test
    void anOrdinaryTaskKeepsTheProfileItsModelAsksFor()
    {
        for (CodexLaunchProfile.ChildProfile configured : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexCliDriver.childProfile(configured, false, false)).isEqualTo(configured);
            // A text-only task has no way to show child activity, so it keeps the existing opt-out.
            assertThat(CodexCliDriver.childProfile(configured, true, false))
                    .isEqualTo(CodexLaunchProfile.ChildProfile.NONE);
        }
    }
}
