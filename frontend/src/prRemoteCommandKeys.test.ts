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
import { describe, expect, it } from 'vitest';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  isPendingManualValidationResponse,
  PrRemoteCommandKeys,
} from './prRemoteCommandKeys';

describe('PrRemoteCommandKeys', () => {
  it('keeps transport retries stable and rotates after a definitive response', () => {
    const generated = ['command-1', 'command-2'];
    const keys = new PrRemoteCommandKeys(() => {
      const commandId = generated.shift();
      if (commandId === undefined) throw new Error('no test command id');
      return commandId;
    });

    expect(keys.acquire('run-tests:pr-1')).toBe('command-1');
    expect(keys.acquire('run-tests:pr-1')).toBe('command-1');

    keys.complete('run-tests:pr-1');
    expect(keys.acquire('run-tests:pr-1')).toBe('command-2');
  });

  it('retains a pending validation command and rotates after a terminal response', () => {
    const generated = ['command-1', 'command-2'];
    const keys = new PrRemoteCommandKeys(() => {
      const commandId = generated.shift();
      if (commandId === undefined) throw new Error('no test command id');
      return commandId;
    });
    const intent = 'run-tests:pr-1';

    expect(keys.acquire(intent)).toBe('command-1');
    expect(isPendingManualValidationResponse(202)).toBe(true);
    expect(keys.acquire(intent)).toBe('command-1');

    expect(isPendingManualValidationResponse(409)).toBe(false);
    expect(isPendingManualValidationResponse(500)).toBe(false);
    keys.complete(intent);
    expect(keys.acquire(intent)).toBe('command-2');
  });

  it('reuses a pending command after process restart without storing its payload', () => {
    const directory = mkdtempSync(join(tmpdir(), 'bytequay-command-keys-'));
    const storagePath = join(directory, 'pending.json');
    const intent = 'comment:pr-1:{"body":"private review text"}';
    try {
      const first = new PrRemoteCommandKeys(() => 'command-1', storagePath);
      expect(first.acquire(intent)).toBe('command-1');

      const restarted = new PrRemoteCommandKeys(() => 'command-2', storagePath);
      expect(restarted.acquire(intent)).toBe('command-1');
      expect(readFileSync(storagePath, 'utf8')).not.toContain('private review text');

      restarted.complete(intent);
      const completed = new PrRemoteCommandKeys(() => 'command-2', storagePath);
      expect(completed.acquire(intent)).toBe('command-2');
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  it('completes a restored command without reconstructing its private intent', () => {
    const generated = ['command-1', 'command-2'];
    const keys = new PrRemoteCommandKeys(() => {
      const commandId = generated.shift();
      if (commandId === undefined) throw new Error('no test command id');
      return commandId;
    });

    expect(keys.acquire('publish-review:pr-1:{"body":"private review text"}'))
      .toBe('command-1');
    keys.completeCommand('command-1');
    expect(keys.acquire('publish-review:pr-1:{"body":"private review text"}'))
      .toBe('command-2');
  });

  it('fails closed when persisted command identity is corrupt', () => {
    const directory = mkdtempSync(join(tmpdir(), 'bytequay-command-keys-'));
    const storagePath = join(directory, 'pending.json');
    try {
      const seed = new PrRemoteCommandKeys(() => 'command-1', storagePath);
      seed.acquire('approve:pr-1');
      const encoded = readFileSync(storagePath, 'utf8');
      expect(encoded).toContain('command-1');

      writeFileSync(storagePath, '{broken', 'utf8');
      expect(() => new PrRemoteCommandKeys(() => 'command-2', storagePath))
        .toThrow(/invalid/);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
});
