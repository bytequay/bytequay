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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentCommandPolicy
{
    @Test
    void authenticatedCommandsAreLimitedToAttendedDevelopment()
    {
        assertThat(AgentPermissionPolicy.AUTO.allowsGitHubCommands(false, false)).isTrue();
        assertThat(AgentPermissionPolicy.AUTO.allowsGitHubCommands(true, false)).isFalse();
        assertThat(AgentPermissionPolicy.AUTO.allowsGitHubCommands(false, true)).isFalse();
        assertThat(AgentPermissionPolicy.AUTO.allowsGitHubCommands(true, true)).isFalse();
        for (AgentPermissionPolicy policy : new AgentPermissionPolicy[] {AgentPermissionPolicy.CI_AUTOFIX, AgentPermissionPolicy.CHERRY_PICK}) {
            assertThat(policy.allowsGitHubCommands(false, false)).isFalse();
            assertThat(policy.allowsGitHubCommands(true, false)).isFalse();
            assertThat(policy.allowsGitHubCommands(false, true)).isFalse();
            assertThat(policy.allowsGitHubCommands(true, true)).isFalse();
        }
    }

    @Test
    void modelAndReviewerInstructionsShareEveryRestriction()
    {
        assertThat(AgentCommandPolicy.restrictions()).isNotEmpty().allSatisfy(restriction -> {
            assertThat(restriction).startsWith("Block ");
            assertThat(AgentCommandPolicy.instructions()).contains(restriction);
        });
        assertThatThrownBy(() -> AgentCommandPolicy.restrictions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(AgentCommandPolicy.instructions()).contains(
                "Git and gh are available for attended development",
                "subscription or API key",
                "direct explicit user authorization",
                "earlier request in the same task",
                "accepted automation settings",
                "Narrower user instructions",
                "exact destination and outgoing payload",
                "expected remote revision",
                "whole shell chain",
                "without exposing their values",
                "native denials still apply");
    }
}
