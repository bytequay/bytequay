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
import type { RealtimeCi } from '../types/brainView';

const STATUS_LABEL: Record<RealtimeCi['status'], string> = {
  green: 'Passing',
  failing: 'Failing',
  pending: 'Running',
  unknown: 'Unknown',
};

const CHECK_ICON: Record<RealtimeCi['checks'][number]['status'], string> = {
  ok: '✓',
  fail: '✕',
  pending: '◐',
};

function formatDuration(seconds: number | null): string | null {
  if (seconds == null || seconds <= 0) return null;
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

/**
 * Compact live CI status for a shipped task's PR — the overall state plus each
 * check, rendered at the top of the CI-fixing stage so the user can watch CI
 * (pending / passing / failing) without leaving the app. Driven by the
 * server-polled {@code realtimeCi}; a "View on GitHub" link opens the PR.
 */
export function CiStatusPanel({ ci, onOpenGitHub }: {
  ci: RealtimeCi;
  onOpenGitHub: () => void;
}) {
  return (
    <div className={`ci-panel ci-panel--${ci.status}`}>
      <div className="ci-panel__head">
        <span className="ci-panel__dot" aria-hidden />
        <span className="ci-panel__status">CI · {STATUS_LABEL[ci.status]}</span>
        <span className="ci-panel__grow" />
        <button type="button" className="ci-panel__link" onClick={onOpenGitHub}>
          View on GitHub ↗
        </button>
      </div>
      {ci.checks.length > 0 && (
        <ul className="ci-panel__checks">
          {ci.checks.map((check, i) => {
            const dur = formatDuration(check.durationSec);
            return (
              <li key={`${check.name}-${i}`} className={`ci-check ci-check--${check.status}`}>
                <span className="ci-check__icon" aria-hidden>{CHECK_ICON[check.status]}</span>
                <span className="ci-check__name">{check.name}</span>
                {dur !== null && <span className="ci-check__dur">{dur}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
