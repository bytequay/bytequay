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

/**
 * Strict primitives for reading a backend payload.
 *
 * Callers name the field they are reading, so the message says what was malformed without
 * naming the service it came from.
 */

export function object(value: unknown, what = 'object'): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`Malformed ${what}`);
  }
  return value as Record<string, unknown>;
}

export function list(value: unknown, what = 'array'): unknown[] {
  if (!Array.isArray(value)) throw new Error(`Malformed ${what}`);
  return value;
}

export function text(value: unknown, what = 'text'): string {
  if (typeof value !== 'string') throw new Error(`Malformed ${what}`);
  return value;
}

export function optionalText(value: unknown, what?: string): string | null {
  if (value == null) return null;
  return what === undefined ? (typeof value === 'string' ? value : null) : text(value, what);
}

export function count(value: unknown, what = 'number'): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`Malformed ${what}`);
  return value;
}

export function optionalCount(value: unknown, what?: string): number | null {
  if (value == null) return null;
  return what === undefined ? (typeof value === 'number' && Number.isFinite(value) ? value : null) : count(value, what);
}

export function oneOf<T extends string>(value: unknown, allowed: readonly T[], what: string): T {
  const found = allowed.find(candidate => candidate === value);
  if (found === undefined) throw new Error(`Malformed ${what}`);
  return found;
}

export function flag(value: unknown, what = 'flag'): boolean {
  if (typeof value !== 'boolean') throw new Error(`Malformed ${what}`);
  return value;
}
