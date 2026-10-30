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
package com.bytequay.app.agent;

import org.junit.jupiter.api.Test;

import static com.bytequay.app.agent.ContributeCommand.BRIEF;
import static com.bytequay.app.agent.ContributeCommand.expand;
import static org.assertj.core.api.Assertions.assertThat;

class TestContributeCommand
{
    @Test
    void replacesLeadingCommandWithBriefAndKeepsTheUsersWords()
    {
        assertThat(expand("@contribute")).isEqualTo(BRIEF.strip());
        assertThat(expand("  @Contribute  look at the Iceberg connector")).isEqualTo(BRIEF + "\nlook at the Iceberg connector");
    }

    @Test
    void leavesOtherMessagesAlone()
    {
        assertThat(expand("Fix the flaky test")).isEqualTo("Fix the flaky test");
        assertThat(expand("Please @contribute here")).isEqualTo("Please @contribute here");
        assertThat(expand("@contributed nothing")).isEqualTo("@contributed nothing");
    }
}
