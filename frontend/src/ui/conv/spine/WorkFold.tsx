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
import { ChevronRightIcon } from '../../TaskBrainDesignIcons';

/**
 * Layer-2 conversation unit: the work fold — a round's intermediate work
 * (thinking, sub-messages, tool batches) collapsed into one
 * `✦ worked N steps · M tool calls · time` bar. Only the headline stays
 * visible; this keeps a long stage scannable. Failures still badge on the
 * bar even while folded. `forceOpen` (density = Full) overrides the local
 * toggle.
 */
export function WorkFold({ label = 'Brain worked', meta, failed, forceOpen = false, icon, children }: {
  label?: ReactNode;
  /** Right-aligned mono meta, e.g. "4 steps · 18 tool calls · 2m". */
  meta?: ReactNode;
  /** Failure count surfaced as a red badge even while collapsed. */
  failed?: number;
  forceOpen?: boolean;
  /** Replaces the default ✦ spark (Task Conversation uses a clock). */
  icon?: ReactNode;
  children?: ReactNode;
}) {
  const [selfOpen, setSelfOpen] = useState(false);
  const open = forceOpen || selfOpen;
  return (
    <div className={`sp-work${open ? ' open' : ''}`}>
      <button
        type="button"
        className="sp-work__bar"
        onClick={() => setSelfOpen(o => !o)}
        aria-expanded={open}
        disabled={forceOpen}
      >
        <span className="sp-work__sp" aria-hidden>{icon ?? '✦'}</span>
        <span className="sp-work__lbl">{label}</span>
        {meta !== undefined && <span className="sp-work__meta">{meta}</span>}
        {failed !== undefined && failed > 0 && (
          <span className="sp-badge sp-badge--fail">{failed} failed</span>
        )}
        <span className="sp-work__chev" aria-hidden><ChevronRightIcon size={13} strokeWidth={2} /></span>
      </button>
      {open && children !== undefined && <div className="sp-work__inner">{children}</div>}
    </div>
  );
}
