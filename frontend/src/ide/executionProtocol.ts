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
import type { LocalRunClassDto } from '../pulls/code-tests/contracts';
import { flag, object } from '../pulls/wireParse';
import { workspacePath } from './codeQuery';

export type ExecutionStart = { scope: 'TESTS' | 'DEBUG' | 'RUN'; label: string; path: string | null };
export type ExecutionOutput = { sequence: number; text: string; truncated: boolean };
export type ExecutionResult = { cancelled: boolean; failed: boolean; incomplete: boolean; exitCode: number | null; tests: LocalRunClassDto[] };
export type ExecutionRequest =
  | { operation: 'start'; request: ExecutionStart }
  | { operation: 'output'; id: string; request: ExecutionOutput }
  | { operation: 'finish'; id: string; request: ExecutionResult }
  | { operation: 'stop'; id: string };

function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length > max) throw new Error('Invalid execution text');
  return value;
}
function integer(value: unknown): number {
  if (!Number.isSafeInteger(value)) throw new Error('Invalid execution number');
  return value as number;
}
function duration(value: unknown): number | null {
  if (value == null) return null;
  const result = integer(value);
  if (result < 0) throw new Error('Invalid execution duration');
  return result;
}

/** Only execution metadata crosses this channel; owner, session, filesystem paths and executable commands come from main/backend. */
export function parseExecutionRequest(value: unknown): ExecutionRequest {
  const input = object(value);
  if (input.operation === 'start') {
    const request = object(input.request);
    if (!['TESTS', 'DEBUG', 'RUN'].includes(String(request.scope)) || (request.path !== null && !workspacePath(request.path))) throw new Error('Invalid execution scope');
    const label = text(request.label, 2000);
    if (!label.trim()) throw new Error('Missing execution label');
    return { operation: 'start', request: { scope: request.scope as ExecutionStart['scope'], label, path: request.path as string | null } };
  }
  const id = text(input.id, 64);
  if (!id || !/^[a-z0-9-]+$/i.test(id)) throw new Error('Invalid execution id');
  if (input.operation === 'stop') return { operation: 'stop', id };
  const request = object(input.request);
  if (input.operation === 'output') {
    const sequence = integer(request.sequence);
    if (sequence < 1) throw new Error('Invalid output sequence');
    return { operation: 'output', id, request: { sequence, text: text(request.text, 65_536), truncated: flag(request.truncated) } };
  }
  if (input.operation !== 'finish' || !Array.isArray(request.tests) || request.tests.length > 1000) throw new Error('Invalid execution result');
  let characters = 0;
  let count = 0;
  const tests: LocalRunClassDto[] = request.tests.map(value => {
    const group = object(value);
    const className = text(group.className, 1024);
    if (!className.trim() || !Array.isArray(group.methods)) throw new Error('Invalid test group');
    characters += className.length;
    count += group.methods.length;
    if (count > 2000) throw new Error('Too many test results');
    const methods = group.methods.map(value => {
      const item = object(value);
      const name = text(item.name, 2000);
      if (!name.trim() || !['PASSED', 'FAILED', 'ERROR', 'SKIPPED'].includes(String(item.status))) throw new Error('Invalid test result');
      const message = item.message == null ? null : text(item.message, 16_384);
      const trace = item.trace == null ? null : text(item.trace, 32_768);
      characters += name.length + (message?.length ?? 0) + (trace?.length ?? 0);
      if (characters > 1_048_576) throw new Error('Test evidence is too large');
      return { name, status: item.status as LocalRunClassDto['methods'][number]['status'], durationMs: duration(item.durationMs), message, trace };
    });
    return { className, durationMs: duration(group.durationMs), methods };
  });
  const exitCode = request.exitCode == null ? null : integer(request.exitCode);
  if (exitCode !== null && (exitCode < -2_147_483_648 || exitCode > 2_147_483_647)) throw new Error('Invalid exit code');
  return { operation: 'finish', id, request: { cancelled: flag(request.cancelled), failed: flag(request.failed), incomplete: flag(request.incomplete),
    exitCode, tests } };
}
