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
import type { LocalPRCheck, LocalPRCheckKind } from '../../types/localPr';

const ICON_CLS: Record<string, string> = {
  passed: 'pass',
  failed: 'fail',
  running: 'run',
  pending: 'run',
  neutral: 'pass',
};

const ICON_GLYPH: Record<string, string> = {
  passed: '✓',
  failed: '✗',
  running: '●',
  pending: '·',
  neutral: '–',
};

function statusLine(checks: LocalPRCheck[], kind: LocalPRCheckKind): string {
  if (checks.length === 0) {
    return kind === 'remote' ? '— not started · waiting for push' : '— no runs yet';
  }
  const passed = checks.filter(c => c.status === 'passed' || c.status === 'neutral').length;
  const failed = checks.some(c => c.status === 'failed');
  if (failed) return `✗ ${passed} of ${checks.length} passed`;
  const running = checks.some(c => c.status === 'running' || c.status === 'pending');
  return running ? `running · ${passed}/${checks.length}` : `✓ ${passed} of ${checks.length} passed`;
}

/**
 * One LOCAL or REMOTE checks card (decision #54 Checks section). `dim` renders
 * the whole card at half opacity — the local-mode REMOTE placeholder (awaiting
 * push) and the remote-mode LOCAL history both render dim.
 */
export function PRChecksCard({
  kind, title, checks, dim = false,
}: {
  kind: LocalPRCheckKind;
  title: string;
  checks: LocalPRCheck[];
  dim?: boolean;
}) {
  return (
    <div className="pr-checks-card" style={dim ? { opacity: 0.5 } : undefined}>
      <div className="checks-head">
        <span className={`kind-badge ${kind}`}>{kind}</span>
        {title}
        <span className="status-line">{statusLine(checks, kind)}</span>
      </div>
      {checks.map(c => (
        <div className="check-row" key={c.id}>
          <span className={`ic ${ICON_CLS[c.status] ?? 'run'}`}>{ICON_GLYPH[c.status] ?? '·'}</span>
          <span className="nm">{c.name}</span>
          <span className="dur">{c.durationMs !== null ? `${Math.round(c.durationMs / 1000)}s` : ''}</span>
        </div>
      ))}
    </div>
  );
}
