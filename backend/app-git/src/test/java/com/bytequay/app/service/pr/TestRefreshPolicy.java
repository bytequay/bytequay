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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TestRefreshPolicy
{
    @ParameterizedTest
    @MethodSource("refreshes")
    void reusesOnlyRefreshesThatMeetTheRequest(RefreshPolicy requested, RefreshPolicy running, boolean reusable)
    {
        assertThat(requested.canReuse(running)).isEqualTo(reusable);
    }

    static Stream<Arguments> refreshes()
    {
        return Stream.of(
                Arguments.of(RefreshPolicy.detail(), RefreshPolicy.detail(), true),
                Arguments.of(RefreshPolicy.forced(), RefreshPolicy.forced(), true),
                Arguments.of(RefreshPolicy.forced(), RefreshPolicy.detail(), false),
                Arguments.of(RefreshPolicy.detail(), RefreshPolicy.forced(), true),
                Arguments.of(RefreshPolicy.detail(), RefreshPolicy.checks(), false),
                Arguments.of(RefreshPolicy.checks(), RefreshPolicy.detail(), false),
                Arguments.of(RefreshPolicy.checks(), RefreshPolicy.forced(), true),
                Arguments.of(RefreshPolicy.opened(), RefreshPolicy.detail(), false),
                Arguments.of(RefreshPolicy.detail(), RefreshPolicy.opened(), true),
                Arguments.of(RefreshPolicy.detail(), RefreshPolicy.background(Duration.ofSeconds(60)), false),
                Arguments.of(RefreshPolicy.background(Duration.ofSeconds(60)), RefreshPolicy.detail(), true),
                Arguments.of(RefreshPolicy.background(Duration.ofMinutes(10)), RefreshPolicy.background(Duration.ofMinutes(5)), true),
                Arguments.of(RefreshPolicy.background(Duration.ofMinutes(5)), RefreshPolicy.background(Duration.ofMinutes(10)), false),
                Arguments.of(RefreshPolicy.checks(), new RefreshPolicy(Duration.ofSeconds(30), Duration.ofHours(1), true, false, false), true));
    }
}
