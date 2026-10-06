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
import type { IdeConnection } from './contracts';

/** Semantic requests select a fixed operation; neither paths nor commands can select another workspace. */
export const CODE_OPERATIONS = ['SYMBOLS', 'DOCUMENT_SYMBOLS', 'DEFINITIONS', 'REFERENCES', 'IMPLEMENTATIONS', 'INCOMING_CALLS', 'OUTGOING_CALLS', 'DIAGNOSTICS'] as const;
export type CodeOperation = typeof CODE_OPERATIONS[number];
export type CodeQuery = { operation: CodeOperation; path: string; query?: string; line?: number; column?: number; limit: number };
export type CodeQueryRequest = { id: string; workspaceId: string; taskId: string; expiresAt: number; query: CodeQuery; connection: IdeConnection };
export type CodeQueryRange = { line: number; column: number; endLine: number; endColumn: number };
export type CodeQueryItem = CodeQueryRange & { path: string; name?: string; kind?: number; message?: string; severity?: number;
  calls?: (CodeQueryRange & { path: string })[] };
export type CodeQueryStatus = 'READY' | 'UPDATING' | 'UNSUPPORTED' | 'UNAVAILABLE' | 'CANCELLED' | 'STALE' | 'UNSAVED';
export type CodeQueryReply = { status: CodeQueryStatus; items: CodeQueryItem[]; truncated: boolean; message: string };

export function workspacePath(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 4096 && !value.includes('\\') && ![...value].some(character => character.charCodeAt(0) < 32)
    && value.split('/').every(part => part !== '' && part !== '.' && part !== '..' && part.toLowerCase() !== '.git');
}

export function parseCodeQuery(value: unknown): CodeQuery {
  if (!value || typeof value !== 'object') throw new Error('Invalid code query');
  const q = value as Record<string, unknown>;
  if (!CODE_OPERATIONS.includes(q.operation as CodeOperation) || !workspacePath(q.path)
    || !Number.isSafeInteger(q.limit) || Number(q.limit) < 1 || Number(q.limit) > 200
    || (q.query != null && (typeof q.query !== 'string' || q.query.length > 200))
    || [q.line, q.column].some(position => position != null && (!Number.isSafeInteger(position) || Number(position) < 1))) throw new Error('Invalid code query');
  const operation = q.operation as CodeOperation;
  if (operation === 'SYMBOLS' && (typeof q.query !== 'string' || !q.query.trim())) throw new Error('A symbol query is required');
  if (['DEFINITIONS', 'REFERENCES', 'IMPLEMENTATIONS', 'INCOMING_CALLS', 'OUTGOING_CALLS'].includes(operation)
    && (!Number.isSafeInteger(q.line) || Number(q.line) < 1 || !Number.isSafeInteger(q.column) || Number(q.column) < 1)) throw new Error('A source position is required');
  return { operation, path: q.path, limit: Number(q.limit), ...(q.query == null ? {} : { query: q.query as string }),
    ...(q.line == null ? {} : { line: q.line as number }), ...(q.column == null ? {} : { column: q.column as number }) };
}

export function queryFailure(status: CodeQueryStatus, message: string): CodeQueryReply {
  return { status, items: [], truncated: false, message };
}

/** IPC is untrusted, including replies. Bound every repository-derived string before the backend transport. */
export function parseCodeQueryReply(value: unknown): CodeQueryReply {
  if (!value || typeof value !== 'object') throw new Error('Invalid code query result');
  const result = value as CodeQueryReply;
  if (!['READY', 'UPDATING', 'UNSUPPORTED', 'UNAVAILABLE', 'CANCELLED', 'STALE', 'UNSAVED'].includes(result.status)
    || typeof result.message !== 'string' || result.message.length > 4000 || typeof result.truncated !== 'boolean'
    || !Array.isArray(result.items) || result.items.length > 200) throw new Error('Invalid code query result');
  const range = (item: CodeQueryRange & { path: string }) => {
    if (!item || !workspacePath(item.path) || ![item.line, item.column, item.endLine, item.endColumn].every(n => Number.isSafeInteger(n) && n > 0)
      || item.endLine < item.line || (item.line === item.endLine && item.endColumn < item.column)) throw new Error('Invalid code query location');
    return { path: item.path, line: item.line, column: item.column, endLine: item.endLine, endColumn: item.endColumn };
  };
  const items = result.items.map(item => {
    const location = range(item);
    for (const key of ['name', 'message'] as const) if (item[key] !== undefined && (typeof item[key] !== 'string' || item[key].length > 2000)) throw new Error('Invalid code query text');
    for (const key of ['kind', 'severity'] as const) if (item[key] !== undefined && !Number.isSafeInteger(item[key])) throw new Error('Invalid code query kind');
    if (item.calls !== undefined && (!Array.isArray(item.calls) || item.calls.length > 40)) throw new Error('Invalid call locations');
    return { ...location, name: item.name, kind: item.kind, message: item.message, severity: item.severity, calls: item.calls?.map(range) ?? [] };
  });
  if (result.status !== 'READY' && items.length !== 0) throw new Error('Unavailable queries cannot carry results');
  return { status: result.status, items, truncated: result.truncated, message: result.message };
}
