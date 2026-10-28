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
package com.bytequay.app.assistant;

import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.attention.AttentionMarkService;
import com.bytequay.app.service.epic.EpicService;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantDraft;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.agenda.AgendaReader.clip;
import static java.util.Objects.requireNonNull;

/**
 * What the assistant's work operations do once its checks pass: mark an Epic, add work to one, create a work item, and acknowledge or
 * snooze a Needs attention item. Each goes through the service the app's own buttons use, and all of it stays local: nothing here asks
 * GitHub or Jira.
 */
@Component
class AssistantWork
{
    private static final int TITLE = 80;

    private final IssueDevelopmentService issues;
    private final JiraService jira;
    private final EpicService epics;
    private final WorkItemService work;
    private final WorkItemStore items;
    private final WorkspaceStore workspaces;
    private final AssistantRows rows;
    private final AttentionMarkService marks;

    AssistantWork(IssueDevelopmentService issues, JiraService jira, EpicService epics, WorkItemService work, WorkItemStore items, WorkspaceStore workspaces, AssistantRows rows,
            AttentionMarkService marks)
    {
        this.issues = requireNonNull(issues, "issues is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.epics = requireNonNull(epics, "epics is null");
        this.work = requireNonNull(work, "work is null");
        this.items = requireNonNull(items, "items is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.rows = requireNonNull(rows, "rows is null");
        this.marks = requireNonNull(marks, "marks is null");
    }

    /**
     * What an operation left.
     *
     * @param line the receipt's line
     * @param detail a second line, such as where new work lives
     * @param target where the receipt opens
     * @param result what the action log keeps: the changed item's ref or id
     */
    record Done(String line, Optional<String> detail, Optional<AgendaTarget> target, String result) {}

    /** Marks an issue, a ticket or a work item as an Epic, as its page's Mark as Epic does. */
    Done markEpic(String item)
    {
        switch (AgendaRefs.kind(item)) {
            case "issue" -> {
                AgendaRefs.Numbered numbered = AgendaRefs.numbered(item).orElseThrow(() -> new IllegalArgumentException("That issue ref is not valid"));
                issues.setEpic(watched(numbered.repo()).id(), numbered.number(), true);
            }
            case "jira" -> {
                JiraIssue ticket = ticket(item);
                jira.epic(ticket.connectionId(), ticket.cloudId(), ticket.id(), true);
            }
            case "work" -> {
                WorkItem marked = items.find(AgendaRefs.id(item)).orElseThrow(() -> new IllegalArgumentException("That work item is gone"));
                epics.setLocalEpic(marked.workspaceId(), marked.id(), true);
            }
            default -> throw new IllegalArgumentException("Only an issue, a ticket or a work item can be an Epic");
        }
        Optional<AssistantRow> row = row(item);
        return new Done("Marked “" + row.map(found -> clip(found.title(), TITLE)).orElse(item) + "” as an Epic", Optional.empty(), row.map(AssistantRow::target), item);
    }

    /** Puts work in an Epic, as the Epic's Add work does: the work that already owns the item joins, or new work takes its title. */
    Done addToEpic(String epic, String item)
    {
        String epicId = AgendaRefs.id(epic);
        Workspace workspace = rows.epicWorkspace(epicId).orElseThrow(() -> new IllegalArgumentException("That Epic is gone"));
        WorkItemService.EpicJoin join = switch (AgendaRefs.kind(item)) {
            case "work" -> work.joinLocalEpic(workspace.id(), epicId, AgendaRefs.id(item));
            case "issue" -> work.joinEpic(workspace.id(), epicId, GoalItemKind.ISSUE, numberedKey(item));
            case "pr" -> work.joinEpic(workspace.id(), epicId, GoalItemKind.PULL_REQUEST, numberedKey(item));
            case "task" -> work.joinEpic(workspace.id(), epicId, GoalItemKind.TASK, AgendaRefs.id(item));
            case "jira" -> {
                JiraIssue ticket = ticket(item);
                yield work.joinEpic(workspace.id(), epicId, GoalItemKind.JIRA, ticket.connectionId() + ":" + ticket.cloudId() + ":" + ticket.id());
            }
            default -> throw new IllegalArgumentException("Work joins an Epic from a work, issue, jira, pr or task ref");
        };
        WorkItemView added = join.item();
        String into = row(epic).map(found -> clip(found.title(), TITLE)).orElse("its Epic");
        return new Done("Added “" + clip(added.title(), TITLE) + "” to " + into, join.movedFrom().map(ignored -> "Moved from another Epic"),
                Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, workspace.id(), added.id())), added.id());
    }

