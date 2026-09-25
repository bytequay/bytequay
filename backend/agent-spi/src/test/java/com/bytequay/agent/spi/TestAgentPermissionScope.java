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

class TestAgentPermissionScope
{
    @Test
    void derivesCommandAccessForEveryTaskScope()
    {
        assertScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, true, true, false, false);
        assertScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, true, false, true, true, false);

        for (SessionAccess access : new SessionAccess[] {SessionAccess.READ_ONLY, SessionAccess.TEXT_ONLY}) {
            for (boolean review : new boolean[] {false, true}) {
                assertScope(AgentPermissionPolicy.AUTO, access, review, false, false, false, false);
            }
        }
        for (AgentPermissionPolicy policy : new AgentPermissionPolicy[] {AgentPermissionPolicy.CI_AUTOFIX, AgentPermissionPolicy.CHERRY_PICK}) {
            for (SessionAccess access : SessionAccess.values()) {
                for (boolean review : new boolean[] {false, true}) {
                    assertScope(policy, access, review, false, false, false, true);
                }
            }
        }
    }

    private static void assertScope(
            AgentPermissionPolicy policy,
            SessionAccess access,
            boolean review,
            boolean githubCommands,
            boolean remoteReads,
            boolean reviewReads,
            boolean unattended)
    {
        AgentPermissionScope scope = new AgentPermissionScope(policy, access, review);
        assertThat(scope.githubCommands()).as("GitHub commands for %s / %s / review=%s", policy, access, review).isEqualTo(githubCommands);
        assertThat(scope.remoteReads()).as("remote reads for %s / %s / review=%s", policy, access, review).isEqualTo(remoteReads);
        assertThat(scope.reviewReads()).as("review reads for %s / %s / review=%s", policy, access, review).isEqualTo(reviewReads);
        assertThat(scope.buildNetwork()).as("build network for %s / %s / review=%s", policy, access, review)
                .isEqualTo(access == SessionAccess.FULL && policy != AgentPermissionPolicy.CHERRY_PICK);
        assertThat(scope.unattended()).as("unattended for %s / %s / review=%s", policy, access, review).isEqualTo(unattended);
        if (policy == AgentPermissionPolicy.CI_AUTOFIX) {
            assertThat(AgentCommandPolicy.instructions(scope)).contains("Never push", "Do not read or expose", "GitHub credentials");
        }
        else {
            assertThat(AgentCommandPolicy.instructions(scope))
                    .as("command guidance for %s / %s / review=%s", policy, access, review)
                    .isEqualTo(reviewReads ? AgentCommandPolicy.reviewInstructions() : githubCommands ? AgentCommandPolicy.instructions() : "");
        }
    }
}
