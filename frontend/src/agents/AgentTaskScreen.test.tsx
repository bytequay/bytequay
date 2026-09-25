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

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AgentTaskList, creationBlocker, groupTasksByDay, taskCreationCatalog } from './AgentTasksView';
import type { TaskDiscussions } from './discussions/useTaskDiscussions';
import { AgentTaskBody, providerStatusFromUnavailable, type AttachmentPreview } from './AgentTaskScreen';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence, type AgentEventEnvelope } from './events';
import { initialAgentTaskState, type AgentTaskState } from './model';
import { samePullRequest, taskPullRequest, type TaskPullRequestChanges } from './TaskPullRequestPanel';
import { displayModelName } from '../settings/model';
import { projectTranscript } from './transcript';
import { EMPTY_TIMELINE_PAYLOAD } from '../pulls/model';
import { agentTurn as turn, previewTask, TASK_BODY_NOOPS } from './fixtures/previewBridge';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import type {
  AgentCapabilitiesDto,
  PullRequestDetailDto,
  AgentProviderReadiness,
  AgentProviderStatus,
  AgentProviderUnavailableDto,
  AgentTaskDto,
  ModelOptionDto,
  ModelSettingsDto,
  PullRequestDto,
} from '../types';

const events = parseAgentEventSequence(fixture.events);

const CAPABILITIES: AgentCapabilitiesDto = {
  ...NO_AGENT_CAPABILITIES,
  structuredPlans: true,
  nestedAgentActivity: true,
  steering: true,
  attachmentMediaTypes: ['image/png', 'image/jpeg', 'image/gif'],
};

function providerStatus(readiness: AgentProviderReadiness, extra: Partial<AgentProviderStatus> = {}): AgentProviderStatus {
  return {
    laneId: 'claude',
    readiness,
    version: '2.1.259',
    diagnostic: 'provider readiness diagnostic',
    capabilities: CAPABILITIES,
    signInCommand: readiness === 'AUTHENTICATION_REQUIRED' ? 'CLAUDE_CONFIG_DIR="/provider-home" claude auth login' : null,
    ...extra,
  };
}

/** A catalog shaped like a probed, ready Codex lane. */
function readyCatalog(models: ModelOptionDto[]): ModelSettingsDto {
  return {
    agents: [
      { type: 'DEVELOPMENT', explicit: true, configId: 'codex', label: 'Codex', model: models[0]?.id ?? '', modelLabel: 'Model', effort: 'high' },
      { type: 'REVIEW', explicit: false, configId: 'codex', label: 'Codex', model: models[0]?.id ?? '', modelLabel: 'Model', effort: 'high' },
    ],
    providers: [{
      id: 'codex',
      label: 'Codex',
      readiness: 'READY',
      version: '0.152.1',
      diagnostic: '',
      models,
      selectedModel: models[0]?.id ?? '',
      selectedEffort: 'high',
      capabilities: CAPABILITIES,
    }],
    apiKeys: [],
    keyProviders: [],
  };
}

const MODELS: ModelOptionDto[] = [
  { id: 'gpt-5.6-codex', name: '5.6 Terra', description: 'Balanced', efforts: ['medium', 'high'], defaultEffort: 'high', inputModalities: ['text', 'image'] },
  { id: 'text-only', name: 'Text only', description: '', efforts: ['high'], defaultEffort: 'high', inputModalities: ['text'] },
];

/** Everything the list needs beyond its rows, so each test states only what it varies. */
const listProps = {
  laneId: 'codex',
  providers: readyCatalog(MODELS).providers,
  models: MODELS,
  selection: { model: 'gpt-5.6-codex', effort: 'high' as string | null },
  draft: 'Fix the flaky preview test',
  blocker: null as string | null,
  onDraftChange: () => {},
  onSelectModel: () => {},
  onSelectProvider: () => {},
  onCreate: () => {},
  onOpenTask: () => {},
  onOpenSettings: () => {},
};

function task(id: string, state: string, title: string): AgentTaskDto {
  return { ...previewTask('workspace-1', id), state, title };
}


/** Explicit open choices for tests inspecting a finished turn's log. */
function allTurnsOpen(state: Partial<AgentTaskState>): Record<string, boolean> {
  return Object.fromEntries(projectTranscript(state.events ?? []).map((turn) => [turn.turnId ?? `turn-${turn.ordinal}`, true]));
}

function body(
  state: Partial<AgentTaskState>,
  activityChoice: Record<string, boolean> = {},
  turnChoice: Record<string, boolean> = allTurnsOpen(state),
  previews: Record<string, AttachmentPreview> = {},
): string {
  return renderToStaticMarkup(
    <AgentTaskBody
      state={{ ...initialAgentTaskState(), capabilities: CAPABILITIES, ...state }}
      activityChoice={activityChoice}
      turnChoice={turnChoice}
      previews={previews}
      models={MODELS}
      {...TASK_BODY_NOOPS}
    />);
}

const css = readFileSync(resolve(process.cwd(), 'src/css/agents.css'), 'utf8');

