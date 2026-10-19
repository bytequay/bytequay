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

import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it, vi } from 'vitest';
import { createJiraBridge } from './preload';
import { JiraPage, JiraTicketLayer, JiraHostProvider, supportedTransitionField, transitionPayload, type JiraHost } from './renderer';

it('renders settings and ticket surfaces using explicit host services without desktop globals', () => {
  const invoke = vi.fn();
  const host: JiraHost = {
    bridge: createJiraBridge({ invoke }),
    openExternal: vi.fn(), openInAppBrowser: vi.fn(), settingsUrl: '#/jira-settings', generalSettingsUrl: '#/general-settings',
    route: () => '#/jira', location: () => null, openTicket: vi.fn(), openTask: vi.fn(), openWorkItem: vi.fn(),
    createLocalChild: vi.fn(), useCovered: () => false, useTasks: () => ({}),
    useTicketActivity: () => ({ complete: vi.fn(), onAction: vi.fn() }),
    useEpic: () => ({ available: false, tabs: null, content: null }),
    IssueActions: () => null, WorkspaceDialog: () => null, Launch: () => null, Pin: () => null,
    Markdown: ({ body }) => <p>{body}</p>, BetterWords: () => <button type="button">Better words</button>,
  };
  const settings = renderToStaticMarkup(<JiraHostProvider host={host}><JiraPage /></JiraHostProvider>);
  expect(settings).toContain('Jira');
  const ticket = renderToStaticMarkup(<JiraHostProvider host={host}><JiraTicketLayer location={{ connectionId: 'c', cloudId: 's', issueId: 'i' }} onClose={vi.fn()} /></JiraHostProvider>);
  expect(ticket).toContain('Loading ticket');
  expect(invoke).not.toHaveBeenCalled();
});

it('submits supported transition values and refuses complex required editors', () => {
    const fields = [
      { id: 'estimate', name: 'Estimate', required: true, schemaType: 'number', allowedValues: [] },
      { id: 'resolution', name: 'Resolution', required: true, schemaType: 'resolution', allowedValues: [{ id: 'fixed', name: 'Fixed' }] },
      { id: 'approvers', name: 'Approvers', required: true, schemaType: 'array', allowedValues: [{ id: 'user', name: 'Person' }] },
      { id: 'description', name: 'Description', required: false, schemaType: 'string', allowedValues: [] },
    ];
    expect(fields.map(supportedTransitionField)).toEqual([true, true, false, false]);
    expect(transitionPayload(fields, { estimate: '2.5', resolution: 'fixed', approvers: 'user', description: 'not a rich-text document' })).toEqual({ estimate: 2.5, resolution: 'fixed' });
  });