    /** Creates a work item titled with the user's words, in an Epic or in a watched repository, with the defaults the Epic page gives new work. */
    Done createWorkItem(Optional<String> epic, Optional<String> repository, String title, String id)
    {
        Optional<String> epicId = epic.map(AgendaRefs::id);
        Workspace workspace = (epicId.isPresent() ? rows.epicWorkspace(epicId.orElseThrow()) : workspaces.findById(repository.orElseThrow().substring("ws:".length())))
                .orElseThrow(() -> new IllegalArgumentException(epicId.isPresent() ? "That Epic is gone" : "That repository is not watched any more"));
        WorkItemView created = epicId.isPresent()
                ? work.createInEpic(workspace.id(), id, epicId.orElseThrow(), title, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY)
                : work.create(workspace.id(), id, title, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY);
        ImmutableList.Builder<String> where = ImmutableList.<String>builder().add(workspace.monitoredRepository().fullName());
        epic.flatMap(this::row).ifPresent(found -> where.add(clip(found.title(), TITLE)));
        return new Done("Created “" + clip(created.title(), TITLE) + "”", Optional.of(String.join(" · ", where.build())),
                Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, workspace.id(), created.id())), created.id());
    }

    /** Acknowledges a Needs attention item until something new happens to it, as its row's Acknowledge does. */
    Done acknowledge(String item)
    {
        String key = rows.attentionKey(item).orElseThrow(() -> new IllegalArgumentException("That isn't in Needs attention"));
        marks.acknowledge(key);
        Optional<AssistantRow> row = row(item);
        return new Done("Acknowledged “" + row.map(found -> clip(found.title(), TITLE)).orElse(item) + "”", Optional.of("Back when something new happens"),
                row.map(AssistantRow::target), key);
    }

    /** Snoozes a Needs attention item to the end of one of its row's snooze choices. */
    Done snooze(String item, AttentionMarkService.Snooze snooze)
    {
        String key = rows.attentionKey(item).orElseThrow(() -> new IllegalArgumentException("That isn't in Needs attention"));
        marks.snooze(key, snooze);
        Optional<AssistantRow> row = row(item);
        String until = switch (snooze) {
            case HOUR -> "for an hour";
            case TOMORROW -> "until tomorrow at 9:00";
            case MONDAY -> "until Monday at 9:00";
        };
        return new Done("Snoozed “" + row.map(found -> clip(found.title(), TITLE)).orElse(item) + "” " + until, Optional.empty(), row.map(AssistantRow::target), key);
    }

    /**
     * The receipt for a draft the user created with its own button: the Epic it became, and the GitHub issue it was published as.
     *
     * @throws IllegalArgumentException when the draft's work item was not created
     */
    AssistantItem drafted(AssistantDraft draft, String conversationId, Instant at)
    {
        WorkItem created = items.find(draft.workItemId()).orElseThrow(() -> new IllegalArgumentException("That draft was not created yet"));
        Optional<String> issue = created.links().stream().map(link -> link.reference()).filter(reference -> reference.kind() == GoalItemKind.ISSUE)
                .map(GoalItem::key).findFirst();
        AssistantReceipt receipt = new AssistantReceipt(AssistantReceipt.Operation.CREATE_EPIC, AssistantReceipt.Outcome.DONE, Optional.of(issue.orElse(draft.repository())),
                Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, created.workspaceId(), created.id())), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return AssistantItem.receipt(conversationId, at, "Created “" + clip(created.title(), TITLE) + "” as an Epic", receipt);
    }

    /** What the item is called and where it opens, read now from the stores. */
    private Optional<AssistantRow> row(String ref)
    {
        return Optional.ofNullable(rows.rows(ImmutableList.of(ref)).get(ref));
    }

    private Workspace watched(String repo)
    {
        return workspaces.findAll().stream().filter(Workspace::isGitHub).filter(workspace -> workspace.monitoredRepository().fullName().equalsIgnoreCase(repo)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That repository is not watched"));
    }

    private JiraIssue ticket(String ref)
    {
        List<String> parts = Splitter.on(':').limit(2).splitToList(AgendaRefs.id(ref));
        if (parts.size() != 2) {
            throw new IllegalArgumentException("That Jira ref is not valid");
        }
        return jira.cachedIssue(parts.get(0), parts.get(1)).orElseThrow(() -> new IllegalArgumentException("That ticket is not cached; open it in Jira first"));
    }

    private static String numberedKey(String ref)
    {
        AgendaRefs.Numbered numbered = AgendaRefs.numbered(ref).orElseThrow(() -> new IllegalArgumentException("That ref has no number"));
        return GoalItem.numberedKey(numbered.repo(), numbered.number());
    }
}
