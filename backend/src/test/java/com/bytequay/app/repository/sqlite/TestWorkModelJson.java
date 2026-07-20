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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestWorkModelJson
{
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reasoningEffortRoundTrips()
    {
        WorkModel selected = new WorkModel(
                WorkModelKind.CLI, "codex", "gpt-5.6-sol", null, "max");

        String stored = WorkModelJson.serialise(json, selected);

        assertThat(stored).contains("\"reasoningEffort\":\"max\"");
        assertThat(WorkModelJson.deserialise(json, stored)).isEqualTo(selected);
    }

    @Test
    void olderRowsWithoutReasoningEffortRemainReadable()
    {
        WorkModel restored = WorkModelJson.deserialise(json, """
                {"kind":"CLI","agentOrProvider":"codex","model":"gpt-5","account":null}
                """);

        assertThat(restored).isEqualTo(
                new WorkModel(WorkModelKind.CLI, "codex", "gpt-5", null, null));
    }
}
