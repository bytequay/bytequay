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
package com.bytequay.app.developmentflow.trunk;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestTrunkLifecycle
{
    @Test
    void exposesOnlyTheLockedEdges()
    {
        Map<TrunkLifecycle, Set<TrunkLifecycle>> expected = Map.of(
                TrunkLifecycle.ACTIVE,
                Set.of(TrunkLifecycle.IDLE, TrunkLifecycle.ARCHIVED),
                TrunkLifecycle.IDLE,
                Set.of(TrunkLifecycle.ACTIVE, TrunkLifecycle.ARCHIVED),
                TrunkLifecycle.ARCHIVED,
                Set.of());

        for (TrunkLifecycle source : TrunkLifecycle.values()) {
            for (TrunkLifecycle target : TrunkLifecycle.values()) {
                assertThat(source.allows(target))
                        .as("%s -> %s", source, target)
                        .isEqualTo(expected.get(source).contains(target));
            }
        }
    }
}
