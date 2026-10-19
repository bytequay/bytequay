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
import type { ReactNode } from 'react';

/** One concern. Rows inside are separated by hairlines; a footer holds the card's one add action. */
export function SettingsCard({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  return (
    <section className="setw-card">
      {children}
      {footer !== undefined && <div className="setw-card-foot">{footer}</div>}
    </section>
  );
}

/** One setting: a title, at most one short subtitle, and right-aligned controls. */
export function SettingsRow({ title, badge, subtitle, children }: {
  title: ReactNode;
  badge?: ReactNode;
  subtitle?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="setw-row">
      <div className="setw-row-main">
        <div className="setw-row-title">
          {title}
          {badge}
        </div>
        {subtitle !== undefined && subtitle !== null && <div className="setw-row-sub">{subtitle}</div>}
      </div>
      {children !== undefined && <div className="setw-row-controls">{children}</div>}
    </div>
  );
}

export function Badge({ children, tone }: { children: ReactNode; tone?: 'warn' }) {
  return <span className={`setw-badge${tone === 'warn' ? ' is-warn' : ''}`}>{children}</span>;
}

export function StatusDot({ tone }: { tone: 'ok' | 'warn' | 'bad' | 'idle' }) {
  return <span className={`setw-dot${tone === 'idle' ? '' : ` is-${tone}`}`} />;
}

/** The readiness badge's tone as a dot: the prototype shows a dot, the model layer speaks in badges. */
export function dotTone(badgeTone: string): 'ok' | 'warn' | 'bad' | 'idle' {
  if (badgeTone === 'is-ok') return 'ok';
  if (badgeTone === 'is-warn') return 'warn';
  if (badgeTone === 'is-danger') return 'bad';
  return 'idle';
}

export function Toggle({ on, label, disabled, onChange }: {
  on: boolean;
  label: string;
  disabled?: boolean;
  onChange: (next: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      disabled={disabled}
      className={`setw-toggle${on ? ' is-on' : ''}`}
      onClick={() => onChange(!on)}
    >
      <span className="setw-toggle-knob" />
    </button>
  );
}