/** The declarations of one exact selector, as `property: value` strings. */
function declarations(selector: string): string[] {
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} is missing from agents.css`).toBeGreaterThan(-1);
  return css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
    .split(';')
    .map(declaration => declaration.replace(/\/\*[^]*?\*\//g, '').trim())
    .filter(declaration => declaration !== '');
}

describe('issue development conversation', () => {
  const opening = events[0];
  if (opening.type !== 'user_message') throw new Error('Fixture must start with a user message');
  const rawPrompt = 'INTERNAL GENERATED PROVIDER PROMPT';
  const message = { ...opening, payload: { ...opening.payload, text: rawPrompt } };
  const source = {
    host: 'github.com', issueId: 23, number: 7, repo: 'upstream/app', url: 'https://github.com/upstream/app/issues/7',
    title: 'Fix upload timeout', body: '## Expected behavior\n\nPreserve the upload API.', updatedAt: '2026-09-17T00:00:00Z',
    initialTurnId: opening.turnId, instructions: 'Keep the **public API** stable.',
  };

  it.each([source.instructions, ''])('shows a compact source request without disclosing instructions (%s)', instructions => {
    const markup = body({ task: { ...task('t-1', 'OPEN', 'Issue development'), issueSource: { ...source, instructions } },
      connection: 'open', events: [message] });
    expect(markup).toContain('Start development');
    expect(markup).toContain('class="agt-issue-request"');
    expect(markup).toContain('Fix upload timeout');
    expect(markup).toContain('upstream/app#7');
    expect(markup).not.toContain(rawPrompt);
    expect(markup).toContain('flow.png');
    expect(markup.indexOf('agt-issue-attachment')).toBeGreaterThan(markup.indexOf('agt-transcript'));
    expect(markup.indexOf('agt-issue-attachment')).toBeLessThan(markup.indexOf('agt-compose-dock is-task'));
    expect(markup).not.toContain('Show request details');
    expect(markup).not.toContain('public API');
  });

  it('recognizes an old source response only from the confirmed first root turn', () => {
    const legacyTask: AgentTaskDto = { ...task('t-1', 'OPEN', 'Legacy issue development'), issueSource: { ...source, initialTurnId: null, instructions: '' } };
    const later = { ...message, eventId: 'later-request', sequence: message.sequence + 1, turnId: 'later-turn',
      payload: { ...message.payload, text: 'Keep this follow-up visible.' } };
    const firstPage: AgentTaskState['historyPages'][number] = { events: [message, later], beforeCursor: null, afterCursor: null, latestSequence: later.sequence };
    const markup = body({ task: legacyTask, connection: 'open', events: firstPage.events, historyPages: [firstPage] });
    expect(markup.match(/class="agt-issue-request"/g)).toHaveLength(1);
    expect(markup).not.toContain(rawPrompt);
    expect(markup).toContain('Keep this follow-up visible.');

    const partialPage = { ...firstPage, events: [later], beforeCursor: 'earlier-history' };
    const partial = body({ task: legacyTask, connection: 'open', events: partialPage.events, historyPages: [partialPage] });
    expect(partial).not.toContain('class="agt-issue-request"');
    expect(partial).toContain('Keep this follow-up visible.');
  });

  it('keeps ordinary input and other turns unchanged instead of guessing which text was generated', () => {
    expect(body({ task: task('t-1', 'OPEN', 'Normal task'), connection: 'open', events: [message] })).toContain(rawPrompt);
    const markup = body({ task: { ...task('t-1', 'OPEN', 'Other turn'), issueSource: { ...source, initialTurnId: 'different-turn' } },
      connection: 'open', events: [message] });
    expect(markup).toContain(rawPrompt);
    expect(markup).not.toContain('Start development');
  });
});

describe('agent task layout', () => {
  it('offers scrollable history without newer-activity navigation buttons', () => {
    const markup = body({
      task: task('t-1', 'OPEN', 'x'),
      connection: 'open',
      historyPages: [{ events: [], beforeCursor: 'older', afterCursor: 'newer', latestSequence: 10 }],
    });
    expect(markup).not.toContain('Scroll up');
    expect(markup).not.toContain('Loading earlier activity');
    expect(markup).toContain('aria-label="Task transcript" tabindex="0"');
    expect(markup).not.toContain('Load newer activity');
    expect(markup).not.toContain('Jump to latest');
  });

  it('keeps the new-task controls low and aligned without branch or creation-hint rows', () => {
    expect(declarations('.agt-compose.is-new-task')).toEqual(expect.arrayContaining([
      'display: flex',
      'min-height: 112px',
      'flex-direction: column',
      'border-color: #647181',
      'border-radius: 14px',
    ]));
    expect(declarations('.agt-compose.is-new-task .agt-compose__row')).toEqual(expect.arrayContaining([
      'margin-top: auto',
      'padding: 12px 10px 6px',
    ]));
    expect(declarations('.agt-compose.is-new-task .agt-compose__input')).toEqual(expect.arrayContaining([
      'font-size: 13px',
      'font-weight: 400',
    ]));
    expect(css).toMatch(/\.agt-compose\.is-new-task \.agt-compose__chip,[^}]*\.agt-compose\.is-new-task \.chev \{\s*font-size: 13px;\s*font-weight: 400;\s*color: var\(--text-1\)/s);
    expect(css).toMatch(/\.agt-compose\.is-new-task \.agt-compose__chip,[^}]*\.agt-compose\.is-new-task \.agt-send \{\s*transform: translateY\(2px\)/s);
    expect(declarations('.agt-compose.is-new-task .agt-attach')).toEqual(expect.arrayContaining([
      'font: inherit',
      'color: var(--text-1)',
      'font-size: 24px',
      'font-weight: 300',
      'transform: translateY(-2px)',
    ]));
    expect(css).toMatch(/^\.agt-send \{[^}]*font-size: 18px;[^}]*font-weight: 400/m);
    expect(declarations('.agt-compose__row')).toContain('align-items: center');
    expect(declarations('.agt-attach')).not.toContain('align-self: flex-end');

    const markup = renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />);
    expect(markup).not.toContain('Branch the task starts from');
    expect(markup).toContain('placeholder="Let&#x27;s ship..."');
  });

  it('keeps the composer controls on one line and truncates labels in a narrow pane', () => {
    expect(css).not.toMatch(/\.agt-compose-dock\.is-task \.agt-compose__row \{[^}]*flex-wrap: wrap/);
    // A zero basis makes the automation summary give way before the model picker loses a glyph.
    expect(declarations('.agt-compose-dock.is-task .agt-automation')).toEqual(expect.arrayContaining([
      'flex: 1 1 0',
      'max-width: max-content',
    ]));
    expect(declarations('.agt-compose-dock.is-task .agt-automation summary')).toContain('text-overflow: ellipsis');
    expect(declarations('.agt-compose-dock.is-task .agt-picker__chip .agt-picker__effort')).toContain('text-overflow: ellipsis');
    // A growing spacer would split the free space with the summary and truncate it while room remains.
    expect(declarations('.agt-compose-dock.is-task .agt-picker')).toContain('margin-left: auto');
    const markup = body({ connection: 'open' });
    const row = markup.slice(markup.indexOf('class="agt-compose__row"'), markup.indexOf('aria-label="Send message"'));
    expect(row).toContain('agt-picker');
    expect(row).not.toContain('agt-spacer');
  });

  it('reads a held checkout as loading, not recovery, until the history has arrived', () => {
    const held = {
      ...task('t-1', 'OPEN', 'x'),
      mutationClaim: { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-08-20T10:00:00Z' },
    };
    const connecting = body({ task: held, connection: 'connecting' });
    expect(connecting).toContain('>Connecting<');
    expect(connecting).toContain('Connecting — loading task history…');
    expect(connecting).not.toContain('Could not verify shutdown');
    // Once the history is in and shows no live turn, the claim really does mean recovery.
    expect(body({ task: held, connection: 'open' })).toContain('Could not verify shutdown');
  });

  it('settles the final displayed turn when a terminal task lacks its old turn-end event', () => {
    const completed = {
      ...task('t-1', 'COMPLETED', 'Finished work'),
      terminalAt: '2026-08-20T10:00:12Z',
    };
    const withoutTurnEnd = events.filter((event) => event.type !== 'lifecycle' || event.payload.phase !== 'TURN_COMPLETED');
    const markup = body({ task: completed, events: withoutTurnEnd, connection: 'open' }, {}, {});

    expect(markup).toContain('Turn 1 · Worked for 11s');
    expect(markup).not.toContain('Working for');
    expect(markup).not.toContain('agt-meta');
  });

  it('positions the composer picker by surface, and names models the short way', () => {
    expect(declarations('.agt-picker__card')).toContain('bottom: calc(100% + 8px)');
    expect(declarations('.agt-picker__card')).toContain('border-radius: 14px');
    expect(declarations('.agt-home__composer .agt-picker__card')).toEqual(expect.arrayContaining([
      'top: calc(100% + 8px)',
      'bottom: auto',
    ]));
    expect(displayModelName('GPT-5.6-Sol')).toBe('5.6 Sol');
    expect(displayModelName('GPT-5.3-Codex-Spark')).toBe('5.3 Codex Spark');
    expect(displayModelName('Claude Opus 4.1')).toBe('Claude Opus 4.1');
    const codex = { ...task('t-1', 'OPEN', 'x'), defaults: { model: 'gpt-5.6-sol', effort: 'low', permissionPolicy: 'AUTO' } };
    const models: ModelOptionDto[] = [{ id: 'gpt-5.6-sol', name: 'GPT-5.6-Sol', description: 'Reliable.', efforts: ['low'], defaultEffort: 'low', inputModalities: ['text'] }];
    const markup = renderToStaticMarkup(
      <AgentTaskBody state={{ ...initialAgentTaskState(), task: codex, connection: 'open' }} models={models} {...TASK_BODY_NOOPS} />);
    expect(markup).toContain('5.6 Sol');
    expect(markup).not.toContain('GPT-5.6-Sol');
  });

  it('scrolls the transcript rather than the screen, so the composer stays reachable', () => {
    // A long transcript must move under a fixed header and composer, not push them off-screen.
    expect(declarations('.agt-screen')).toContain('height: 100%');
    expect(declarations('.agt-screen')).toContain('overflow: hidden');
    expect(declarations('.agt-transcript')).toContain('overflow-y: auto');
    expect(declarations('.agt-transcript')).toContain('flex: 1');
    // A flex child will not shrink below its content without this, which re-breaks the scroll.
    expect(declarations('.agt-transcript')).toContain('min-height: 0');
  });
});

describe('agent task list', () => {
  it('separates open from finished and names a reserved checkout', () => {
    const reserved = {
      ...task('t-3', 'OPEN', 'Stuck work'),
      mutationClaim: { kind: 'RECOVERY', ownerId: 'launch-1', acquiredAt: '2026-08-20T10:00:00Z' },
    };
    const markup = renderToStaticMarkup(
      <AgentTaskList
        {...listProps}
        tasks={[task('t-1', 'OPEN', 'Rename the widget'), task('t-2', 'COMPLETED', 'Old work'), reserved]}
        loading={false}
        error={null}
        />);

    // Asserting the separator too: a JSX text node does not interpret \\u escapes, so a
    // hand-written one renders literally and only an exact match catches it.
    // The design labels the section and lists the rows; it counts nothing.
    expect(markup).not.toContain('finished');
    expect(markup).toContain('Rename the widget');
    expect(markup).toContain('Describe a task');
    // Wire enums never reach the user.
    expect(markup).toContain('>Idle<');
    expect(markup).toContain('>Completed<');
    expect(markup).toContain('>Recovery required<');
    expect(markup).not.toContain('OPEN');
    // The list reads live data now; nothing may call it fixture data.
    expect(markup).not.toContain('Fixture data');
  });

  it('offers the ready provider and catalog model as real controls', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />);

    // Creation selects a real ready lane.
    expect(markup).toContain('Codex');
    expect(markup).toMatch(/<button[^>]*class="agt-compose__chip"[^>]*aria-label="Provider: Codex"/);
    expect(markup).toContain('5.6 Terra');
    expect(markup).toContain('High');
    // The one real choice, the model, is live.
    expect(markup).not.toMatch(/class="agt-picker__chip"[^>]*disabled/);
    expect(markup).not.toMatch(/class="agt-send"[^>]*disabled/);
  });

  it('refuses to offer creation when the provider has no model to run', () => {
    // The backend rejects a blank model, so an empty catalog must block the button, not the POST.
    const markup = renderToStaticMarkup(
      <AgentTaskList
        {...listProps}
        models={[]}
        selection={null}
        blocker={creationBlocker(readyCatalog([]), 'codex', null)}
        tasks={[]}
        loading={false}
        error={null}
      />);

    expect(markup).toMatch(/<button[^>]*class="agt-send"[^>]*disabled/);
    expect(markup).toContain('No Codex model is available yet');
    // The catalog is only fetched by a readiness check, so the copy must send them there.
    expect(markup).toContain('Workspace settings');
    expect(markup).toContain('also fetches its models');
    expect(markup).toContain('Open workspace settings');
  });

  it('links a provider that is not ready to the settings that fix it', () => {
    const catalog: ModelSettingsDto = {
      ...readyCatalog(MODELS),
      providers: [{ ...readyCatalog(MODELS).providers[0], readiness: 'AUTHENTICATION_REQUIRED' }],
    };

    const blocker = creationBlocker(catalog, 'codex', { model: 'gpt-5.6-codex', effort: 'high' });

    // The same typed readiness Workspace settings shows, with its remedy, not a generic failure.
    expect(blocker).toContain('Codex is not ready');
    expect(blocker).toContain('Sign in');
    expect(blocker).toContain('codex login');
    // A lane with its own home names the exact command, path included.
    const ownHome: ModelSettingsDto = {
      ...catalog,
      providers: [{ ...catalog.providers[0], signInCommand: 'CODEX_HOME="/x/codex-home" codex login' }],
    };
    expect(creationBlocker(ownHome, 'codex', { model: 'gpt-5.6-codex', effort: 'high' }))
      .toContain('CODEX_HOME="/x/codex-home" codex login');

    // A probe that names its obstacle is repeated verbatim, because the remedy alone is generic.
    const unavailable: ModelSettingsDto = {
      ...catalog,
      providers: [{
        ...catalog.providers[0],
        readiness: 'TEMPORARILY_UNAVAILABLE',
        diagnostic: 'exec-policy rules at ~/.codex/rules would be inherited',
      }],
    };
    expect(creationBlocker(unavailable, 'codex', { model: 'gpt-5.6-codex', effort: 'high' }))
      .toContain('exec-policy rules at ~/.codex/rules');
    // A catalog that has not arrived yet is loading, not an unchecked provider.
    expect(creationBlocker(null, 'codex', null)).toBeNull();
    expect(creationBlocker(readyCatalog(MODELS), 'codex', { model: 'gpt-5.6-codex', effort: 'high' })).toBeNull();
  });

  it('uses a saved-key remedy when a DeepSeek key is invalid or removed', () => {
    const laneId = 'deepseek-key-123';
    const catalog: ModelSettingsDto = {
      ...readyCatalog(MODELS),
      providers: [{ ...readyCatalog(MODELS).providers[0], id: laneId, label: 'DeepSeek · personal', readiness: 'AUTHENTICATION_REQUIRED',
        diagnostic: 'DeepSeek rejected this API key' }],
    };
    const blocked = creationBlocker(catalog, laneId, { model: 'deepseek-flash', effort: null });
    expect(blocked).toContain('Check the DeepSeek key');
    expect(blocked).toContain('DeepSeek rejected this API key');
    expect(blocked).not.toContain('codex login');
    expect(creationBlocker({ ...catalog, providers: [] }, laneId, null)).toContain('DeepSeek key removed');
  });

  it('offers DeepSeek to manual tasks but keeps issue-sourced starts on CLI lanes', () => {
    const codex = readyCatalog(MODELS).providers[0];
    const deepSeek = { ...codex, id: 'deepseek-key-123', label: 'DeepSeek · personal' };
    const catalog = { ...readyCatalog(MODELS), providers: [codex, deepSeek] };

    expect(taskCreationCatalog(catalog, null).providers.map(provider => provider.id)).toEqual(['codex', deepSeek.id]);
    expect(taskCreationCatalog(catalog, 42).providers.map(provider => provider.id)).toEqual(['codex']);
  });

  it('shows loading, empty and error states rather than an unexplained blank list', () => {
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading error={null} />))
      .toContain('Loading agent tasks\u2026');
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />))
      .toContain('No agent tasks yet');
    // A live list must not promise fixture behaviour.
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />))
      .not.toContain('never runs an agent');
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error="backend refused" />))
      .toContain('backend refused');
  });
});

/** A pull request whose diff already carries one review thread, for the panel's Changes tab. */
function reviewedDetail(): PullRequestDetailDto {
  return {
    id: 482,
    repo: 'bytequay/bytequay',
    number: 482,
    title: 'Fix preview output limit',
    htmlUrl: 'https://github.com/bytequay/bytequay/pull/482',
    state: 'open',
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    author: 'alice',
    authorAvatarUrl: null,
    createdAt: '2026-08-20T10:00:00Z',
    updatedAt: '2026-08-20T10:00:00Z',
    mergedAt: null,
    baseRef: 'main',
    headRef: 'fix-preview-output-limit',
    body: null,
    labels: [],
    assignees: [],
    milestone: null,
    reviewers: [],
    additions: 1,
    deletions: 0,
    changedFiles: 1,
    approvalCount: 0,
    ciStatus: null,
    recentActivity: [{
      actor: 'bob',
      eventType: 'review_thread',
      timestamp: '2026-08-20T10:30:00Z',
      body: null,
      githubId: 9,
      payload: { ...EMPTY_TIMELINE_PAYLOAD, path: 'src/Example.java', line: 2, side: 'RIGHT' },
    }],
    checkRuns: [],
    unavailableSections: [],
    viewerCanWrite: true,
  };
}

function pullRequest(number: number, title: string, state = 'open', draft = false): PullRequestDto {
  return {
    id: number,
    repo: 'bytequay/bytequay',
    number,
    title,
    author: 'alice',
    htmlUrl: `https://github.com/bytequay/bytequay/pull/${number}`,
    createdAt: '2026-08-20T10:00:00Z',
    updatedAt: '2026-08-20T10:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: null,
    additions: 0,
    deletions: 0,
    commentCount: 0,
    state,
    mergeable: null,
    mergeableState: null,
    headPushedAt: null,
    headRef: 'bytequay/task/t-1',
  };
}

describe('one arrow', () => {
  it('draws folds, rows and menu carets with the same chevron, turned when open', () => {
    const state = { task: task('t-1', 'OPEN', 'x'), events, connection: 'open' as const };
    const folded = body(state);
    // The activity fold and the turn head carry the arrow pointing right until opened.
    expect(folded).toMatch(/class="agt-act__fold"[^>]*>\s*<svg class="chev"/);
    const opened = body(state, Object.fromEntries(projectTranscript(events).flatMap((turn) => turn.blocks)
      .filter((block) => block.kind === 'activity').map((block) => [block.id, true])));
    expect(opened).toMatch(/class="agt-act__fold"[^>]*>\s*<svg class="chev is-open"/);
    // Menu carets are the same arrow, already turned down; nothing draws a text glyph.
    expect(folded).toContain('<button type="button" class="agt-picker__chip"');
    expect(folded).toMatch(/agt-picker__chip[^>]*>[^]*?<svg class="chev is-open"/);
    expect(folded).not.toMatch(/[›⌄]/);
    expect(renderToStaticMarkup(<AgentTaskList {...listProps} tasks={[task('t-1', 'OPEN', 'x')]} loading={false} error={null} />))
      .not.toMatch(/[›⌄]/);
  });
});

describe('agent prose', () => {
  it('renders an answer as the Markdown the agent wrote it in', () => {
    const answered = events.map((event) => (event.type === 'assistant_message' && event.payload.messageKind === 'ANSWER' && event.payload.phase === 'FINAL'
      ? { ...event, payload: { ...event.payload, text: 'The project has **109 Maven modules** in [pom.xml](/w/pom.xml:30).\n\n- 3 client\n- 8 core' } }
      : event));
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events: answered, connection: 'open' });
    expect(markup).toContain('<strong>109 Maven modules</strong>');
    expect(markup).toContain('<code title="/w/pom.xml:30">pom.xml</code>');
    expect(markup).toContain('<ul><li>3 client</li><li>8 core</li></ul>');
    expect(markup).not.toContain('**109');
  });
});

describe('truthful task controls', () => {
  it('shows no permission chip in either composer', () => {
    const list = renderToStaticMarkup(<AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />);
    const drafting = body({ task: task('t-1', 'OPEN', 'x'), turn: turn('RUNNING'), connection: 'open', composer: 'x' });
    expect(list).toContain('aria-label="Provider: Codex"');
    expect(list).not.toContain('>Auto<');
    expect(drafting).toContain('aria-label="Message the agent"');
    expect(drafting).not.toContain('>Auto<');
  });
});

