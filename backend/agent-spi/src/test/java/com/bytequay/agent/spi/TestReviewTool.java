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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewTool
{
    @Test
    void readOnlySessionsCarryContextReadsAndOneRootOnlyArtifactSave()
    {
        ReviewTool context = tool("agenda_context", true);
        ReviewTool save = tool("artifact_save", false);
        assertThatCode(() -> ReviewTool.checkSessionTools(SessionAccess.READ_ONLY, ImmutableList.of(context, save))).doesNotThrowAnyException();
        // A child could otherwise overwrite the page the root agent is writing.
        assertThatThrownBy(() -> ReviewTool.checkSessionTools(SessionAccess.READ_ONLY, ImmutableList.of(tool("artifact_save", true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewTool.checkSessionTools(SessionAccess.READ_ONLY, ImmutableList.of(tool("assistant_act", false))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewTool.checkSessionTools(SessionAccess.TOOLS_ONLY, ImmutableList.of(save)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReviewTool tool(String name, boolean childCallable)
    {
        return new ReviewTool(name, "A tool", ImmutableMap.of("type", "object"), arguments -> "{}", childCallable);
    }
}
