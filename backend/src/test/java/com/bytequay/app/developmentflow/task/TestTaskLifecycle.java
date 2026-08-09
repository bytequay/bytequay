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
package com.bytequay.app.developmentflow.task;

import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestTaskLifecycle
{
    @Test
    void exposesOnlyTheLockedEdges()
    {
        Map<TaskLifecycle, Set<TaskLifecycle>> expected = Map.ofEntries(
                Map.entry(TaskLifecycle.PROVISIONING, ImmutableSet.of(TaskLifecycle.ACTIVE)),
                Map.entry(TaskLifecycle.ACTIVE, ImmutableSet.of(
                        TaskLifecycle.PAUSING,
                        TaskLifecycle.ARCHIVING,
                        TaskLifecycle.CANCELING,
                        TaskLifecycle.CLEANING)),
                Map.entry(TaskLifecycle.PAUSING, ImmutableSet.of(TaskLifecycle.PAUSED)),
                Map.entry(TaskLifecycle.PAUSED, ImmutableSet.of(
                        TaskLifecycle.RESUMING,
                        TaskLifecycle.CANCELING,
                        TaskLifecycle.CLEANING)),
                Map.entry(TaskLifecycle.RESUMING, ImmutableSet.of(TaskLifecycle.ACTIVE)),
                Map.entry(TaskLifecycle.CANCELING, ImmutableSet.of(TaskLifecycle.CLEANING)),
                Map.entry(TaskLifecycle.CLEANING, ImmutableSet.of(
                        TaskLifecycle.CANCELED,
                        TaskLifecycle.COMPLETED,
                        TaskLifecycle.REMOTE_CLOSED)),
                Map.entry(TaskLifecycle.CANCELED, ImmutableSet.of()),
                Map.entry(TaskLifecycle.ARCHIVING, ImmutableSet.of(TaskLifecycle.ARCHIVED)),
                Map.entry(TaskLifecycle.ARCHIVED, ImmutableSet.of(
                        TaskLifecycle.RESUMING,
                        TaskLifecycle.CANCELING,
                        TaskLifecycle.CLEANING)),
                Map.entry(TaskLifecycle.COMPLETED, ImmutableSet.of()),
                Map.entry(TaskLifecycle.REMOTE_CLOSED, ImmutableSet.of()));

        for (TaskLifecycle source : TaskLifecycle.values()) {
            for (TaskLifecycle target : TaskLifecycle.values()) {
                assertThat(source.allows(target))
                        .as("%s -> %s", source, target)
                        .isEqualTo(expected.get(source).contains(target));
            }
        }
    }

    @Test
    void onlyOutcomeStatesAreTerminal()
    {
        assertThat(ImmutableSet.copyOf(TaskLifecycle.values()).stream()
                .filter(TaskLifecycle::isTerminal))
                .containsExactlyInAnyOrder(
                        TaskLifecycle.CANCELED,
                        TaskLifecycle.COMPLETED,
                        TaskLifecycle.REMOTE_CLOSED);
    }
}
