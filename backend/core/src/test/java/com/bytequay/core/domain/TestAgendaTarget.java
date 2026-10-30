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
package com.bytequay.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.bytequay.core.domain.AgendaTarget.Kind.ISSUE;
import static com.bytequay.core.domain.AgendaTarget.Kind.PULL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgendaTarget
{
    @Test
    void namesAnAccountOnlyForAPullRequestOutsideAWorkspace()
    {
        assertThat(target(PULL, Optional.empty()).accountId()).contains(11L);
        assertThatThrownBy(() -> target(PULL, Optional.of("workspace"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a pull request outside a workspace names an account");
        assertThatThrownBy(() -> target(ISSUE, Optional.of("workspace"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(AgendaTarget.pull("group/project", 4, Optional.empty()).accountId()).isEmpty();
    }

    private static AgendaTarget target(AgendaTarget.Kind kind, Optional<String> workspaceId)
    {
        return new AgendaTarget(kind, workspaceId, Optional.of("group/project"), Optional.of(4), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(11L));
    }
}
