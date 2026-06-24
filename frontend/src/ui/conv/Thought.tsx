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
import { useState } from 'react';
import type { ReactNode } from 'react';

/**
 * The "💭 Thought for Xs" extended-thinking indicator. With no `children`
 * it's just the one-line indicator; with `children` it becomes a
 * collapsible disclosure (header + the reasoning body), collapsed by
 * default — the Copilot pattern for showing thinking depth without it
 * dominating the feed.
 */
export function Thought({ seconds, label, children, defaultOpen = false }: {
  seconds?: number;
  label?: string;
  children?: ReactNode;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const text = label ?? `Thought for ${seconds ?? 0}s`;

  if (children === undefined) {
    return (
      <div className="thought">
        <span className="ic" aria-hidden>💭</span>
        <span>{text}</span>
      </div>
    );
  }

  return (
    <div className="thought-block">
      <button
        type="button"
        className="thought-block__head"
        aria-expanded={open}
        onClick={() => setOpen(o => !o)}
      >
        <span className="disc" aria-hidden>{open ? '▾' : '▸'}</span>
        <span className="ic" aria-hidden>💭</span>
        <span>{text}</span>
      </button>
      {open && <div className="thought-block__body">{children}</div>}
    </div>
  );
}
