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
package com.bytequay.app.service.goal;

import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalService
{
    private final InMemoryGoalStore store = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final GoalService service = new GoalServiceImpl(store, workspaces, work, mock(GoalReferenceReader.class), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final GoalSource source = new GoalSource(GoalItemKind.ISSUE, "org/repo#1");

    @BeforeEach
    void setUp()
    {
        when(workspaces.findById("workspace")).thenReturn(Optional.of(mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE))));
        when(work.list("workspace")).thenReturn(ImmutableList.of());
    }

    @Test
    void unmarkAndArchiveRetainTheObjectiveAndWorkHistory()
    {
        service.seed("workspace", source, "Objective");
        service.seed("workspace", source, "Objective");
        assertThat(service.list("workspace")).hasSize(1);
        String id = service.list("workspace").getFirst().id();
        when(work.hasWork("workspace", id)).thenReturn(true);
        service.unseed(source);
        assertThat(service.list("workspace")).singleElement().satisfies(goal -> assertThat(goal.source()).isEmpty());
        assertThat(store.find(id).orElseThrow().unmarkedAt()).isPresent();
        service.delete("workspace", id);
        assertThat(service.list("workspace")).isEmpty();
        assertThat(store.find(id)).map(Goal::archived).contains(true);
    }

    @Test
    void markingAgainRestoresTheGoalThatKeptItsWork()
    {
        service.seed("workspace", source, "Objective");
        String id = service.list("workspace").getFirst().id();
        when(work.hasWork("workspace", id)).thenReturn(true);
        service.unseed(source);
        service.seed("workspace", source, "Objective");
        assertThat(service.list("workspace")).singleElement().satisfies(goal -> {
            assertThat(goal.id()).isEqualTo(id);
            assertThat(goal.source()).contains(source);
        });
    }

    @Test
    void markingAgainAfterUnmarkingAnEmptyGoalLeavesOneGoal()
    {
        service.seed("workspace", source, "Objective");
        service.unseed(source);
        assertThat(service.list("workspace")).isEmpty();
        service.seed("workspace", source, "Objective");
        service.unseed(source);
        service.seed("workspace", source, "Objective");
        assertThat(service.list("workspace")).singleElement().satisfies(goal -> assertThat(goal.pinned()).isTrue());
    }
}
