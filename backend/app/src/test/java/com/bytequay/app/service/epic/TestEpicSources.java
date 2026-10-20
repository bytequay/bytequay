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
package com.bytequay.app.service.epic;

import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestEpicSources
{
    private final IssueDevelopmentStore markers = mock(IssueDevelopmentStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final JiraService jira = mock(JiraService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JiraService> tickets = mock(ObjectProvider.class);
    private final WorkItemStore items = mock(WorkItemStore.class);
    private final InMemoryEpicStore records = new InMemoryEpicStore();
    private final EpicSources epics = new EpicSources(markers, workspaces, tickets, items, records);

    @BeforeEach
    void setUp()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("TrinoDB", "Trino"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(tickets.getObject()).thenReturn(jira);
    }

    @Test
    void anIssueIsAnEpicOfTheWorkspaceThatMonitorsItsRepositoryWhileItIsMarked()
    {
        when(markers.findEpic("github.com", 41)).thenReturn(Optional.of(new IssueEpic("github.com", 41, "trinodb/trino", 7, true, Instant.EPOCH)));
        when(markers.findEpic("github.com", 42)).thenReturn(Optional.of(new IssueEpic("github.com", 42, "trinodb/trino", 8, false, Instant.EPOCH)));
        when(markers.findEpic("github.com", 43)).thenReturn(Optional.of(new IssueEpic("github.com", 43, "other/repo", 9, true, Instant.EPOCH)));
        assertThat(epics.marked("workspace", WorkItemLink.issueIdentity("github.com", 41))).isTrue();
        assertThat(epics.marked("workspace", WorkItemLink.issueIdentity("github.com", 42))).as("unmarked").isFalse();
        assertThat(epics.marked("workspace", WorkItemLink.issueIdentity("github.com", 43))).as("another repository's").isFalse();
        assertThat(epics.marked("workspace", WorkItemLink.issueIdentity("github.com", 44))).as("never marked").isFalse();
        assertThatThrownBy(() -> epics.require("workspace", WorkItemLink.issueIdentity("github.com", 42))).hasMessageContaining("Epic not found");
    }

    @Test
    void aTicketIsAnEpicOfTheWorkspaceItIsAssignedToWhileItIsMarked()
    {
        String cloud = "a:b-cloud";
        when(jira.isEpic(cloud, "10")).thenReturn(true);
        when(jira.assignedWorkspace(cloud, "10")).thenReturn(Optional.of("workspace"));
        when(jira.isEpic(cloud, "11")).thenReturn(true);
        when(jira.assignedWorkspace(cloud, "11")).thenReturn(Optional.of("other"));
        assertThat(epics.marked("workspace", WorkItemLink.jiraIdentity(cloud, "10"))).as("a cloud id may contain colons").isTrue();
        assertThat(epics.marked("workspace", WorkItemLink.jiraIdentity(cloud, "11"))).as("assigned elsewhere").isFalse();
        assertThat(epics.marked("workspace", WorkItemLink.jiraIdentity(cloud, "12"))).as("unmarked").isFalse();
        assertThatThrownBy(() -> epics.marked("workspace", "TASK:task-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> epics.marked("workspace", "JIRA:40:cloud:10")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLocalEpicNeedsBothItsWorkItemAndMarkerInTheSameWorkspace()
    {
        WorkItem item = new WorkItem("work-1", "workspace", "Local Epic", WorkItem.Kind.DEVELOP, "Preserve private work",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), Instant.EPOCH, Instant.EPOCH,
                WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        when(items.find(item.id())).thenReturn(Optional.of(item));
        String epicId = EpicSources.localEpicId(item.id());
        assertThat(epics.marked("workspace", epicId)).as("an ordinary local item is not an Epic").isFalse();

        records.save(Epic.of(epicId, "workspace"));
        assertThat(epics.marked("workspace", epicId)).isTrue();
        assertThat(epics.marked("other", epicId)).as("another workspace cannot use the Epic").isFalse();
        records.save(Epic.of(epicId, "other"));
        assertThat(epics.marked("workspace", epicId)).as("the marker must belong to the item's workspace too").isFalse();
        records.save(Epic.of("LOCAL:missing", "workspace"));
        assertThat(epics.marked("workspace", "LOCAL:missing")).as("a marker cannot outlive its source item").isFalse();
        assertThat(EpicSources.localId(epicId)).contains(item.id());
        assertThat(EpicSources.localId(WorkItemLink.issueIdentity("github.com", 41))).isEmpty();
        assertThat(epics.marked("workspace", "LOCAL:")).isFalse();
    }
}
