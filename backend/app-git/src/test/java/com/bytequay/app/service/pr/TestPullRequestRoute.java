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
package com.bytequay.app.service.pr;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPullRequestRoute
{
    @Test
    void namesAWorkspaceAnAccountOrNeither()
    {
        assertThat(PullRequestRoute.of("workspace", null)).isEqualTo(new PullRequestRoute(Optional.of("workspace"), OptionalLong.empty()));
        assertThat(PullRequestRoute.of(null, 11L)).isEqualTo(PullRequestRoute.account(11));
        assertThat(PullRequestRoute.of(" ", null)).isEqualTo(new PullRequestRoute(Optional.empty(), OptionalLong.empty()));
        assertThatThrownBy(() -> PullRequestRoute.of("workspace", 11L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A request is reached through a workspace or an account, not both");
    }
}
