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

import type { ThreadStreamEvent } from '../types';

/** A short-lived tool row shown only while the current agent turn runs. */
export type LiveActivity = {
  callId: string;
  label: string;
  detail: string | null;
  startedAt: number;
  done: boolean;
  failed: boolean;
};

const MAX_ACTIVITY_ROWS = 4;

/**
 * Reduce shared thread-stream events into the compact, non-persistent log
 * shown beneath the working indicator. Tool rows already have a durable
 * operational-evidence path; this merely makes them visible immediately.
 */
export function updateLiveActivities(
  activities: LiveActivity[], event: ThreadStreamEvent,
): LiveActivity[] {
  if (event.name === 'TurnDone' || event.name === 'SessionEnded') return [];
  if (event.name === 'ToolCallStarted') {
    const callId = stringField(event.data.callId);
    if (callId === '') return activities;
    const { label, detail } = describeTool(stringField(event.data.toolName), stringField(event.data.inputJson));
    return [...activities, {
      callId,
      label,
      detail,
      startedAt: eventTime(event.data.timestamp),
      done: false,
      failed: false,
    }].slice(-MAX_ACTIVITY_ROWS);
  }
  if (event.name === 'ToolCallDone') {
    const callId = stringField(event.data.callId);
    const failed = event.data.isError === true;
    return activities.map(row => row.callId === callId ? { ...row, done: true, failed } : row);
  }
  return activities;
}

function describeTool(toolName: string, inputJson: string): { label: string; detail: string | null } {
  const input = parseInput(inputJson);
  const detail = firstString(input.command, input.path, input.file_path, input.query, input.pattern, input.text);
  const name = toolName.toLowerCase();
  if (name.includes('grep') || name.includes('search')) return { label: 'Searching', detail };
  if (name.includes('glob')) return { label: 'Finding files', detail };
  if (name.includes('read')) return { label: 'Reading', detail };
  if (name.includes('command') || name.includes('shell') || name === 'bash') {
    return { label: 'Running command', detail };
  }
  return { label: toolName === '' ? 'Using tool' : `Using ${toolName}`, detail };
}

function parseInput(inputJson: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(inputJson);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : {};
  }
  catch {
    return {};
  }
}

function firstString(...values: unknown[]): string | null {
  for (const value of values) {
    if (typeof value === 'string' && value.trim() !== '') return value;
  }
  return null;
}

function stringField(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function eventTime(value: unknown): number {
  if (typeof value !== 'string') return Date.now();
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}
