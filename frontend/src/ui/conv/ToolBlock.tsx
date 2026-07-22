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
 * A collapsible tool-call block — the single way every tool (`Bash`,
 * `Edit`, `Read`, `record_plan`, `note_plan_concern`, …) renders. The
 * header shows a coloured tag + one-line description + optional meta; the
 * code body discloses on click. `tag="plan"` tints the tag purple for the
 * plan-family tools. Controlled when `open`/`onToggle` are supplied,
 * otherwise self-managed.
 */
export function ToolBlock({ tag, plan = false, desc, descTail = false, meta, children, open, onToggle, defaultOpen = false, icon }: {
  tag: ReactNode;
  /** Purple plan-family tag tint. */
  plan?: boolean;
  desc: ReactNode;
  /** Head-truncate the desc (ellipsis on the left) so a file path's tail
   *  stays visible instead of its interchangeable directory prefix. */
  descTail?: boolean;
  meta?: ReactNode;
  /** The code/result body, shown when expanded. */
  children?: ReactNode;
  open?: boolean;
  onToggle?: () => void;
  defaultOpen?: boolean;
  /** Verb icon before the tag (Run/Read/MCP…, Task Conversation design). */
  icon?: ReactNode;
}) {
  const [selfOpen, setSelfOpen] = useState(defaultOpen);
  const isControlled = open !== undefined;
  const expanded = isControlled ? open : selfOpen;
  const hasBody = children !== undefined && children !== null && children !== false;

  const toggle = () => {
    if (isControlled) onToggle?.();
    else setSelfOpen(o => !o);
  };

  return (
    <div className="tool-block">
      <button type="button" className="head" onClick={hasBody ? toggle : undefined} aria-expanded={hasBody ? expanded : undefined}>
        {hasBody && <span className="disc" aria-hidden>{expanded ? '▾' : '▸'}</span>}
        {icon !== undefined && <span className="t-ic" aria-hidden>{icon}</span>}
        <span className={plan ? 'tag plan' : 'tag'}>{tag}</span>
        <span className={descTail ? 'desc desc--tail' : 'desc'}>{desc}</span>
        {meta !== undefined && <span className="meta">{meta}</span>}
      </button>
      {hasBody && expanded && <div className="body-code">{children}</div>}
    </div>
  );
}
