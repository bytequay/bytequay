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
package com.bytequay.app.service.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestCavemanPrompt
{
    @Test
    void wrapsExistingInstructionsWithLiteAndPublishingSafeguards()
    {
        String prompt = CavemanPrompt.wrap("Existing output contract.");

        assertThat(prompt).contains("Respond terse like smart caveman");
        assertThat(prompt).contains("Use its lite intensity");
        assertThat(prompt).contains("structured tool/JSON contracts");
        assertThat(prompt).contains("commit subjects concise and\nself-explanatory");
        assertThat(prompt).endsWith("Existing output contract.");
    }
}
