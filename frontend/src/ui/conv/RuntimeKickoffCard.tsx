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
import {
  ChevronRightIcon, PenIcon, PlanIcon, TerminalRunIcon,
} from '../TaskBrainDesignIcons';

const PLAN_KICKOFF_PREFIX = 'The plan for this task has been approved — implement it now.';
const PLANNING_KICKOFF_PREFIX = 'You are the planning agent for a new development task.';
const CI_FIX_CONTEXT_PREFIX = '## Context from prior stages\nYou are a fresh agent for the CI-fixing stage —';
const CI_FIX_PROMPT_PREFIX = 'CI is failing on the shipped PR ';
const LOCAL_COMMENTS_PREFIX = 'New comments arrived on your local PR "';

function kickoffIntent(text: string): string {
  const start = text.indexOf('\nIntent:');
  const end = text.indexOf('\nSteps:', start + 1);
  if (start === -1 || end === -1) return 'Development instructions sent automatically';
  return text.slice(start + '\nIntent:'.length, end).trim()
    || 'Development instructions sent automatically';
}

function ciFixPreview(text: string): string {
  const lines = text.split('\n');
  const prLine = lines.find(line => line.startsWith(CI_FIX_PROMPT_PREFIX));
  const pr = prLine?.slice(CI_FIX_PROMPT_PREFIX.length).replace(/\.$/, '');
  const checksStart = lines.indexOf('Failing checks:');
  const checkLines = checksStart === -1 ? [] : lines.slice(checksStart + 1);
  const checksEnd = checkLines.findIndex(line => line.trim().length === 0);
  const checks = (checksEnd === -1 ? checkLines : checkLines.slice(0, checksEnd))
    .map(line => line.replace(/^\s*-\s*/, ''));
  return [pr, ...checks].filter(Boolean).join(' · ')
    || 'Failing checks and remediation instructions sent automatically';
}

export type RuntimeKickoff = {
  title: string;
  preview: string;
  bodyLabel: string;
  icon: ReactNode;
};

/** Runtime-generated prompts use the model's user role, but are not human messages. */
export function runtimeKickoff(text: string | null): RuntimeKickoff | null {
  if (text === null) return null;
  if (text.startsWith(PLAN_KICKOFF_PREFIX)) {
    return {
      title: 'Approved plan',
      preview: kickoffIntent(text),
      bodyLabel: 'Development kickoff prompt',
      icon: <PlanIcon size={14} />,
    };
  }
  if (text.startsWith(PLANNING_KICKOFF_PREFIX)) {
    return {
      title: 'Planning started',
      preview: 'Task context and planning instructions sent automatically',
      bodyLabel: 'Planning kickoff prompt',
      icon: <PlanIcon size={14} />,
    };
  }
  if (text.startsWith(CI_FIX_CONTEXT_PREFIX) || text.startsWith(CI_FIX_PROMPT_PREFIX)) {
    return {
      title: 'CI fix instructions',
      preview: ciFixPreview(text),
      bodyLabel: 'CI-fix kickoff prompt',
      icon: <TerminalRunIcon size={14} />,
    };
  }
  if (text.startsWith(LOCAL_COMMENTS_PREFIX)) {
    const firstLine = text.split('\n', 1)[0];
    return {
      title: 'Address local review comments',
      preview: firstLine.replace(/\s+Unlike remote review comments.*$/, ''),
      bodyLabel: 'Local-comment addressing prompt',
      icon: <PenIcon />,
    };
  }
  return null;
}

export function RuntimeKickoffCard({
  text, kickoff, timestamp, messageSeq, managedSkills = [],
}: {
  text: string;
  kickoff: RuntimeKickoff;
  timestamp?: ReactNode;
  messageSeq?: number | null;
  managedSkills?: string[];
}) {
  return (
    <details className="runtime-kickoff-card" data-seq={messageSeq ?? undefined}>
      <summary className="runtime-kickoff-card__summary">
        <span className="runtime-kickoff-card__icon" aria-hidden>{kickoff.icon}</span>
        <span className="runtime-kickoff-card__copy">
          <span className="runtime-kickoff-card__title">{kickoff.title}</span>
          <span className="runtime-kickoff-card__preview">{kickoff.preview}</span>
        </span>
        <span className="runtime-kickoff-card__badge">Runtime</span>
        {timestamp !== undefined && <span className="runtime-kickoff-card__time">{timestamp}</span>}
        <span className="runtime-kickoff-card__chevron" aria-hidden>
          <ChevronRightIcon size={13} strokeWidth={2} />
        </span>
      </summary>
      <div className="runtime-kickoff-card__body">
        <div className="runtime-kickoff-card__body-label">{kickoff.bodyLabel}</div>
        <div className="runtime-kickoff-card__prompt">{text}</div>
        {managedSkills.length > 0 && (
          <div className="runtime-kickoff-card__skills">
            Managed skills: {managedSkills.join(', ')}
          </div>
        )}
      </div>
    </details>
  );
}
