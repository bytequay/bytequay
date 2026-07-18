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
import type { ThreadTurnEventDto } from '../types';

export type CodeGraphPolicyMetrics = {
  redirected: number;
  attempted: number;
  succeeded: number;
  failed: number;
  fallback: number;
  ignored: number;
};

const EMPTY: CodeGraphPolicyMetrics = {
  redirected: 0,
  attempted: 0,
  succeeded: 0,
  failed: 0,
  fallback: 0,
  ignored: 0,
};

export function summarizeCodeGraphPolicy(events: ThreadTurnEventDto[]): CodeGraphPolicyMetrics {
  return events.reduce((total, event) => {
    if (event.event !== 'CODEGRAPH_POLICY' || event.message === null) return total;
    try {
      const row = JSON.parse(event.message) as Partial<CodeGraphPolicyMetrics>;
      return {
        redirected: total.redirected + count(row.redirected),
        attempted: total.attempted + count(row.attempted),
        succeeded: total.succeeded + count(row.succeeded),
        failed: total.failed + count(row.failed),
        fallback: total.fallback + count(row.fallback),
        ignored: total.ignored + count(row.ignored),
      };
    }
    catch {
      return total;
    }
  }, { ...EMPTY });
}

export function formatCodeGraphPolicy(metrics: CodeGraphPolicyMetrics): string {
  const parts = [
    `${metrics.succeeded}/${metrics.attempted} graph`,
    `${metrics.redirected} redirected`,
  ];
  if (metrics.failed > 0) parts.push(`${metrics.failed} failed`);
  if (metrics.fallback > 0) parts.push(`${metrics.fallback} fallback`);
  if (metrics.ignored > 0) parts.push(`${metrics.ignored} ignored`);
  return parts.join(' · ');
}

export function hasCodeGraphPolicyMetrics(metrics: CodeGraphPolicyMetrics): boolean {
  return Object.values(metrics).some(value => value > 0);
}

function count(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
