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
/* eslint-disable import/no-unresolved -- Pinned VS Code test result types. */
import { renderAsPlaintext } from '@codingame/monaco-vscode-api/vscode/vs/base/browser/markdownRenderer';
import type { ITestResult } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/testing/common/testResult';
import type { TestResultItem } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/testing/common/testTypes';
import type { ExecutionResult } from './executionProtocol';

/** Native states describe individual tests; computed parent states must never count as extra passing tests. */
export function testEvidence(results: Iterable<ITestResult>): Pick<ExecutionResult, 'tests' | 'incomplete'> {
  const tests: ExecutionResult['tests'] = [];
  let incomplete = false;
  let count = 0;
  let remaining = 1_048_576;
  const bounded = (value: string, limit: number) => {
    const result = value.slice(0, Math.min(limit, remaining));
    remaining -= result.length;
    incomplete ||= result.length !== value.length;
    return result;
  };
  const label = (value: string) => value.replace(/\$\([a-z][\w~-]*\)/gi, '').trim();
  const duration = (value: number | undefined) => value === undefined || !Number.isFinite(value) || value < 0 ? null : Math.round(value);
  for (const result of results) {
    const items = [...result.tests];
    const byId = new Map(items.map(item => [item.item.extId, item]));
    const parents = new Set(items.map(item => item.item.extId.slice(0, item.item.extId.lastIndexOf('\0'))));
    const failedParents = new Set<string>();
    for (const item of items) if (item.ownComputedState === 4 || item.ownComputedState === 6) {
      let prefix = item.item.extId;
      while (prefix.includes('\0')) { prefix = prefix.slice(0, prefix.lastIndexOf('\0')); failedParents.add(prefix); }
    }
    const groups = new Map<string, ExecutionResult['tests'][number]>();
    for (const item of items) {
      const state = item.ownComputedState;
      if (parents.has(item.item.extId) && ((state !== 4 && state !== 6) || failedParents.has(item.item.extId))) continue;
      if (state === 0) continue;
      if (state === 1 || state === 2) { incomplete = true; continue; }
      if (count >= 2000 || remaining < 4096) { incomplete = true; break; }
      const chain: TestResultItem[] = [item];
      let parent = item.item.extId.slice(0, item.item.extId.lastIndexOf('\0'));
      while (byId.has(parent)) {
        const ancestor = byId.get(parent);
        if (!ancestor) break;
        chain.unshift(ancestor);
        const split = parent.lastIndexOf('\0');
        if (split < 0) break;
        parent = parent.slice(0, split);
      }
      const groupId = item.item.extId.slice(0, item.item.extId.lastIndexOf('\0'));
      let group = groups.get(groupId);
      if (!group) {
        if (tests.length >= 1000) { incomplete = true; break; }
        group = { className: bounded(chain.slice(0, -1).map(value => label(value.item.label)).join(' › ') || label(result.name) || 'Tests', 1024), durationMs: null, methods: [] };
        groups.set(groupId, group); tests.push(group);
      }
      const messages: string[] = [];
      const traces: string[] = [];
      for (const task of item.tasks) for (const message of task.messages) {
        if (message.type !== 0) continue;
        // Java test failures use Markdown links and HTML line breaks; retain their text, never command links.
        messages.push(renderAsPlaintext(typeof message.message === 'string' ? message.message
          : { ...message.message, value: message.message.value.replace(/<br\s*\/?>/gi, '\n') }));
        if (message.expected !== undefined) traces.push(`Expected: ${message.expected}`);
        if (message.actual !== undefined) traces.push(`Actual: ${message.actual}`);
        if (message.location) traces.push(`${message.location.uri.toString()}:${message.location.range.startLineNumber}`);
        for (const frame of message.stackTrace ?? []) traces.push(`${frame.label}${frame.uri ? ` (${frame.uri.toString()}:${frame.position?.lineNumber ?? 1})` : ''}`);
      }
      if (item.item.uri) traces.push(`${item.item.uri.toString()}:${item.item.range?.startLineNumber ?? 1}`);
      // Include the stable controller ID to distinguish parameterized cases sharing a display name.
      traces.push(`Test ID: ${item.item.extId.replaceAll('\0', ' › ')}`);
      const message = bounded(messages.join('\n'), 16_384).trim() || null;
      const trace = bounded(traces.join('\n'), 32_768).trim() || null;
      const name = bounded(label(item.item.label) || item.item.extId.replaceAll('\0', '/'), 2000).trim();
      if (!name) { incomplete = true; break; }
      group.methods.push({ name, status: state === 3 ? 'PASSED' : state === 4 ? 'FAILED' : state === 5 ? 'SKIPPED' : 'ERROR',
        durationMs: duration(item.ownDuration), message, trace });
      count++;
    }
  }
  return { tests, incomplete };
}
