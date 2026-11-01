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

import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import { ChevronDown, MessageSquare, type LucideIcon } from 'lucide-react';
import { useId, useState, type ComponentType, type ReactNode, type SVGProps } from 'react';

export type ReviewRequestTarget =
  | { kind: 'card'; runId: string; cardId: string }
  | { kind: 'finding'; runId?: string; findingId: string }
  | { kind: 'local'; threadId: string };
export type ReviewCardFocus = { target: ReviewRequestTarget; token: number };

export type ReviewRequest = {
  action: string;
  topic: string;
  generated?: boolean;
  turnId?: string | null;
  messageEventId?: string | null;
  target?: ReviewRequestTarget;
  icon?: LucideIcon;
  /** The private card the request discusses, shown above the prompt. */
  card?: ReactNode;
};

export type Instruction = {
  /** Without an action the topic reads as the whole instruction, in plain text. */
  action?: string;
  topic: string;
  icon?: LucideIcon | ComponentType<SVGProps<SVGSVGElement>>;
  card?: ReactNode;
  onOpen?: () => void;
};

/**
 * Every instruction ByteQuay or the person sent the agent, as one quiet row: what it asks for and
 * about what. The row unfolds to the exact prompt the agent received, with anything the request
 * carries (its private card, attachments) above it.
 */
export function InstructionCard({ action, topic, icon: Icon = MessageSquare, card, onOpen, prompt, children }: Instruction & { prompt: string; children?: ReactNode }) {
  const [open, setOpen] = useState(false);
  const contentId = useId();
  const toggle = () => setOpen(value => !value);
  return <article className={`agt-instruction${open ? ' is-open' : ''}`}>
    <div className="agt-instruction__row">
      <button type="button" className="agt-instruction__open" onClick={onOpen ?? toggle}
        aria-label={onOpen ? `Open ${topic} in Summary` : action === undefined ? topic : `${action}: ${topic}`}
        title={onOpen ? 'Open in Summary' : 'Show prompt'}>
        <Icon className="agt-instruction__icon" aria-hidden="true" />
        {action !== undefined && <span className="agt-instruction__action">{action}</span>}
        <span className={action === undefined ? 'agt-instruction__text' : 'agt-instruction__topic'} title={topic}>{topic}</span>
      </button>
      <button type="button" className="agt-instruction__toggle" aria-expanded={open} aria-controls={contentId}
        aria-label={`${open ? 'Hide' : 'Show'} prompt: ${topic}`} onClick={toggle}>
        <ChevronDown aria-hidden="true" />
      </button>
    </div>
    <div id={contentId} className="agt-instruction__content" hidden={!open}>
      {(card !== undefined || children) && <div className="agt-instruction__extra">{card}{children}</div>}
      <div className="agt-instruction__prompt">
        <CopyLinkButton value={prompt} label="prompt" />
        <pre>{prompt}</pre>
      </div>
    </div>
  </article>;
}