describe('agent task side panel', () => {
  const props = {
    models: MODELS,
    onSelectModel: () => {},
    onBack: () => {},
    onSend: () => {},
    onCancel: () => {},
    onComplete: () => {},
    onDecide: () => {},
    onCompose: () => {},
    onStopServer: () => {},
    onMarkFailed: () => {},
  };
  const state = {
    ...initialAgentTaskState(),
    task: task('t-1', 'OPEN', 'x'),
    connection: 'open' as const,
    capabilities: CAPABILITIES,
  };

  it('offers Code & Tests on the task\'s pull request only while the task keeps its worktree', () => {
    const render = (extra: Partial<AgentTaskDto>) => renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...state, task: { ...state.task, interactive: true, checkoutRemovedAt: null, ...extra } }}
        panelOpen
        pullRequests={[pullRequest(482, 'Fix preview output limit')]}
        {...props}
      />);
    expect(render({})).toMatch(/<button[^>]*aria-pressed="false"[^>]*>[^]*?Code &amp; Tests/);
    expect(render({ checkoutRemovedAt: '2026-08-21T10:00:00Z' })).not.toContain('Code &amp; Tests');
    expect(render({ interactive: false })).not.toContain('Code &amp; Tests');
  });

  it('keeps Discussions beside the other tabs once the task has a pull request', () => {
    const discussions: TaskDiscussions = {
      discussions: [],
      error: null,
      disabledReason: null,
      refresh: async () => undefined,
      open: () => Promise.reject(new Error('This test never opens a discussion')),
      ask: async () => undefined,
      resolve: async () => undefined,
      retry: async () => undefined,
    };
    // The panel header is replaced when a task has a pull request, so a tab registered in that
    // header's own list would vanish here. This asserts it lives in the shared slot instead.
    const withPullRequest = renderToStaticMarkup(
      <AgentTaskBody state={state} panelOpen discussions={discussions}
        pullRequests={[pullRequest(482, 'Fix preview output limit')]} {...props} />);
    expect(withPullRequest).toMatch(/<button[^>]*aria-pressed="false"[^>]*>[^]*?Discussions/);

    const withoutPullRequest = renderToStaticMarkup(
      <AgentTaskBody state={state} panelOpen discussions={discussions} pullRequests={[]} {...props} />);
    expect(withoutPullRequest).toMatch(/<button[^>]*aria-pressed="false"[^>]*>[^]*?Discussions/);

    // No hook, no tab: a screen that never asked for discussions must not grow one.
    expect(renderToStaticMarkup(<AgentTaskBody state={state} panelOpen pullRequests={[]} {...props} />))
      .not.toContain('Discussions');
  });

  it('opens on the authored pull request with Changes and Subagents beside it', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskBody
        state={state}
        panelOpen
        pullRequests={[{ ...pullRequest(482, 'Fix preview output limit'), additions: 12, deletions: 3 }]}
        onOpenPullRequest={() => {}}
        {...props}
      />);
    expect(markup).toContain('aria-label="Show or hide the side panel"');
    expect(markup).toContain('aria-label="Pull request #482"');
    // Three tabs, the pull request first and selected without another click.
    expect(markup).toMatch(/<button[^>]*aria-pressed="true"[^>]*>[^]*?PR #482</);
    expect(markup).toMatch(/<button[^>]*aria-pressed="false"[^>]*>[^]*?Changes/);
    expect(markup).toMatch(/<button[^>]*aria-pressed="false"[^>]*>[^]*?Subagents/);
    expect(markup).toContain('>+12<');
    expect(markup).toContain('>−3<');
    // The panel uses the same PR toolbar, including refresh and the zoom action.
    expect(markup).toContain('aria-label="Zoom pull request details"');
    expect(markup).toContain('aria-label="Refresh pull request"');
    expect(markup).not.toContain('aria-label="Hide the side panel"');
    expect(markup).not.toContain('Agent review');
    expect(markup).not.toContain('Review &amp; Learning');
    expect(markup).toContain('title="Drag to resize the panel"');
    expect(markup).toContain('style="width:960px"');
    expect(markup).toContain('class="pull-detail__body"');
    // The tabs stay pinned while only the panel body scrolls, as in the approved design.
    expect(declarations('.agt-panel')).toContain('display: flex');
    expect(declarations('.agt-panel')).toContain('flex-direction: column');
    expect(declarations('.agt-panel__scroll')).toContain('overflow-y: auto');
    // The diff fills the frame instead of nesting inside a second scroller with its own padding.
    expect(declarations('.agt-panel__flush')).toContain('min-height: 0');
    const column = markup.match(/<section class="agt-task-column"[^>]*>([^]*?)<\/section>/)?.[1];
    expect(column).toContain('class="agt-screen-head"');
    expect(column).toContain('aria-label="Show or hide the side panel"');
    expect(column).not.toContain('class="agt-panel"');
    expect(declarations('.agt-task-column')).toContain('min-width: 0');
  });

  it('opens the task pull request from one chip above the composer', () => {
    const draft = [pullRequest(31105, 'Clean up nits in Delta Lake', 'open', true)];
    const chip = (extra: object = {}) => renderToStaticMarkup(
      <AgentTaskBody state={state} pullRequests={draft} {...extra} {...props} />);
    // GitHub's own pill on the composer: the state mark, PR, and the number, and nothing dated.
    const closed = chip();
    expect(closed).toMatch(/<button type="button" class="agt-pr-chip" aria-pressed="false" title="#31105 Clean up nits in Delta Lake">/);
    expect(closed).toContain('agt-pr-icon is-draft');
    expect(closed).toContain('class="agt-pr-chip__number">#31105<');
    expect(closed.indexOf('agt-pr-chip')).toBeLessThan(closed.indexOf('agt-compose__input'));
    expect(closed).not.toContain('Last checked');
    // It reads as pressed only where clicking it folds the panel away again.
    expect(chip({ panelOpen: true })).toContain('class="agt-pr-chip" aria-pressed="true"');
    expect(chip({ panelOpen: true, panelTab: 'changes' })).toContain('class="agt-pr-chip" aria-pressed="false"');
    // A branch without a pull request has nothing to open.
    expect(renderToStaticMarkup(<AgentTaskBody state={state} {...props} />)).not.toContain('agt-pr-chip');
    // The pill keeps its own width and ring; the dock's flex column would otherwise stretch it.
    expect(declarations('.agt-pr-chip')).toContain('align-self: flex-start');
    expect(declarations('.agt-pr-chip')).toContain('border-radius: 999px');
    expect(declarations('.agt-pr-chip:focus-visible')).toContain('outline: 2px solid var(--accent)');
  });

  it('keeps the subagent list unchanged under its own tab', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskBody state={state} panelOpen panelTab="agents" pullRequests={[]} {...props} />);
    expect(markup).toContain('aria-label="Subagents"');
    expect(markup).toMatch(/<button[^>]*aria-pressed="true"[^>]*>[^]*?Subagents/);
    expect(markup).toContain('No active subagents');
    // The tab reads Pull request, without a number, until the branch has one.
    expect(markup).toContain('Pull request<');
    expect(markup).not.toContain('No pull requests from this task yet');
  });

  it('keeps a background pull-request failure out of the transcript and subagents', () => {
    const pullRequestError = 'Could not refresh the branch pull request.';
    for (const panel of [{ panelOpen: false }, { panelOpen: true, panelTab: 'agents' as const }]) {
      const markup = renderToStaticMarkup(
        <AgentTaskBody state={state} pullRequestError={pullRequestError} {...panel} {...props} />);
      expect(markup).not.toContain(pullRequestError);
      expect(markup).not.toContain('class="agt-notice"');
    }
  });

  it.each(['pr', 'changes'] as const)('retains the cached pull request and task error after a failed lookup on %s', (panelTab) => {
    const pullRequestError = 'Could not refresh the branch pull request.';
    const markup = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...state, notice: 'The message could not be sent.' }}
        panelOpen panelTab={panelTab}
        pullRequests={[pullRequest(482, 'Fix preview output limit')]}
        pullRequestError={pullRequestError}
        onRefreshPullRequests={() => {}}
        {...props}
      />);
    expect(markup).toContain('PR #482');
    expect(markup).toContain(pullRequestError);
    expect(markup).toContain('>Retry</button>');
    expect(markup).toContain('<div class="agt-notice" role="alert">The message could not be sent.</div>');
    expect(markup).not.toContain('No pull request yet');
    expect(markup).not.toContain('this task&#x27;s branch has no pull request');
  });

  it.each(['pr', 'changes'] as const)('does not report an empty branch when the lookup failed on %s', (panelTab) => {
    const pullRequestError = 'Could not refresh the branch pull request.';
    const markup = renderToStaticMarkup(
      <AgentTaskBody
        state={state} panelOpen panelTab={panelTab} pullRequests={[]}
        pullRequestError={pullRequestError} pullRequestLoading
        onRefreshPullRequests={() => {}}
        {...props}
      />);
    expect(markup).toContain(pullRequestError);
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>Retrying…<\/button>/);
    expect(markup).not.toContain('No pull request yet');
    expect(markup).not.toContain('this task&#x27;s branch has no pull request');
    expect(markup).not.toContain('Check again');
    expect(markup).not.toContain('class="agt-notice"');
  });

  it('waits for the first pull-request lookup before reporting an empty branch', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskBody state={state} panelOpen pullRequests={[]} pullRequestLoading {...props} />);
    expect(markup).toContain('Checking for a pull request…');
    expect(markup).not.toContain('No pull request yet');
    // A later lookup refreshes underneath the answer the panel already shows.
    const rechecking = renderToStaticMarkup(
      <AgentTaskBody state={state} panelOpen pullRequests={[]} pullRequestLoading pullRequestChecked {...props} />);
    expect(rechecking).not.toContain('Checking for a pull request…');
    expect(rechecking).toContain('No pull request yet');
  });

  it('hides the subagents tab when the lane does not advertise nested activity', () => {
    const claudeState = {
      ...state,
      capabilities: { ...CAPABILITIES, nestedAgentActivity: false },
    };
    const markup = renderToStaticMarkup(
      <AgentTaskBody state={claudeState} panelOpen panelTab="agents" pullRequests={[]} {...props} />);

    expect(markup).toContain('aria-label="Pull request"');
    expect(markup).not.toContain('Subagents');
    expect(markup).not.toContain('No active subagents');
    expect(markup).toMatch(/<button[^>]*aria-pressed="true"[^>]*>[^]*?Pull request</);
  });

  it('reads the task pull request in the panel instead of a list that navigates away', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskBody
        state={state}
        panelOpen
        pullRequests={[pullRequest(482, 'Fix preview output limit'), pullRequest(480, 'Earlier attempt', 'merged')]}
        onOpenPullRequest={() => {}}
        {...props}
      />);
    // One pull request, the one the branch still has open, and no row that leaves the task.
    expect(markup).toContain('#482 Fix preview output limit');
    expect(markup).not.toContain('Earlier attempt');
    expect(markup).not.toContain('title="Open this pull request"');
    expect(markup).not.toContain('on this task&#x27;s branch');
    // Changes reads the same pull request's diff; findings still open on the full-width surface.
    const changes = renderToStaticMarkup(
      <AgentTaskBody state={state} panelOpen panelTab="changes" pullRequests={[]} {...props} />);
    expect(changes).toContain('aria-label="Changes"');
    expect(changes).toContain('this task&#x27;s branch has no pull request');
  });

  it('shows review threads on the panel diff once the pull request detail is read', () => {
    const pr = pullRequest(482, 'Fix preview output limit');
    const changes: TaskPullRequestChanges = {
      files: [{
        filename: 'src/Example.java',
        status: 'modified',
        additions: 1,
        deletions: 0,
        patch: '@@ -1,1 +1,2 @@\n line one\n+line two',
      }],
      commits: [],
      loading: false,
      error: null,
    };
    const withoutDetail = renderToStaticMarkup(
      <AgentTaskBody
        state={state} panelOpen panelTab="changes" pullRequests={[pr]}
        onAskAgent={async () => {}}
        pullRequestChanges={changes}
        {...props} />);
    const withDetail = renderToStaticMarkup(
      <AgentTaskBody
        state={state} panelOpen panelTab="changes" pullRequests={[pr]}
        onAskAgent={async () => {}}
        pullRequestChanges={changes}
        pullRequestDetail={{
          detail: reviewedDetail(),
          loading: false,
          error: null,
          refresh: async () => {},
          update: () => {},
          replace: () => {},
        }}
        {...props} />);
    // Both render the diff; only the one holding a detail can place the thread that is on it.
    expect(withoutDetail).toContain('src/Example.java');
    expect(withoutDetail).not.toContain('pr-inline-thread');
    expect(withDetail).toContain('class="is-review-thread is-add"');
    expect(withDetail).toContain('Comment on line <strong>R2</strong>');
    expect(withoutDetail).toContain('aria-label="Add a comment on new line 2"');
    expect(withDetail).toContain('aria-label="Add a comment on new line 2"');
  });

  it('keeps commit selection available after a comparison fails', () => {
    const markup = renderToStaticMarkup(<AgentTaskBody
      state={state} panelOpen panelTab="changes"
      pullRequests={[pullRequest(482, 'Fix preview output limit')]}
      pullRequestChanges={{
        files: [], loading: false, error: 'Comparison failed',
        commits: [{ sha: 'abcdef1', parentSha: 'abcdef0', message: 'Fix output', author: 'alice', authoredAt: null }],
      }}
      pullRequestCommitRange={{ start: 0, end: 0 }}
      {...props} />);
    expect(markup).toContain('role="alert">Comparison failed');
    expect(markup).toContain('All commits');
    expect(markup).toContain('aria-label="Deselect abcdef1: Fix output"');
  });

  it('does not offer private code comments on a completed task', () => {
    const markup = renderToStaticMarkup(<AgentTaskBody
      state={{ ...state, task: { ...state.task, state: 'COMPLETED' } }}
      onAskAgent={async () => {}}
      panelOpen panelTab="changes"
      pullRequests={[pullRequest(482, 'Fix preview output limit')]}
      pullRequestChanges={{ files: [{
        filename: 'src/Example.java', status: 'modified', additions: 1, deletions: 0,
        patch: '@@ -1,1 +1,2 @@\n line one\n+line two',
      }], commits: [], loading: false, error: null }}
      {...props} />);
    expect(markup).toContain('src/Example.java');
    expect(markup).not.toContain('aria-label="Add a comment on new line 2"');
  });

  it('shows the no-pull-request state with the branch, the repository and the draft action', () => {
    const branched: AgentTaskState = {
      ...state,
      task: {
        ...task('t-1', 'OPEN', 'x'),
        summary: {
          finalResponse: null,
          resultBranch: 'reuse-resolved-field-index',
          resultHead: null,
          metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT1S' },
          notes: null,
          children: [],
        },
      },
    };
    const markup = renderToStaticMarkup(
      <AgentTaskBody
        state={branched}
        panelOpen
        pullRequests={[]}
        repository="trinodb/trino"
        {...props}
      />);
    expect(markup).toContain('No pull request yet');
    expect(markup).toContain('<code>reuse-resolved-field-index</code>');
    expect(markup).toContain('<strong>trinodb/trino</strong>');
    expect(markup).toContain('A pull request opened from the agent or on GitHub appears at the next check.');
  });

  it('offers a re-check only while the branch has no pull request of its own', () => {
    const check = { panelOpen: true, onRefreshPullRequests: () => {} };
    const idle = renderToStaticMarkup(<AgentTaskBody state={state} pullRequests={[]} {...check} {...props} />);
    expect(idle).toMatch(/<button type="button" class="agt-nopr__check" title="[^"]*">Check again<\/button>/);
    // Without a re-check to run — a busy task, or a static render — the button says so.
    const busy = renderToStaticMarkup(<AgentTaskBody state={state} panelOpen pullRequests={[]} {...props} />);
    expect(busy).toMatch(/class="agt-nopr__check" disabled=""/);
    const checking = renderToStaticMarkup(
      <AgentTaskBody state={state} pullRequests={[]} pullRequestLoading pullRequestChecked {...check} {...props} />);
    expect(checking).toMatch(/class="agt-nopr__check" disabled=""[^>]*>Checking…<\/button>/);
    // An open pull request replaces the empty state entirely.
    const opened = renderToStaticMarkup(
      <AgentTaskBody state={state} pullRequests={[pullRequest(482, 'Fix preview output limit', 'open', true)]} {...check} {...props} />);
    expect(opened).not.toContain('Check again');
    // A merged one is what the task delivered, so it replaces the empty state as well.
    const merged = renderToStaticMarkup(
      <AgentTaskBody state={state} pullRequests={[pullRequest(480, 'Earlier attempt', 'merged')]} {...check} {...props} />);
    expect(merged).not.toContain('Check again');
    // A closed, unmerged one leaves the branch without one.
    const closed = renderToStaticMarkup(
      <AgentTaskBody state={state} pullRequests={[pullRequest(480, 'Earlier attempt', 'closed')]} {...check} {...props} />);
    expect(closed).toMatch(/<button type="button" class="agt-nopr__check" title="/);
  });

  it('reads the branch pull request the task is working on, then the one it delivered', () => {
    const merged = pullRequest(480, 'Earlier attempt', 'merged');
    const open = pullRequest(482, 'Fix preview output limit');
    const later = pullRequest(486, 'Second attempt');
    const closed = pullRequest(488, 'Abandoned attempt', 'closed');
    expect(taskPullRequest([])).toBeNull();
    expect(taskPullRequest([merged, open])?.number).toBe(482);
    expect(taskPullRequest([open, later])?.number).toBe(486);
    // Without an open pull request the task stays linked to the newest one that merged.
    expect(taskPullRequest([merged, closed])?.number).toBe(480);
    expect(taskPullRequest([merged, pullRequest(490, 'Second delivery', 'merged')])?.number).toBe(490);
    // A closed, unmerged row leaves the branch without a pull request.
    expect(taskPullRequest([closed])).toBeNull();
    expect(taskPullRequest([closed], closed.number)).toBe(closed);
  });

  it('treats a transcript link to the task pull request as that panel tab', () => {
    const pr = pullRequest(482, 'Fix preview output limit');
    expect(samePullRequest('https://github.com/bytequay/bytequay/pull/482', pr)).toBe(true);
    // GitHub's own anchors and query strings still address the same row.
    expect(samePullRequest('https://github.com/bytequay/bytequay/pull/482#issuecomment-9', pr)).toBe(true);
    expect(samePullRequest('https://github.com/bytequay/bytequay/pull/482/?utm=1', pr)).toBe(true);
    // Another pull request, another repository, or the diff of a file is somebody else's link.
    expect(samePullRequest('https://github.com/bytequay/bytequay/pull/4820', pr)).toBe(false);
    expect(samePullRequest('https://github.com/trinodb/trino/pull/482', pr)).toBe(false);
    expect(samePullRequest('https://github.com/bytequay/bytequay/issues/482', pr)).toBe(false);
    expect(samePullRequest(null, pr)).toBe(false);
  });

});

