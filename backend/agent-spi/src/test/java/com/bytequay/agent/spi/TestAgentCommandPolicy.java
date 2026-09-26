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
    private static final String DEVELOPMENT_POLICY = AgentCommandPolicy.instructions(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false));

    @Test
    void modelAndReviewerInstructionsShareEveryRestriction()
    {
        assertThat(AgentCommandPolicy.restrictions()).isNotEmpty().allSatisfy(restriction -> {
            assertThat(restriction).startsWith("Block ");
            assertThat(DEVELOPMENT_POLICY).contains(restriction);
        });
        assertThatThrownBy(() -> AgentCommandPolicy.restrictions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(DEVELOPMENT_POLICY).contains(
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
                "native denials still apply",
                "reviewer approves it",
                "ByteQuay checks",
                "requests it receives");
        assertThat(DEVELOPMENT_POLICY).startsWith("## ByteQuay Development Scope").doesNotContain("Automatic Approval Scope", "sandbox weakening");
        assertThat(AgentCommandPolicy.reviewInstructions()).startsWith("## ByteQuay Private Review Scope").doesNotContain("Automatic Approval Scope");
    }

    @Test
    void publicationToolOwnershipDoesNotForbidAuthorizedCliWork()
    {
        assertThat(DevelopmentResultBridge.INSTRUCTION).contains(
                "readyForDelivery=false before asking for an unresolved decision",
                "Missing or invalid reports never authorize delivery",
                "During ByteQuay's generated draft-publication step",
                "use the tool without duplicating its writes",
                "explicitly requested git or gh publishing follows the shared command authorization",
                "Its refusal does not block an authorized CLI operation");
        assertThat(DevelopmentResultBridge.INSTRUCTION).doesNotContain("never run git push", "never ask\nfor credentials");
    }
}
