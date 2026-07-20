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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorkModelAgentLock
{
    private static final WorkModel CODEX =
            new WorkModel(WorkModelKind.CLI, "codex", "gpt-5.6-sol", null);

    @Test
    void lockedConversationAllowsOnlyModelsFromTheSameAgent()
    {
        assertThatCode(() -> WorkModelAgentLock.requireSameAgent(
                true, CODEX,
                new WorkModel(WorkModelKind.CLI, "codex", "gpt-5.6-terra", null)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> WorkModelAgentLock.requireSameAgent(
                true, CODEX,
                new WorkModel(WorkModelKind.CLI, "claude-code", "claude-sonnet-4-6", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("agent is locked after the first message");
    }
}