describe('agent task detail', () => {
  it('offers the header title as the rename control while a short name is generated', () => {
    const provisional = { ...task('t-1', 'OPEN', 'Investigate the flaky preview'), titleSource: 'PROVISIONAL' };
    const renamable = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: provisional }}
        models={MODELS}
        onRename={() => {}}
        {...TASK_BODY_NOOPS}
      />);
    expect(renamable).toMatch(/<button[^>]*class="agt-title is-provisional"[^>]*>Investigate the flaky preview</);
    expect(renamable).toContain('while a short name is generated');

    // Without a rename handler the title is text, not a dead control; an agent-named task is not provisional.
    const named = body({ task: { ...task('t-1', 'OPEN', 'Trim the preview to the selection'), titleSource: 'AGENT' } });
    expect(named).toMatch(/<strong class="agt-title">Trim the preview to the selection</);
  });

  it('never leaks a raw escape sequence into rendered text', () => {
    // A JSX text node does not interpret \\u escapes; one written there renders literally.
    // This has slipped through twice, so it is asserted over every state the body can be in.
    const states: Array<Partial<AgentTaskState>> = [
      { events, connection: 'open', task: task('t-1', 'OPEN', 'x') },
      { connection: 'connecting' },
      { connection: 'reconnecting', lastSequence: 7 },
      { connection: 'failed', connectionDetail: 'gone' },
      { connection: 'open', gap: true },
    ];
    for (const state of states) {
      expect(body(state)).not.toMatch(/\\u[0-9a-f]{4}|u00b7|u2014|u2026/);
    }
  });


  it('prints the whole fixture transcript', () => {
    const folded = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events, connection: 'open' });
    // The fixture's activity group folds to its sentence until it is opened.
    expect(folded.replace(/<[^>]*>/g, '')).not.toContain('$ npm test');
    const markup = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events, connection: 'open' }, { '00000000-0000-4000-8000-000000000005': true });

    expect(markup).toContain('Rename the widget');
    expect(markup).toContain('Review the authentication change');
    expect(markup).toContain('Plan');
    expect(markup.replace(/<[^>]*>/g, '')).toContain('$ npm test');
    expect(markup).toContain('Approval');
    expect(markup).toContain('Live');
    // A finished group folds to a sentence derived from its typed members.
    expect(markup).toContain('Ran 1 command, edited 1 file, and used 1 tool');
  });

  it('keeps the running root plan after its event leaves the loaded history window', () => {
    const plan = [{ text: 'Inspect planner', state: 'COMPLETED' as const }, { text: 'Verify fix', state: 'IN_PROGRESS' as const }];
    const recent: AgentEventEnvelope = { ...events[0], type: 'assistant_message', sequence: 4000, turnId: 'turn-1',
      payload: { messageKind: 'COMMENTARY', phase: 'FINAL', text: 'Tests are running.' } };
    const state: Partial<AgentTaskState> = { task: task('t-1', 'OPEN', 'Fix planner'), turn: turn('RUNNING', { plan }),
      latestTurnId: 'turn-1', latestTurnSettled: false, events: [recent], connection: 'open',
      historyPages: [{ events: [recent], beforeCursor: '3000', afterCursor: null, latestSequence: 4000 }] };
    const markup = body(state);
    expect(markup).toContain('class="agt-todos"');
    expect(markup).toContain('Inspect planner');
    expect(markup).toContain('Verify fix');
    expect(markup).toContain('1 completed · 1 in progress');
    expect(body({ ...state, turn: turn('RUNNING', { plan: [] }) })).not.toContain('class="agt-todos"');
    expect(body({ ...state, latestTurnId: 'next-turn' })).not.toContain('class="agt-todos"');
    expect(body({ ...state, capabilities: { ...CAPABILITIES, structuredPlans: false } })).not.toContain('class="agt-todos"');
  });

  it('uses semantic Lucide icons for activity and keeps state icons for outcomes', () => {
    const openActivity = { '00000000-0000-4000-8000-000000000005': true };
    const markup = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events, connection: 'open' }, openActivity);

    expect(markup).toContain('lucide-square-terminal');
    expect(markup).toContain('lucide-file-pen-line');
    expect(markup).toContain('lucide-wrench');
    expect(markup).toContain('lucide-shield-check');
    expect(markup).toContain('lucide-circle-check');
    expect(markup).not.toMatch(/[✓✕○]/);

    const parsed = events.map((event) => event.type !== 'command' ? event : ({
      ...event,
      payload: {
        ...event.payload,
        actions: [
          { kind: 'READ' as const, path: 'src/auth.ts', query: null as string | null },
          { kind: 'LIST_FILES' as const, path: 'src', query: null as string | null },
          { kind: 'SEARCH' as const, path: 'src', query: 'token' },
        ],
      },
    }));
    const parsedMarkup = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events: parsed, connection: 'open' }, openActivity);
    expect(parsedMarkup).toContain('lucide-book-open');
    expect(parsedMarkup).toContain('lucide-folder-open');
    expect(parsedMarkup).toContain('lucide-search');
    // All actions belong to one command, so the result gets one disclosure.
    expect(parsedMarkup.match(/<summary class="agt-act__row is-command">/g)).toHaveLength(1);
    expect(parsedMarkup).toMatch(/<summary class="agt-act__row is-command">[^]*?Read src\/auth.ts[^]*?Listed src[^]*?Searched token in src[^]*?<\/summary>/);

    const toolIcons = [
      ['Web search', 'lucide-globe'],
      ['Image view', 'lucide-image'],
      ['Context compaction', 'lucide-chevrons-down-up'],
      ['Automatic approval', 'lucide-shield-check'],
    ] as const;
    for (const [name, icon] of toolIcons) {
      const named = events.map((event) => event.type === 'tool' ? { ...event, payload: { ...event.payload, name } } : event);
      expect(body({ task: task('t-1', 'OPEN', 'Rename the widget'), events: named, connection: 'open' }, openActivity)).toContain(icon);
    }
  });

  it('lets running and failed state override an activity type icon', () => {
    const openActivity = { '00000000-0000-4000-8000-000000000005': true };
    const commandState = (state: 'STARTED' | 'FAILED', exitCode: number | null) => events.map((event) => event.type !== 'command' ? event : ({
      ...event,
      payload: { ...event.payload, state, exitCode },
    }));

    const running = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events: commandState('STARTED', null), connection: 'open' }, openActivity);
    expect(running).toContain('lucide-loader-circle');

    const failed = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events: commandState('FAILED', 1), connection: 'open' }, openActivity);
    expect(failed).toContain('lucide-circle-x');
  });

  it('turns off transcript motion when reduced motion is requested', () => {
    expect(css).toContain('@media (prefers-reduced-motion: reduce)');
    expect(css).toContain('.agt-icon.is-outcome > svg');
    expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\)[^]*?\.agt-spin[^]*?animation: none/);
  });

  it('anchors the composer card with one round button that sends, or stops while a turn runs', () => {
    const vision = { ...task('t-1', 'OPEN', 'x'), defaults: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO' } };
    const idle = body({ task: vision, connection: 'open', composer: 'go' });

    expect(idle).toContain('Message the agent');
    expect(idle).toMatch(/<button[^>]*class="agt-send"[^>]*aria-label="Send message"/);
    expect(idle).not.toMatch(/class="agt-send"[^>]*disabled/);
    expect(idle).not.toContain('agt-send is-stop');
    // Complete is live while idle, and the header names the state.
    expect(idle).toMatch(/<button[^>]*class="agt-headbtn"[^>]*aria-label="Mark it complete"(?![^>]*disabled)/);
    expect(idle).toContain('title="Mark it complete"');
    expect(idle).toContain('>Idle<');
    // The bounded status line is what assistive technology hears; it names the state, not tokens.
    expect(idle).toContain('aria-live="polite"');
    expect(idle.match(/role="status"/g)).toHaveLength(1);
    // The settings the next turn runs with are the chips, in prose rather than enum names.
    expect(idle).toContain('5.6 Terra');
    expect(idle).not.toContain('AUTO');
    // A disabled Complete has to say why it is disabled.
    expect(body({ connection: 'open' })).toContain('Available when the task is idle');

    const running = body({ task: vision, turn: turn('RUNNING'), connection: 'open' });
    expect(running).toMatch(/<button[^>]*class="agt-send is-stop"[^>]*aria-label="Stop this turn"/);
    expect(running).not.toMatch(/class="agt-send is-stop"[^>]*disabled/);
    expect(body({ task: vision, turn: turn('ACCEPTED'), connection: 'open', sending: true })).toMatch(/<button[^>]*class="agt-send is-stop"[^>]*disabled/);
    expect(running).not.toContain('aria-label="Send message"');
    expect(running).toContain('>Running<');
    // The last-resort server stop lives on the recovery card, not beside a running turn.
    expect(running).not.toContain('Stop server');
    expect(running).not.toContain('CP10');

    // CP10 has replaced both placeholders with real, capability-gated controls.
    const drafting = body({ task: vision, turn: turn('RUNNING'), connection: 'open', composer: 'also check the tests', capabilities: CAPABILITIES });
    expect(drafting).toMatch(/<button[^>]*class="agt-steer"(?![^>]*disabled)[^>]*>Steer<\/button>/);
    expect(drafting).toMatch(/<button[^>]*class="agt-compose__chip"(?![^>]*disabled)[^>]*>Run next<\/button>/);
    expect(drafting).not.toContain('CP10');
  });

  it('requires a post-failure readiness probe before offering an explicit authentication retry', () => {
    const user = events.find((event) => event.type === 'user_message');
    const authTurn = turn('FAILED', {
      id: user?.turnId ?? '',
      endedAt: '2026-08-20T10:00:12Z',
      failureKind: 'AUTHENTICATION_REQUIRED',
    });
    const ready: AgentProviderStatus = {
      laneId: 'codex',
      readiness: 'READY',
      version: '0.152.1',
      diagnostic: 'signed in',
      capabilities: CAPABILITIES,
      signInCommand: null,
    };
    const renderAuth = (fresh: boolean, failed = authTurn) => renderToStaticMarkup(
      <AgentTaskBody
        state={{
          ...initialAgentTaskState(),
          task: task('t-1', 'OPEN', 'Authentication'),
          turn: failed,
          events,
          connection: 'open',
          capabilities: CAPABILITIES,
        }}
        models={MODELS}
        provider="Codex"
        providerStatus={ready}
        providerStatusFresh={fresh}
        {...TASK_BODY_NOOPS}
      />);

    const stale = renderAuth(false);
    expect(stale).toContain('Codex sign-in required');
    expect(stale).toContain('Message to resend');
    expect(stale).toContain('Review the authentication change');
    expect(stale).toContain('1 attachment');
    expect(stale).toContain('flow.png (image/png)');
    expect(stale).toMatch(/<button[^>]*disabled[^>]*>Retry last message…<\/button>/);

    const refreshed = renderAuth(true);
    expect(refreshed).toContain('Codex sign-in required');
    expect(refreshed).toContain('Login status is ready');
    expect(refreshed).toMatch(/<button(?![^>]*disabled)[^>]*>Retry last message…<\/button>/);

    const uncertain = renderAuth(true, { ...authTurn, delivered: false, knownUnsent: false });
    expect(uncertain).toContain('delivery may be uncertain');
    expect(uncertain).not.toContain('Retry last message…');
  });

  it('keeps a removed DeepSeek key task readable and blocks another turn', () => {
    const oldTask = { ...task('t-1', 'OPEN', 'Saved work'), laneId: 'deepseek-key-old' };
    const markup = renderToStaticMarkup(<AgentTaskBody
      state={{ ...initialAgentTaskState(), task: oldTask, events, connection: 'open', capabilities: NO_AGENT_CAPABILITIES }}
      models={[]} provider="DeepSeek" keyRemoved onOpenSettings={() => {}} {...TASK_BODY_NOOPS} />);

    expect(markup).toContain('DeepSeek key removed');
    expect(markup).toContain('Its transcript and checkout remain available');
    expect(markup).toContain('Open Models settings');
    expect(markup).toContain('Review the authentication change');
    expect(markup).not.toContain('Codex sign-in');
    expect(markup).not.toContain('aria-label="Send message"');
  });

  it('shows a key remedy for an invalid DeepSeek key without offering CLI sign-in', () => {
    const invalid = providerStatus('AUTHENTICATION_REQUIRED', {
      laneId: 'deepseek-key-123', diagnostic: 'DeepSeek rejected this API key', signInCommand: null,
    });
    const markup = renderToStaticMarkup(<AgentTaskBody
      state={{ ...initialAgentTaskState(), task: { ...task('t-1', 'OPEN', 'Key issue'), laneId: invalid.laneId }, connection: 'open', capabilities: NO_AGENT_CAPABILITIES }}
      models={[]} provider="DeepSeek · personal" providerStatus={invalid} onOpenSettings={() => {}} {...TASK_BODY_NOOPS} />);

    expect(markup).toContain('DeepSeek key needs attention');
    expect(markup).toContain('Open Models settings');
    expect(markup).toContain('DeepSeek rejected this API key');
    expect(markup).not.toContain('>Sign in<');
    expect(markup).not.toContain('codex login');
  });

  it('offers sign-in only where a lane is unauthenticated and a terminal can be opened', () => {
    const render = (readiness: AgentProviderReadiness, onSignIn?: () => void) => renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: task('t-1', 'OPEN', 'Provider blocked'), connection: 'open', capabilities: CAPABILITIES }}
        models={MODELS}
        provider="Claude"
        providerStatus={providerStatus(readiness)}
        onSignInProvider={onSignIn}
        onOpenSettings={() => {}}
        {...TASK_BODY_NOOPS}
      />);

    const offered = render('AUTHENTICATION_REQUIRED', () => {});
    expect(offered).toContain('>Sign in<');
    // The command stays visible for anyone who would rather run it themselves.
    expect(offered).toContain('CLAUDE_CONFIG_DIR');
    expect(offered).toContain('Refresh login status');

    // A lane that is merely unavailable cannot be fixed by signing in.
    expect(render('TEMPORARILY_UNAVAILABLE', () => {})).not.toContain('>Sign in<');
    expect(render('CLI_MISSING', () => {})).not.toContain('>Sign in<');
    // Server-rendered surfaces pass no handler, so they show the command alone.
    expect(render('AUTHENTICATION_REQUIRED')).not.toContain('>Sign in<');
  });

  it.each([
    ['CLI_MISSING', 'Claude CLI not installed', 'Install the CLI', 'npm install -g @anthropic-ai/claude-code', 'Check again'],
    ['AUTHENTICATION_REQUIRED', 'Claude sign-in required', 'Sign in', 'CLAUDE_CONFIG_DIR=&quot;/provider-home&quot; claude auth login', 'Refresh login status'],
    ['UNSUPPORTED', 'Claude is unsupported', 'Report an issue', 'Detected CLI version: 2.1.259', 'Check again'],
    ['TEMPORARILY_UNAVAILABLE', 'Claude is unavailable', 'Check again', 'provider readiness diagnostic', 'Check again'],
  ] as const)('blocks a new turn and shows the typed %s remedy', (readiness, heading, remedy, detail, checkAction) => {
    const markup = renderToStaticMarkup(
      <AgentTaskBody
        state={{
          ...initialAgentTaskState(),
          task: task('t-1', 'OPEN', 'Provider blocked'),
          connection: 'open',
          composer: 'keep this draft',
          capabilities: CAPABILITIES,
        }}
        models={MODELS}
        provider="Claude"
        providerStatus={providerStatus(readiness)}
        onOpenSettings={() => {}}
        {...TASK_BODY_NOOPS}
      />);

    expect(markup).toContain(heading);
    expect(markup).toContain(remedy);
    expect(markup).toContain(detail);
    expect(markup).toContain(checkAction);
    expect(markup).toContain('Open workspace settings');
    expect(markup).not.toContain('agt-compose-dock');
    expect(markup).not.toContain('aria-label="Send message"');
  });

  it('replaces a stale ready status with the typed turn-preflight refusal', () => {
    const unavailable: AgentProviderUnavailableDto = {
      timestamp: '2026-09-04T10:00:00Z',
      status: 503,
      code: 'AGENT_PROVIDER_UNAVAILABLE',
      laneId: 'claude',
      readiness: 'UNSUPPORTED',
      diagnostic: 'required stream-json capability is unavailable',
      signInCommand: null,
      path: '/agent/tasks/t-1/turns',
    };

    expect(providerStatusFromUnavailable(unavailable, providerStatus('READY'), NO_AGENT_CAPABILITIES))
      .toEqual({
        laneId: 'claude',
        readiness: 'UNSUPPORTED',
        version: '2.1.259',
        diagnostic: 'required stream-json capability is unavailable',
        capabilities: CAPABILITIES,
        signInCommand: null,
      });
  });

  it('replaces the mutation controls with the recovery card while the checkout is reserved', () => {
    const held = {
      ...task('t-1', 'OPEN', 'x'),
      mutationClaim: { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-08-20T10:00:00Z' },
    };
    const markup = body({
      task: held,
      turn: turn('FAILED', { terminalReason: 'Codex CLI 0.152.1 finished the turn but proc-1 could not be shown stopped' }),
      connection: 'open',
      composer: 'go',
    });

    expect(markup).toContain('Could not verify shutdown');
    expect(markup).toContain('could not verify that its agent and tools stopped');
    // The diagnostic is bounded to what the backend recorded, and the way out is named.
    expect(markup).toContain('proc-1 could not be shown stopped');
    expect(markup).toContain('Mark task failed');
    expect(markup).toContain('Only this task');
    expect(markup).not.toMatch(/<button[^>]*disabled[^>]*>Stop this task/);
    // Nothing may send, retry or complete until the process is verified gone: the card replaces the composer.
    expect(markup).not.toContain('agt-compose');
    expect(markup).not.toContain('Retry last message');
    expect(markup).toMatch(/<button[^>]*class="agt-headbtn"[^>]*aria-label="Mark it complete"[^>]*disabled/);
    expect(markup).toContain('Available after agent shutdown is verified');
    expect(markup).toContain('>Shutdown blocked<');
    expect(markup).not.toContain('agt-spin');
    expect(markup).not.toContain('agt-state__dot');
  });

  it('offers a shutdown check without inventing a remedy for a recovered process', () => {
    const reserved = {
      ...task('t-1', 'OPEN', 'x'),
      mutationClaim: { kind: 'RECOVERY', ownerId: 'launch-9', acquiredAt: '2026-08-20T10:00:00Z' },
    };
    const markup = body({ task: reserved, connection: 'open' });

    expect(markup).toContain('from before the restart');
    expect(markup).not.toMatch(/<button[^>]*disabled[^>]*>Stop this task/);
    expect(markup).toContain('Use Stop to check again');
    expect(markup).toContain('follow the reported diagnostic');
    expect(markup).not.toContain('restart ByteQuay');
    expect(markup).not.toContain('Restart your Mac');
  });

  it.each(['FAILED', 'INTERRUPTED'])('keeps a %s turn inspectable and restores its composer only after shutdown', state => {
    const open = task('t-1', 'OPEN', 'Kept task');
    const ended = turn(state, { terminalReason: 'The request stopped before completion' });
    const reason = 'Automation stopped. Your task and files are retained; send a message to continue.';
    const preserved = { task: open, turn: ended, connection: 'open' as const, composer: 'Continue with the smaller change',
      developmentProgress: { sequence: 12, message: reason } };
    const blocked = body({ ...preserved, task: { ...open, developmentContinuationUnfinished: true,
      mutationClaim: { kind: 'TURN', ownerId: ended.id, acquiredAt: ended.createdAt } } });
    expect(blocked).toContain('Could not verify shutdown');
    expect(blocked).toContain('Stop automation…');
    expect(blocked).not.toContain('agt-compose');
    expect(blocked).not.toContain('agt-spin');
    expect(blocked).not.toContain('Retry last message');

    const released = body(preserved);
    expect(released).toContain(reason);
    expect(released).toContain('Continue with the smaller change</textarea>');
    expect(released).toMatch(/<button(?![^>]*disabled)[^>]*aria-label="Send message"/);
    expect(released).not.toContain('Could not verify shutdown');
    expect(released).not.toContain('Stop automation');
    expect(released).not.toContain('Delete task');
  });

  it('shows the kept result instead of a composer once the task is closed', () => {
    const completed: AgentTaskDto = {
      ...task('t-1', 'COMPLETED', 'Shipped'),
      terminalAt: '2026-08-20T10:05:00Z',
      checkoutRemovedAt: '2026-08-20T10:05:01Z',
      codegraph: true,
      summary: {
        finalResponse: 'All done.',
        resultBranch: 'bytequay/task/t-1',
        resultHead: 'abc123',
        metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT1S' },
        notes: null,
        children: [],
      },
    };
    const markup = body({ task: completed, connection: 'open' });

    expect(markup).toContain('bytequay/task/t-1');
    expect(markup).toContain('abc123');
    expect(markup).toContain('the branch remains');
    expect(markup).not.toContain('Retry checkout cleanup');
    expect(markup).not.toContain('Message the agent');
    expect(markup).not.toContain('aria-label="Mark it complete"');

    const failed = body({
      task: { ...task('t-1', 'FAILED', 'Gave up'), terminalReason: 'Marked failed by the user during recovery' },
      connection: 'open',
    });
    expect(failed).toContain('Marked failed by the user');
    expect(failed).toContain('Cleanup required — kept for inspection');
    expect(failed).toContain('Retry checkout cleanup');
  });

  it('shows every message steered into the same turn', () => {
    const first = events.find((event) => event.type === 'user_message');
    if (first === undefined) throw new Error('fixture has no user message');
    const steered = {
      ...first,
      eventId: '00000000-0000-4000-8000-000000000099',
      sequence: first.sequence + 1,
      occurredAt: '2026-08-20T10:00:02Z',
      payload: { text: 'Also check the tests', attachments: [], deliveryKind: 'STEER' },
    } as AgentEventEnvelope;
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events: [first, steered], connection: 'open' });

    expect(markup.indexOf('Review the authentication change')).toBeLessThan(markup.indexOf('Also check the tests'));
    expect(markup).toContain('Steered into the running turn');
  });

  it('shows the settings each message actually ran with, from its durable turn', () => {
    const firstTurn = events[0].turnId ?? '';
    const ran = turn('COMPLETED', {
      id: firstTurn,
      settings: {
        model: 'gpt-5.6-codex',
        effort: 'medium',
        permissionPolicy: 'AUTO',
        maximumRuntimeMillis: 3600000,
        attachments: [],
      },
    });
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open', turns: { [firstTurn]: ran } });

    // The snapshot the turn recorded, not the defaults: a later change must not rewrite history.
    expect(markup).toContain('5.6 Terra medium \u00b7 Auto');
    expect(body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open' })).not.toContain('5.6 Terra medium');
  });

  it('folds a finished turn to its final answer and keeps the totals under the composer', () => {
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open' }, {}, {});

    expect(markup).not.toContain('agt-turn__sum');
    expect(markup).not.toContain('Ran 1 command, edited 1 file, and used 1 tool');
    expect(markup).toMatch(/class="agt-turn__head" aria-expanded="false"/);
    expect(markup).toContain('Turn 1 · Worked for 11s');
    expect(markup).toContain('class="agt-answer"');
    expect(markup).toContain('I found one authentication regression.');
    expect(markup).not.toContain('agt-usage__tokens');
    expect(markup).toMatch(/class="agt-compose__stats"[^>]*><span>\d+ turns? · \d+ activit(?:y|ies)<\/span><span class="agt-compose__sep"/);
    expect(markup).toMatch(/Input \d[\d.]*[kM]? tok · Cached \d[\d.]*[kM]? tok · Output \d[\d.]*[kM]? tok/);
  });

  it('folds each completed turn independently while keeping the current turn open', () => {
    const secondTurn = events.map((event): AgentEventEnvelope => ({
      ...event, eventId: `second-${event.eventId}`, turnId: 'second', sequence: event.sequence + events.length,
    }));
    const state = { task: task('t-1', 'OPEN', 'x'), events: [...events, ...secondTurn], connection: 'open' as const };
    const finished = body(state, {}, {});
    expect(finished.match(/class="agt-turn__head" aria-expanded="false"/g)).toHaveLength(2);
    expect(finished.match(/I found one authentication regression\./g)).toHaveLength(2);

    const running = body({ ...state, events: [...events, ...secondTurn.slice(0, -1)] }, {}, {});
    expect(running.match(/class="agt-turn__head" aria-expanded="false"/g)).toHaveLength(1);
    expect(running.match(/class="agt-turn__head" aria-expanded="true"/g)).toHaveLength(1);

    const reopened = body(state, {}, { second: true });
    expect(reopened.match(/class="agt-turn__head" aria-expanded="true"/g)).toHaveLength(1);
    expect(reopened).toContain('Ran 1 command, edited 1 file, and used 1 tool');
  });

  it('keeps commentary visible when a finished turn has no final answer and respects explicit folding', () => {
    const withoutAnswer = events.map((event): AgentEventEnvelope => event.type === 'assistant_message'
      ? { ...event, payload: { ...event.payload, messageKind: 'COMMENTARY' } } : event);
    const state = { task: task('t-1', 'OPEN', 'x'), events: withoutAnswer, connection: 'open' as const };
    expect(body(state, {}, {})).toMatch(/class="agt-turn__head" aria-expanded="true"/);
    expect(body(state, {}, {})).toContain('class="agt-text"');
    const closed = Object.fromEntries(projectTranscript(events).map((turn) => [turn.turnId ?? `turn-${turn.ordinal}`, false]));
    expect(body(state, {}, closed)).not.toContain('class="agt-text"');
    expect(body({ ...state, events }, {}, closed)).toContain('I found one authentication regression.');
  });

  it('shows a live thinking line, the current command, and returns to thinking when it finishes', () => {
    const start = events.slice(0, 2);
    const state = { task: task('t-1', 'OPEN', 'x'), turn: turn('RUNNING', { id: start[0].turnId ?? 'turn-1' }), connection: 'open' as const };
    const liveLine = (shown: AgentEventEnvelope[]) => body({ ...state, events: shown }).match(/<div class="agt-live"[^]*?<\/div>/)?.[0];
    expect(liveLine(start)).toContain('>Thinking</span>');
    expect(liveLine(start)).toContain('agt-live__label is-active');
    const command = events.find((event) => event.type === 'command');
    if (command?.type !== 'command') throw new Error('Missing command fixture');
    const running: AgentEventEnvelope = { ...command, payload: { ...command.payload, state: 'STARTED', exitCode: null, output: null } };
    expect(liveLine([...start, running])).toContain('>Running npm test</span>');
    expect(liveLine([...start, command])).toContain('>Thinking</span>');
    const read: AgentEventEnvelope = { ...running, payload: { ...running.payload, actions: [{ kind: 'READ', name: 'DEVELOPMENT.md', path: 'DEVELOPMENT.md', query: null }] } };
    expect(liveLine([...start, read])).toContain('>Reading DEVELOPMENT.md</span>');
    expect(body({ ...state, events: start, turn: turn('ACCEPTED') })).toContain('>Starting…</span>');
    expect(body({ ...state, events: start, turn: turn('RUNNING', { cancellationRequestedAt: '2026-08-20T10:00:02Z' }) })).toContain('>Stopping…</span>');
    expect(body({ ...state, events: start, connection: 'reconnecting' })).toContain('>Reconnecting…</span>');
    expect(body({ ...state, events: start, connection: 'reconnecting' })).not.toContain('agt-live__label is-active');
    expect(body({ ...state, events, turn: turn('COMPLETED', { endedAt: '2026-08-20T10:00:12Z' }) })).not.toContain('class="agt-live"');
    expect(body({ ...state, events: start, historyPages: [{ events: start, beforeCursor: null, afterCursor: 'newer', latestSequence: 20 }] })).not.toContain('class="agt-live"');
  });

  it('waits for approval without animating a thinking label', () => {
    const markup = body({ task: task('t-1', 'OPEN', 'x'), turn: turn('RUNNING'), events, connection: 'open' });
    expect(markup).toContain('>Waiting for approval</span>');
    expect(markup).not.toContain('agt-live__label is-active');
  });

  it('folds command output behind a keyboard-operable disclosure', () => {
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open' }, { '00000000-0000-4000-8000-000000000005': true });

    expect(markup).toContain('<details class="agt-out">');
    // The disclosure carries the app's one arrow, not the browser's marker.
    // The command row is the disclosure, its chevron on the row; the output box hangs under it.
    expect(markup).toMatch(/<summary class="agt-act__row is-command">[^]*?<svg class="chev"/);
    expect(markup).toMatch(/<summary class="agt-act__row is-command">[^]*?Ran command[^]*?<\/summary><pre class="agt-pre is-shell">[^]*?Shell[^]*?<span class="agt-out__cmd">\$ <code class="hljs">/);
    // The command is printed whole and read as shell; only its output is windowed.
    expect(markup.replace(/<[^>]*>/g, '')).toContain('$ npm test');
    expect(markup).toMatch(/class="agt-out__cmd"[^]*?class="hljs-[^]*?<\/code><\/span><span class="agt-out__stream">/);
    expect(markup).not.toContain('<summary>Output');
    // Approval buttons carry the request they answer, not just "Allow once".
    expect(markup).toContain('aria-label="Allow once: ');
  });

  it('keeps a running group to one folded line that names what is running', () => {
    // A log that opens itself moved the page under the reader on every command.
    const between = events.filter((event) => (event.type !== 'lifecycle' || event.payload.phase !== 'TURN_COMPLETED')
      && !event.type.startsWith('approval') && event.type !== 'error');
    const live = between.map((event): AgentEventEnvelope => event.type !== 'command' ? event
      : { ...event, payload: { ...event.payload, state: 'STARTED', exitCode: null, output: null } });
    const running = body({ task: task('t-1', 'OPEN', 'x'), events: live, connection: 'open' });

    expect(running).toContain('<span>Ran command</span>');
    expect(running).not.toContain('agt-act__body');
    expect(running).not.toContain('<details class="agt-out"');
    // The provider's own label for the command names the line instead of "Ran command".
    const named = live.map((event): AgentEventEnvelope => event.type !== 'command' ? event
      : { ...event, payload: { ...event.payload, description: 'Run the focused tests' } });
    expect(body({ task: task('t-1', 'OPEN', 'x'), events: named, connection: 'open' })).toContain('<span>Run the focused tests</span>');
    // With nothing running the same line is the sentence its members add up to.
    expect(body({ task: task('t-1', 'OPEN', 'x'), events: between, connection: 'open' }))
      .toContain('<span>Ran 1 command, edited 1 file, and used 1 tool</span>');
  });

  it('expands a read from its supplied name and keeps skill loading as a plain row', () => {
    const named = events.map((event): AgentEventEnvelope => {
      if (event.type === 'command') return {
        ...event,
        payload: {
          ...event.payload,
          actions: [{ kind: 'READ' as const, name: 'OpenAI Docs skill', path: null, query: null }],
          output: 'Skill documentation content',
          durationMs: 0,
          truncated: true,
        },
      };
      if (event.type === 'tool') return {
        ...event, payload: { ...event.payload, name: 'Loaded a tool', summary: '', durationMs: 0 },
      };
      return event;
    });
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events: named, connection: 'open' }, { '00000000-0000-4000-8000-000000000005': true });

    expect(markup).toMatch(/<summary class="agt-act__row is-command">[^]*?Read OpenAI Docs skill[^]*?<\/summary><pre class="agt-pre is-shell">[^]*?Skill documentation content[^]*?truncated/);
    expect(markup.match(/Skill documentation content/g)).toHaveLength(1);
    expect(markup).not.toMatch(/<summary[^>]*>[^<]*Output/);
    expect(markup).not.toContain('>0s<');
    expect(markup).toMatch(/<div class="agt-act__row">[^]*?<span class="agt-muted">Loaded a tool<\/span><\/div>/);
    expect(markup).not.toMatch(/<summary[^>]*>(?:(?!<\/summary>)[^])*Loaded a tool/);
  });

  it('names an MCP tool without its server prefix and folds a JSON result', () => {
    const result = JSON.stringify({ step: 2, kind: 'VALIDATE', sealed: false, changeMap: 'Single commit e7dc4a0 on the review branch' });
    const called = events.map((event): AgentEventEnvelope => event.type !== 'tool' ? event : ({
      ...event,
      payload: { ...event.payload, name: 'mcp__bytequay__review_get_context', summary: result },
    }));
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events: called, connection: 'open' }, { '00000000-0000-4000-8000-000000000005': true });

    expect(markup).not.toContain('mcp__bytequay__');
    expect(markup).toMatch(/<summary class="agt-act__row">[^]*?review_get_context[^]*?<\/summary><pre class="agt-pre">/);
    // The payload reads as an indented document instead of one clipped line.
    expect(markup).toContain('&quot;kind&quot;: &quot;VALIDATE&quot;');

    const short = events.map((event): AgentEventEnvelope => event.type !== 'tool' ? event : ({
      ...event,
      payload: { ...event.payload, name: 'mcp__bytequay__review_get_context', summary: 'ok' },
    }));
    const inline = body({ task: task('t-1', 'OPEN', 'x'), events: short, connection: 'open' }, { '00000000-0000-4000-8000-000000000005': true });
    expect(inline).toMatch(/<div class="agt-act__row">[^]*?review_get_context[^]*?<span class="agt-mono">ok<\/span><\/div>/);
  });

  it('keeps unnamed reads truthful and a running result folded, without activity clocks', () => {
    const reading = events.filter((event) => event.type !== 'lifecycle' || event.payload.phase !== 'TURN_COMPLETED')
      .map((event): AgentEventEnvelope => event.type !== 'command' ? event : ({
      ...event,
      payload: {
        ...event.payload,
        state: 'PROGRESS' as const,
        actions: [{ kind: 'READ' as const, path: null, query: null }],
        durationMs: 0,
        exitCode: null,
      },
    }));
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events: reading, connection: 'open' });
    // An action the provider left unnamed says what it did, never "Read .".
    expect(markup).toContain('<span>Read file</span>');
    expect(markup).not.toContain('Read .');
    // Its output is there to open, not opened for the reader, and the line carries no clock.
    expect(markup).not.toContain('<details class="agt-out" open="">');
    expect(markup).not.toMatch(/class="agt-act__fold"(?:(?!<\/button>)[^])*\d+s/);
    const opened = body({ task: task('t-1', 'OPEN', 'x'), events: reading, connection: 'open' },
      { '00000000-0000-4000-8000-000000000005': true });
    expect(opened).toMatch(/<details class="agt-out"><summary[^>]*>[^]*?Read file[^]*?<\/summary>/);
    expect(opened).toContain('agt-cursor');
  });

  it('keeps approval outcomes without inserting a timestamp into the log', () => {
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open' });
    const approval = markup.match(/<div class="agt-appr-done"[^]*?<\/div>/)?.[0];
    expect(approval).toBeDefined();
    expect(approval).toContain('Approved once');
    expect(approval).not.toMatch(/\d+:\d{2}|<span>·/);
    expect(markup).toContain('Turn 1 · Worked for 11s');
  });

  it('offers the task-wide allow the design draws, and shows an allow that covered the task', () => {
    const decisions: Array<'ALLOW_ONCE' | 'ALLOW_FOR_TASK' | 'DENY'> = ['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY'];
    const offered = events.map((event) => {
      if (event.type === 'approval_requested' && event.payload.approvalId === 'approval-2') {
        return { ...event, payload: { ...event.payload, availableDecisions: decisions } };
      }
      return event.type === 'approval_resolved' ? { ...event, payload: { ...event.payload, forTask: true } } : event;
    });
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events: offered, connection: 'open' });

    // Drawn between Allow once and Deny as a plain button, the way the design draws it.
    expect(markup).toMatch(/Allow once<\/button><button type="button" class="agt-decision" aria-label="Allow for this task: Run a command in this workspace">Allow for this task<\/button><button type="button" class="agt-decision is-deny"/);
    expect(markup).toContain('Approved for this task —');
  });

  it('answers a question tool in place of allowing its raw input', () => {
    const questions = JSON.stringify({
      questions: [{
        question: 'Which issue should I work on?',
        header: 'Issue',
        multiSelect: false,
        options: [
          { label: 'Both', description: 'Fix them together.' },
          { label: 'Neither', description: 'Just the links.' },
        ],
      }],
    });
    const asked = events
      .filter((event) => event.type !== 'approval_resolved')
      .map((event) => event.type === 'approval_requested' && event.payload.approvalId === 'approval-1'
        ? { ...event, payload: { ...event.payload, title: 'Allow Claude AskUserQuestion', scope: ['AskUserQuestion'], detail: questions } }
        : event);
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events: asked, connection: 'open' });

    // The options are the decision, so the raw tool input and its bare allow never appear.
    expect(markup).toContain('<span class="agt-ask__text">Which issue should I work on?</span>');
    expect(markup).toContain('<span class="agt-ask__label">Both</span>');
    expect(markup).toContain('<span class="agt-ask__detail">Fix them together.</span>');
    expect(markup).toContain('role="radio" aria-checked="false"');
    expect(markup).not.toContain('Allow once: Allow Claude AskUserQuestion');
    expect(markup).not.toContain('"multiSelect"');
    // Nothing is sent until a question is answered.
    expect(markup).toMatch(/class="agt-decision is-allow" disabled=""[^>]*>Send answer</);
    // Every other approval keeps the allow it had.
    expect(body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open' })).toContain('aria-label="Allow once: ');
  });

  it('allows text paste while an image loads and keeps submission disabled until it finishes', () => {
    const state: AgentTaskState = {
      ...initialAgentTaskState(), task: task('t-1', 'OPEN', 'x'), connection: 'open', capabilities: CAPABILITIES, composer: 'Screenshot details', sending: true,
    };
    const render = (attaching: boolean) => renderToStaticMarkup(
      <AgentTaskBody
        state={state}
        attaching={attaching}
        models={MODELS}
        {...TASK_BODY_NOOPS}
      />);
    const loadingImage = render(true);
    expect(loadingImage).not.toMatch(/<textarea[^>]*disabled/);
    expect(loadingImage).toMatch(/aria-label="Send message"[^>]*disabled/);
    expect(loadingImage).toMatch(/aria-label="Attach an image"[^>]*disabled/);
    expect(loadingImage).toMatch(/class="agt-headbtn"[^>]*aria-label="Mark it complete"[^>]*disabled/);
    expect(render(false)).toMatch(/<textarea[^>]*disabled/);
  });

  it('offers the image control only for a model that accepts images, and shows drafts and previews', () => {
    const vision = { ...task('t-1', 'OPEN', 'x'), defaults: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO' } };
    const withImages = body({ task: vision, connection: 'open', attachments: [
      { id: 'att-1', name: 'stack-trace.png', mediaType: 'image/png', byteSize: 212 * 1024, width: 800, height: 600 },
    ] }, {}, {}, { 'att-1': { status: 'ready', url: 'blob:draft' } });
    expect(withImages).toMatch(/<button[^>]*class="agt-attach"[^>]*title="5.6 Terra accepts images"[^>]*>/);
    expect(withImages).not.toMatch(/<button[^>]*class="agt-attach"[^>]*disabled/);
    expect(withImages).toMatch(/<button[^>]*aria-label="Preview image stack-trace.png"/);
    expect(withImages).toContain('<img class="agt-thumb" src="blob:draft" alt="stack-trace.png"');
    expect(withImages).not.toContain('agt-draft__name');
    expect(withImages).not.toContain('212 KB');
    expect(withImages.replace(/<[^>]*>/g, '')).not.toContain('stack-trace.png');
    expect(withImages).toContain('aria-label="Remove image stack-trace.png"');

    const textOnly = { ...vision, defaults: { ...vision.defaults, model: 'text-only' } };
    const noImages = body({ task: textOnly, connection: 'open' });
    expect(noImages).toMatch(/<button[^>]*class="agt-attach"[^>]*title="Text only does not accept images"[^>]*disabled/);

    const providerWithoutImages = body({
      task: vision,
      connection: 'open',
      capabilities: { ...CAPABILITIES, attachmentMediaTypes: ['text/plain'] },
    });
    expect(providerWithoutImages).toMatch(/<button[^>]*class="agt-attach"[^>]*disabled/);

    const attachment = { id: 'att-1', name: 'stack-trace.png', mediaType: 'image/png', byteSize: 212 * 1024, width: 800, height: 600 };
    const loading = body({ task: vision, connection: 'open', attachments: [attachment] }, {}, {}, { 'att-1': { status: 'loading' } });
    const omitted = body({ task: vision, connection: 'open', attachments: [attachment] });
    expect(omitted).toContain('Preview not loaded');
    expect(omitted).not.toContain('Loading…');
    expect(loading).toContain('aria-label="stack-trace.png: loading"');
    expect(loading).toContain('Loading…');
    expect(loading.replace(/<[^>]*>/g, '')).not.toContain('stack-trace.png');
    expect(loading).not.toContain('agt-draft__name');
    const sending = body({ task: vision, connection: 'open', attachments: [attachment], sending: true }, {}, {}, { 'att-1': { status: 'ready', url: 'blob:draft' } });
    expect(sending).toMatch(/aria-label="Remove image stack-trace.png"[^>]*disabled/);

    const currentVision = body({
      task: textOnly,
      turn: turn('RUNNING', { settings: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO', maximumRuntimeMillis: 3600000, attachments: [] } }),
      connection: 'open',
      composer: 'look here',
      attachments: [attachment],
      capabilities: CAPABILITIES,
    });
    expect(currentVision).not.toMatch(/<button[^>]*class="agt-attach"[^>]*disabled/);
    expect(currentVision).toMatch(/<button[^>]*class="agt-steer"(?![^>]*disabled)/);
    expect(currentVision).toMatch(/<button[^>]*class="agt-compose__chip"[^>]*disabled[^>]*title="The next-turn model does not accept images"/);

    const nextVision = body({
      task: vision,
      turn: turn('RUNNING', { settings: { model: 'text-only', effort: 'high', permissionPolicy: 'AUTO', maximumRuntimeMillis: 3600000, attachments: [] } }),
      connection: 'open',
      composer: 'look later',
      attachments: [attachment],
      capabilities: CAPABILITIES,
    });
    expect(nextVision).toMatch(/<button[^>]*class="agt-steer"[^>]*disabled[^>]*title="The running turn’s model does not accept images"/);
    expect(nextVision).toMatch(/<button[^>]*class="agt-compose__chip"(?![^>]*disabled)[^>]*title="Runs after this turn finishes"/);

    // A sent message shows its image once the bytes arrived, and says so when they never will.
    const ready = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: vision, events, connection: 'open' }}
        previews={{ 'attachment-1': { status: 'ready', url: 'blob:preview' } }}
        models={MODELS}
        {...TASK_BODY_NOOPS}
      />);
    expect(ready).toContain('<img class="agt-image" src="blob:preview" alt="flow.png"');
    expect(ready).toMatch(/<button[^>]*aria-label="Preview image flow.png"/);

    const gone = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: vision, events, connection: 'open' }}
        previews={{ 'attachment-1': { status: 'unavailable' } }}
        models={MODELS}
        {...TASK_BODY_NOOPS}
      />);
    expect(gone).toContain('flow.png · unavailable');
  });

  it('shows subagents as chips, lists them in the panel, and inspects one without a second route', () => {
    const child = (
      type: string,
      payload: Record<string, unknown>,
      threadId = 'child-1',
      label: string | null = 'History tests',
    ) => ({
      ...events[1],
      eventId: `child-${type}-${threadId}-${JSON.stringify(payload).length}`,
      type,
      payload,
      source: {
        ...events[1].source,
        providerThreadId: threadId,
        parentProviderThreadId: 'thread-root',
        logicalAgentPath: label,
      },
    });
    const withAgents = [
      ...events,
      child('lifecycle', { phase: 'AGENT_STARTED', message: 'History tests' }),
      child('assistant_message', { messageKind: 'PROMPT', phase: 'FINAL', text: 'run the persistence tests' }),
      child('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: '3 tests failed' }),
      child('lifecycle', { phase: 'AGENT_STARTED', message: 'Renderer range' }, 'child-2', 'Renderer range'),
    ] as typeof events;
    const state = {
      task: task('t-1', 'OPEN', 'x'),
      events: withAgents,
      connection: 'open' as const,
      capabilities: CAPABILITIES,
    };

    const closed = body(state);
    // The parent transcript names the agents; their work is not inlined into it.
    expect(closed).toContain('History tests');
    expect(closed).toContain('started working');
    expect(closed).not.toContain('3 tests failed');
    expect(closed).toContain('PR, changes and subagents (2)');
    // Two agents are still working, so the barrier says what Send and Complete are waiting for.
    expect(closed).toContain('2 subagents are still working — Send, queued messages, and Complete unlock when every agent and command finishes.');

    const panel = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), ...state }}
        panelOpen
        panelTab="agents"
        models={MODELS}
        {...TASK_BODY_NOOPS}
      />);
    expect(panel).toContain('aria-label="Subagents"');
    expect(panel).toContain('Active · 2');
    expect(panel).toContain('run the persistence tests');

    const opened = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), ...state }}
        panelOpen
        panelTab="agents"
        openAgent="child-1"
        models={MODELS}
        {...TASK_BODY_NOOPS}
      />);
    // The child's own answer is inspectable, and getting there is a filter, not a route.
    expect(opened).toContain('aria-label="Agent: History tests"');
    expect(opened).toContain('3 tests failed');
    expect(opened).toContain('Back to all subagents');
  });

  it('keeps a cleaned-up agent listed from the summary and says its activity expired', () => {
    const completed: AgentTaskDto = {
      ...task('t-1', 'COMPLETED', 'Shipped'),
      deleteAfter: '2026-08-27T10:05:00Z',
      summary: {
        finalResponse: 'All done.',
        resultBranch: 'bytequay/task/t-1',
        resultHead: 'abc123',
        metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT1S' },
        notes: null,
        children: [{
          threadId: 'child-gone',
          parentThreadId: 'thread-root',
          label: 'Range probe',
          purpose: 'check the selected range',
          outcome: 'COMPLETED',
          result: 'the selection is correct',
          startedAt: '2026-08-20T10:00:00Z',
          endedAt: '2026-08-20T10:02:08Z',
        }],
      },
    };
    const props = {
      models: MODELS,
      onSelectModel: () => {},
      onBack: () => {},
      onSend: () => {},
      onCancel: () => {},
      onComplete: () => {},
      onDecide: () => {},
      onCompose: () => {},
      onStopServer: () => {},
      onMarkFailed: () => {},
    };
    const listed = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: completed, connection: 'open', capabilities: CAPABILITIES }}
        panelOpen
        panelTab="agents"
        {...props}
      />);
    expect(listed).toContain('Range probe');
    expect(listed).toContain('expired');
    expect(listed).toContain('Aug 20');

    const opened = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: completed, connection: 'open', capabilities: CAPABILITIES }}
        panelOpen
        panelTab="agents"
        openAgent="child-gone"
        {...props}
      />);
    // The steps are gone; what was kept is still shown, and nothing is reconstructed.
    expect(opened).toContain('Activity expired');
    expect(opened).toContain('Result — kept');
    expect(opened).toContain('the selection is correct');
  });

  it('says Connecting in the composer until the stream is open', () => {
    const connecting = body({ task: task('t-1', 'OPEN', 'x'), connection: 'connecting' });
    expect(connecting).toContain('placeholder="Connecting\u2026"');
    expect(connecting).not.toContain('aria-label="Show or hide the side panel"');
    expect(body({ connection: 'open' })).toContain('placeholder="Message the agent"');
  });

  it('keeps a healthy connection in the header instead of spending a row on it', () => {
    const open = body({ connection: 'open' });
    // Live state is the header dot only; no full-width strip.
    expect(open).toContain('agt-conn-dot is-open');
    expect(open).not.toContain('class="agt-conn ');
    expect(open).toContain('aria-label="Live"');
  });

  it('says it is reconnecting, from where, without claiming anything was lost', () => {
    const markup = body({ connection: 'reconnecting', lastSequence: 214 });

    expect(markup).toContain('agt-conn is-reconnecting');
    // The resume point is the load-bearing part: "catching up" alone asks for faith.
    expect(markup).toContain('catching up from event 214');
    expect(markup).toContain('Nothing is lost');
  });

  it('offers a decision on a pending approval and none on a settled one', () => {
    const markup = body({ events, connection: 'open' });

    // The fixture carries both, so the reviewable state is not only the settled one.
    expect(markup).toContain('Allow once');
    expect(markup).toContain('>Deny<');
    // The asking agent is part of the identity of the request, not decoration.
    expect(markup).toContain('root agent');
    expect(markup).toContain('Approved once');
    expect(markup).toContain('git push origin fix/preview-limit');
    // The scope is what the decision is actually about.
    expect(markup).toContain('cwd: workspaces/jackchen-trino');
  });

  it('surfaces a held gap and a terminal error instead of a silent hole', () => {
    expect(body({ connection: 'open', gap: true })).toContain('waiting for a missing event');

    const failed = body({ connection: 'failed', connectionDetail: 'stream refused with status 404' });
    expect(failed).toContain('agt-conn is-failed');
    expect(failed).toContain('status 404');

    const errored = body({ events, connection: 'open' });
    expect(errored).toContain('agt-error');
    expect(errored).toContain('role="alert"');
  });
});

