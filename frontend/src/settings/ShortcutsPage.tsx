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
import { Fragment } from 'react';
import { APP_NAVIGATION_SHORTCUTS } from '../appNavigation';
import { SettingsCard, SettingsRow } from './primitives';

const SCOPED_SHORTCUTS: { title: string; rows: { label: string; keys: string; detail?: string }[] }[] = [
  { title: 'App pages', rows: [
    { label: 'Open Recent search', keys: '⌘K' },
    { label: 'Go back / forward', keys: '⌘[ / ⌘]', detail: 'Editors and terminals keep their own shortcuts.' },
    { label: 'Find on this page', keys: '⌘F', detail: 'Also works in web pages. On a PR list, focuses its search instead.' },
    { label: 'Next / previous match', keys: 'Enter / ⇧Enter', detail: 'While Find is open; Escape closes it.' },
  ] },
  { title: 'Pull requests and Circles', rows: [
    { label: 'Select a PR list filter', keys: '⌘1–6', detail: 'Review, Open, My work, Monitored, Reviewed, Closed.' },
    { label: 'Select a Circle tab', keys: '⌘1–4', detail: 'Needs you, Activity, Members, Insights.' },
    { label: 'Submit a PR comment', keys: '⌘Enter', detail: 'In a comment editor; Escape cancels where available.' },
  ] },
  { title: 'Tasks and the Assistant', rows: [
    { label: 'Show all tasks', keys: '⇧⌘\\', detail: 'On a workspace task screen.' },
    { label: 'Send / add a new line', keys: 'Enter / ⇧Enter', detail: 'In an agent or Assistant composer.' },
    { label: 'Dictate', keys: 'Hold right ⌥', detail: 'In a composer while dictation is on; release to stop, Escape cancels.' },
    { label: 'Edit a queued message', keys: '↑', detail: 'When the task composer is empty and a queued message can be edited.' },
    { label: 'New Assistant conversation', keys: '⌘N', detail: 'While the Assistant is open.' },
    { label: 'Previous / next Assistant card', keys: '← / →', detail: 'When a card or its group is focused; Tab switches expanded groups.' },
  ] },
  { title: 'Recent search', rows: [
    { label: 'Switch items / words', keys: 'Tab' },
    { label: 'Select a result', keys: '↑ / ↓', detail: 'Control-P and Control-N also move through results.' },
    { label: 'Open the selected result', keys: 'Enter' },
    { label: 'Copy its link or text', keys: '⌘C' },
    { label: 'Copy its snippet', keys: '⇧⌘C', detail: 'For an item result; word results copy their text.' },
  ] },
  { title: 'Code editor', rows: [
    { label: 'Search everywhere', keys: '⇧⇧ / ⇧⌘F', detail: 'Press Shift twice.' },
    { label: 'Search the current file', keys: '⌘F' },
    { label: 'Go to definition', keys: '⌘B', detail: 'With the IDEA keymap.' },
    { label: 'Find usages', keys: '⌥F7', detail: 'With the IDEA keymap.' },
    { label: 'Show call hierarchy', keys: '⌃⌥H', detail: 'With the IDEA keymap.' },
    { label: 'Find a symbol / file', keys: '⌘O / ⇧⌘O', detail: 'With the IDEA keymap.' },
    { label: 'Find a symbol in this file', keys: '⌘F12', detail: 'With the IDEA keymap.' },
  ] },
  { title: 'Jira and Mail', rows: [
    { label: 'Focus Jira search', keys: '/', detail: 'On the Jira list, outside a text field.' },
    { label: 'Submit a Jira comment or field edit', keys: '⌘Enter', detail: 'While its editor is open.' },
    { label: 'Reply to mail', keys: 'R', detail: 'While reading a message, outside a text field.' },
  ] },
];

export default function ShortcutsPage() {
  return <div className="setw-page">
    <h1>Keyboard shortcuts</h1>
    <SettingsCard>
      <SettingsRow title="Mac keyboard" subtitle="⌘ Command · ⌥ Option · ⇧ Shift · ⌃ Control" />
    </SettingsCard>
    <h2 className="setw-section">Across ByteQuay</h2>
    <SettingsCard>
      {APP_NAVIGATION_SHORTCUTS.map(shortcut => <SettingsRow key={shortcut.page} title={shortcut.label}
        subtitle={shortcut.page === 'settings' ? 'Opens repository settings when you are in a workspace.'
          : shortcut.page === 'new-task' ? 'Focuses or opens the task composer in the current workspace. Send creates the task.'
          : shortcut.page === 'switch-task' ? 'Open tasks in the current workspace. Arrow keys select, Enter opens, Escape returns.' : undefined}>
        <kbd>{shortcut.hint}</kbd>
      </SettingsRow>)}
      <SettingsRow title="Toggle Assistant or task notices"><kbd>⌘J</kbd></SettingsRow>
      <SettingsRow title="Open the next waiting task"><kbd>⇧⌘J</kbd></SettingsRow>
      <SettingsRow title="Open the Assistant from any app"><kbd>⌥Space</kbd></SettingsRow>
      <div className="setw-foot-row"><span>These work from app pages, web pages, and the code editor. Task commands open Workspaces when no workspace is selected. Finish or dismiss an open form before navigating.</span></div>
    </SettingsCard>
    {SCOPED_SHORTCUTS.map(group => <Fragment key={group.title}>
      <h2 className="setw-section">{group.title}</h2>
      <SettingsCard>
        {group.rows.map(row => <SettingsRow key={row.label} title={row.label} subtitle={row.detail}><kbd>{row.keys}</kbd></SettingsRow>)}
      </SettingsCard>
    </Fragment>)}
    <p className="setw-footnote">Each group applies only in that view. Standard editing and window shortcuts appear in the Mac menu bar; other editor shortcuts follow your selected keymap.</p>
  </div>;
}
