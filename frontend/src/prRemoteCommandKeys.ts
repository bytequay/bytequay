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
import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export function isPendingManualValidationResponse(status: number): boolean {
  return status === 202;
}

export class PrRemoteCommandKeys {
  private pending: Map<string, string>;

  constructor(
    private readonly next: () => string = randomUUID,
    private readonly storagePath?: string,
  ) {
    this.pending = storagePath === undefined
      ? new Map()
      : loadPending(storagePath);
  }

  acquire(intent: string): string {
    const key = intentKey(intent);
    const commandId = this.pending.get(key) ?? this.next();
    const next = new Map(this.pending).set(key, commandId);
    this.replace(next);
    return commandId;
  }

  complete(intent: string): void {
    const next = new Map(this.pending);
    next.delete(intentKey(intent));
    this.replace(next);
  }

  completeCommand(commandId: string): void {
    const next = new Map(
      [...this.pending].filter(([, pendingCommandId]) => pendingCommandId !== commandId),
    );
    this.replace(next);
  }

  private replace(next: Map<string, string>): void {
    if (this.storagePath !== undefined) {
      persistPending(this.storagePath, next);
    }
    this.pending = next;
  }
}

function intentKey(intent: string): string {
  return createHash('sha256').update(intent).digest('hex');
}

function loadPending(storagePath: string): Map<string, string> {
  let encoded: string;
  try {
    encoded = readFileSync(storagePath, 'utf8');
  } catch (error) {
    if (isNodeError(error) && error.code === 'ENOENT') return new Map();
    throw new Error(`Could not read pending remote command keys at ${storagePath}`, { cause: error });
  }

  let value: unknown;
  try {
    value = JSON.parse(encoded);
  } catch (error) {
    throw new Error(`Pending remote command keys at ${storagePath} are invalid`, { cause: error });
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`Pending remote command keys at ${storagePath} are invalid`);
  }

  const pending = new Map<string, string>();
  for (const [key, commandId] of Object.entries(value)) {
    if (!/^[a-f0-9]{64}$/.test(key)
      || typeof commandId !== 'string'
      || commandId.length === 0) {
      throw new Error(`Pending remote command keys at ${storagePath} are invalid`);
    }
    pending.set(key, commandId);
  }
  return pending;
}

function persistPending(storagePath: string, pending: Map<string, string>): void {
  mkdirSync(dirname(storagePath), { recursive: true });
  const temporaryPath = `${storagePath}.${process.pid}.tmp`;
  writeFileSync(
    temporaryPath,
    `${JSON.stringify(Object.fromEntries(pending))}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
  renameSync(temporaryPath, storagePath);
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error;
}
