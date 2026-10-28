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
package com.bytequay.app.agenda;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.agent.InteractiveAgentTaskStarter;
import com.bytequay.app.agent.InteractiveTaskStart;
import com.bytequay.app.agent.IssueSourceInput;
import com.bytequay.app.agent.JiraSourceInput;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Starts a task for a work item, an issue or a Jira ticket the way the item's own Start button does: with the item's own first message,
 * the repository's development model unless another agent is named, and the user's saved automation. An issue or a ticket that already
 * has a task keeps it, and the start returns that task.
 */
@Component
public class ItemTaskStarter
{
    /** The kinds of ref a task starts from. */
    public static final Set<String> SOURCES = ImmutableSet.of("work", "issue", "jira");
    // Twins of the composer's own first messages, in WorkItemsScreen.tsx and AgentTasksView.tsx; change them together.
    private static final String WORK_ITEM_START = "Develop this work item: %s\n\nCall read_issue_context before starting to read this item's full context and its "
            + "parent Epic, if any. Deliver only this work item.";
    private static final String ISSUE_START = "Implement %s";
    private static final String JIRA_START = "Implement %s: %s";

    private final InteractiveAgentTaskStarter starter;
    private final ModelSettingsService models;
    private final AppSettingsService settings;
    private final WorkspaceStore workspaces;
    private final WorkItemStore workItems;
    private final RepositoryIssueStore issues;
    private final JiraService jira;

    public ItemTaskStarter(
            InteractiveAgentTaskStarter starter,
            ModelSettingsService models,
            AppSettingsService settings,
            WorkspaceStore workspaces,
            WorkItemStore workItems,
            RepositoryIssueStore issues,
            JiraService jira)
    {
        this.starter = requireNonNull(starter, "starter is null");
        this.models = requireNonNull(models, "models is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.jira = requireNonNull(jira, "jira is null");
    }

    /** Where a new task starts, with what first message and from which source. */
    public record Start(String workspaceId, String message, Optional<IssueSourceInput> issue, Optional<JiraSourceInput> jira, Optional<String> workItemId)
    {
        public Start
        {
            requireNonNull(workspaceId, "workspaceId is null");
            requireNonNull(message, "message is null");
            requireNonNull(issue, "issue is null");
            requireNonNull(jira, "jira is null");
            requireNonNull(workItemId, "workItemId is null");
        }

        /** The same start with {@code text} after its first message. */
        public Start followedBy(String text)
        {
            return new Start(workspaceId, message + text, issue, jira, workItemId);
        }
    }

    /**
     * What a start began.
     *
     * @param task the task, or the one the item already had
     * @param fresh whether the start made the task rather than finding the item's own
     * @param agent the agent's name with the model it runs
     * @param automation what the task does on its own once a turn ends
     */
    public record Started(AgentTask task, boolean fresh, String agent, DevelopmentAutomation automation) {}

    /** The item's own first message, as its Start button sends it. */
    public Start item(String ref)
    {
        return switch (AgendaRefs.kind(ref)) {
            case "work" -> {
                WorkItem item = workItems.find(AgendaRefs.id(ref)).orElseThrow(() -> new IllegalArgumentException("That work item is gone"));
                yield new Start(item.workspaceId(), WORK_ITEM_START.formatted(item.title()), Optional.empty(), Optional.empty(), Optional.of(item.id()));
            }
            case "issue" -> {
                AgendaRefs.Numbered numbered = AgendaRefs.numbered(ref).orElseThrow(() -> new IllegalArgumentException("That issue ref is not valid"));
                Workspace workspace = workspaces.findAll().stream().filter(Workspace::isGitHub)
                        .filter(watched -> watched.monitoredRepository().fullName().equalsIgnoreCase(numbered.repo())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("That issue's repository is not watched"));
                RepositoryIssue issue = issues.find(workspace.monitoredRepositoryId(), numbered.number())
                        .orElseThrow(() -> new IllegalArgumentException("That issue is not loaded; refresh its repository's issues"));
                yield new Start(workspace.id(), ISSUE_START.formatted(issue.title()), Optional.of(new IssueSourceInput(issue.id(), issue.number(), issue.updatedAt(),
                        Optional.empty())), Optional.empty(), Optional.empty());
            }
            case "jira" -> {
                String[] parts = AgendaRefs.id(ref).split(":", 2);
                checkArgument(parts.length == 2, "That Jira ref is not valid");
                JiraIssue ticket = jira.cachedIssue(parts[0], parts[1]).orElseThrow(() -> new IllegalArgumentException("That ticket is not cached; open it in Jira first"));
                String workspace = ticket.workspace().orElseThrow(() -> new IllegalArgumentException("Assign that ticket to a repository first")).id();
                yield new Start(workspace, JIRA_START.formatted(ticket.key(), ticket.title()), Optional.empty(), Optional.of(new JiraSourceInput(ticket.connectionId(),
                        ticket.cloudId(), ticket.id(), ticket.key(), ticket.title(), ticket.body(), ticket.updatedAt(), Optional.empty())), Optional.empty());
            }
            default -> throw new IllegalArgumentException("A task starts from a work, issue or jira ref");
        };
    }

    /** A task with no item behind it: the user's own words, in a watched repository's workspace. */
    public Start freeText(String workspaceId, String words)
    {
        workspaces.findById(workspaceId).orElseThrow(() -> new IllegalArgumentException("That repository is not watched any more"));
        return new Start(workspaceId, words, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Starts it as {@code taskId}, with {@code key} for its first turn, so a start repeated after an unknown outcome finds what it began.
     * Empty when the agent is not ready.
     */
    public Optional<Started> start(Start start, Optional<String> agent, TaskId taskId, String key)
    {
        AgentModelSetting development = models.agentModel(AgentModelType.DEVELOPMENT, Optional.of(start.workspaceId()));
        String lane = agent.orElse(development.configId());
        Optional<ModelSettingsService.Provider> provider = models.get().providers().stream().filter(candidate -> candidate.id().equals(lane)).findFirst();
        if (provider.filter(ready -> ready.readiness() == AgentProviderStatus.Readiness.READY).isEmpty()) {
            return Optional.empty();
        }
        boolean usual = lane.equals(development.configId());
        Optional<String> model = Optional.ofNullable(usual ? development.model() : provider.orElseThrow().selectedModel()).filter(value -> !value.isBlank());
        Optional<String> effort = model.isEmpty() ? Optional.empty()
                : (usual ? development.effort() : Optional.ofNullable(provider.orElseThrow().selectedEffort())).filter(value -> !value.isBlank());
        DevelopmentAutomation automation = settings.developmentAutomation();
        AgentTask task = starter.start(new InteractiveTaskStart(taskId, start.workspaceId(), start.message(), new AgentLaneId(lane),
                new AgentTaskDefaults(model, effort, AgentPermissionPolicy.AUTO, Optional.empty(), Optional.of(automation)),
                key, Optional.of(automation), start.issue(), start.jira(), start.workItemId(), ImmutableList.of()));
        String name = provider.orElseThrow().label() + " " + (usual ? development.modelLabel() : model.orElse(""));
        return Optional.of(new Started(task, task.id().equals(taskId), name.strip(), automation));
    }
}