describe('task list tabs, day groups and row actions', () => {
  const noon = new Date(2026, 8, 6, 12).getTime();
  const at = (daysAgo: number, hour = 9): string => new Date(2026, 8, 6 - daysAgo, hour).toISOString();
  const rows: AgentTaskDto[] = [
    { ...task('t-today', 'OPEN', 'hello'), updatedAt: at(0, 10), mutationClaim: { kind: 'TURN', ownerId: 'turn-1', acquiredAt: at(0, 10) } },
    { ...task('t-today-2', 'OPEN', 'Ask about the model'), updatedAt: at(0, 8) },
    { ...task('t-yesterday', 'COMPLETED', 'trino issue reading'), updatedAt: at(1), terminalAt: at(1) },
    { ...task('t-wed', 'FAILED', 'Image journey'), updatedAt: at(3), terminalAt: at(3), terminalReason: 'gave up' },
    { ...task('t-shelved', 'COMPLETED', 'Explain the query planner'), updatedAt: at(9), terminalAt: at(9), archivedAt: at(2) },
  ];

  it('groups by the local day a task last moved, newest first', () => {
    const groups = groupTasksByDay(rows.filter((row) => row.archivedAt === null), noon);
    expect(groups.map((group) => [group.label, group.date, group.tasks.map((row) => row.id)])).toEqual([
      ['Today', 'Sep 6', ['t-today', 't-today-2']],
      ['Yesterday', 'Sep 5', ['t-yesterday']],
      ['Thursday, September 3', 'Sep 3', ['t-wed']],
    ]);
  });

  it('shows active tasks under the day groups with archive and delete, and refuses to delete a running one', () => {
    const markup = renderToStaticMarkup(<AgentTaskList {...listProps} tasks={rows} loading={false} error={null} now={noon} />);

    expect(markup).toMatch(/role="tab" aria-selected="true"[^>]*>Active<span class="agt-tab__count">4</);
    expect(markup).toMatch(/role="tab" aria-selected="false"[^>]*>Archived<span class="agt-tab__count">1</);
    expect(markup).toContain('Completed tasks archive automatically after 7 days');
    expect(markup.match(/class="agt-group"/g)).toHaveLength(3);
    expect(markup).toContain('class="agt-group__label">Today<');
    expect(markup).toContain('class="agt-group__count">2 tasks<');
    expect(markup).toContain('class="agt-group__count">1 task<');
    // The shelved task waits on the other tab; nothing here is a glyph.
    expect(markup).not.toContain('Explain the query planner');
    expect(markup).not.toContain('❋');
    // Row anatomy: selection slot, title, mono facts, pill, actions, chevron.
    expect(markup).toMatch(/aria-label="Select hello"[^>]*aria-pressed="false"/);
    expect(markup).toContain('Codex · test-model · 2h');
    expect(markup).toContain('aria-label="Archive task: hello"');
    expect(markup).toMatch(/<button[^>]*class="agt-trow__btn is-danger"[^>]*title="Stop the task before deleting"[^>]*aria-label="Delete task: hello"[^>]*disabled/);
    expect(markup).toMatch(/<button[^>]*class="agt-trow__btn is-danger"[^>]*title="Delete permanently"[^>]*aria-label="Delete task: trino issue reading"(?![^>]*disabled)/);
    expect(markup).toContain('lucide-chevron-right');
    // The composer stays anchored under the active list.
    expect(markup).toContain('aria-label="Describe a task"');
  });

  it('shows only shelved tasks on the archived tab, with restore and no composer', () => {
    const markup = renderToStaticMarkup(<AgentTaskList {...listProps} tasks={rows} loading={false} error={null} now={noon} initialTab="archived" />);

    expect(markup).toContain('Archived tasks keep their transcript and branch until deleted');
    expect(markup).toContain('Explain the query planner');
    expect(markup).not.toContain('trino issue reading');
    expect(markup).toContain('aria-label="Restore task: Explain the query planner"');
    expect(markup).toContain('class="agt-trow__title is-archived"');
    expect(markup).not.toContain('aria-label="Describe a task"');

    const empty = renderToStaticMarkup(<AgentTaskList {...listProps} tasks={rows.slice(0, 2)} loading={false} error={null} now={noon} initialTab="archived" />);
    expect(empty).toContain('Nothing archived');
    expect(empty).toContain('completed tasks move here after 7 days');
  });

  it('names the pull request a task opened or delivered right after its title', () => {
    const pullRequests: Record<string, PullRequestDto> = {
      't-yesterday': pullRequest(31101, 'Refactor a minor issue', 'merged'),
      't-today-2': pullRequest(482, 'Fix preview output limit', 'open', true),
    };
    const tasks = rows.map((row) => ({ ...row, pullRequest: pullRequests[row.id] ?? null }));
    // A pull request outranks the checkout: its row names the pull request even with changes known.
    const changes = { 't-yesterday': { uncommitted: true, commits: 2 } };
    const markup = renderToStaticMarkup(<AgentTaskList {...listProps} tasks={tasks} loading={false} error={null} now={noon} changes={changes} />);

    expect(markup).toContain('class="agt-trow__title">trino issue reading</span><span class="agt-trow__pr" title="#31101 Refactor a minor issue">');
    expect(markup).toMatch(/title="#31101 Refactor a minor issue"><svg[^>]*class="[^"]*lucide-git-merge[^"]*agt-pr-icon is-merged"[^>]*>.*?<\/svg>#31101 Refactor a minor issue<\/span><\/span>/);
    expect(markup).toMatch(/title="#482 Fix preview output limit"><svg[^>]*class="[^"]*lucide-git-pull-request-draft[^"]*agt-pr-icon is-draft"/);
    expect(markup).not.toContain('agt-trow__changes');
    // A task without one, and without a read of its checkout yet, shows its title alone.
    expect(markup).toContain('<span class="agt-trow__name"><span class="agt-trow__title">hello</span></span><span class="agt-mono agt-trow__meta">');
  });

  it('says whether a task without a pull request has changes, and whether they are committed', () => {
    const changes = {
      't-today': { uncommitted: false, commits: 0 },
      't-today-2': { uncommitted: true, commits: 0 },
      't-yesterday': { uncommitted: false, commits: 1 },
      't-wed': { uncommitted: true, commits: 3 },
    };
    const markup = renderToStaticMarkup(<AgentTaskList {...listProps} tasks={rows} loading={false} error={null} now={noon} changes={changes} />);
    const label = (title: string) => markup.match(new RegExp(`class="agt-trow__title">${title}</span><span class="agt-trow__changes"[^>]*>(.*?)</span>`))?.[1];

    expect(label('hello')).toBe('No changes');
    expect(label('Ask about the model')).toMatch(/^<svg[^>]*lucide-file-pen[^>]*>.*<\/svg>Uncommitted changes$/);
    expect(label('trino issue reading')).toMatch(/^<svg[^>]*lucide-git-commit-horizontal[^>]*>.*<\/svg>1 commit$/);
    expect(label('Image journey')).toMatch(/<\/svg>3 commits · uncommitted changes$/);
    expect(declarations('.agt-trow__pr,\n.agt-trow__changes')).toContain('text-overflow: ellipsis');
  });

  it('offers Archive or Restore in the task header beside Complete', () => {
    const render = (open: AgentTaskDto) => renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), capabilities: CAPABILITIES, task: open, connection: 'open' }}
        models={MODELS}
        {...TASK_BODY_NOOPS}
        onArchive={() => {}}
      />);

    const active = render(task('t-1', 'OPEN', 'x'));
    expect(active).toMatch(/<button[^>]*class="agt-headbtn is-shelf"[^>]*title="Archive"[^>]*aria-label="Archive"[^>]*><svg[^]*?<\/svg><\/button>/);
    expect(active).toContain('lucide-archive"');
    const shelved = render({ ...task('t-1', 'COMPLETED', 'x'), terminalAt: '2026-08-20T10:05:00Z', archivedAt: '2026-08-21T10:05:00Z' });
    expect(shelved).toMatch(/is-shelf"[^>]*title="Restore"[^>]*aria-label="Restore"[^>]*><svg[^]*?<\/svg><\/button>/);
    expect(shelved).toContain('lucide-archive-restore');
    // Without a handler the header offers nothing, as the static states are rendered.
    expect(body({ task: task('t-1', 'OPEN', 'x'), connection: 'open' })).not.toContain('is-shelf');
  });
});
