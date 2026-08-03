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
    const { label, detail } = describeTool(
      stringField(event.data.toolName), stringField(event.data.inputJson));
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

/** The one arg worth showing, in priority order. The search pattern beats the
 *  path it was scoped to: a run of Greps under one directory otherwise renders
 *  as a column of identical rows. */
const DETAIL_FIELDS = ['pattern', 'query', 'command', 'file_path', 'path', 'text'] as const;

function describeTool(
  toolName: string, inputJson: string,
): { label: string; detail: string | null } {
  const input = parseInput(inputJson);
  const field = DETAIL_FIELDS.find(key => {
    const value = input[key];
    return typeof value === 'string' && value.trim() !== '';
  });
  const detail = field === undefined ? null : input[field] as string;
  const name = toolName.toLowerCase();
  const described = (label: string) => ({ label, detail });
  if (name.includes('grep') || name.includes('search')) return described('Searching');
  if (name.includes('glob')) return described('Finding files');
  if (name.includes('read')) return described('Reading');
  if (name.includes('write') || name.includes('edit')) return described('Writing');
  if (name.includes('command') || name.includes('shell') || name === 'bash') {
    return described('Running command');
  }
  return described(toolName === '' ? 'Using tool' : `Using ${toolName}`);
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

function stringField(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function eventTime(value: unknown): number {
  if (typeof value !== 'string') return Date.now();
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}
