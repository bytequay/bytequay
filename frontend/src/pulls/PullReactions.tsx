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
import { SmilePlus } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import { recordPullRequestAttentionAction } from '../home/useAttentionVisit';
import { errorMessage } from '../errorMessage';
import type { Bridge, PullReactionContent, PullReactionDto, PullReactionTarget } from '../types';
import { usePullRequestBridge, usePullRequestWorkspace } from './PullRequestWorkspace';

export type ReactionAction = {
  repo: string;
  number: number;
  target: PullReactionTarget;
  commentId: number | null;
  reactions: PullReactionDto[];
  saving: boolean;
  error: string | null;
  intent: (content: PullReactionContent) => boolean | null;
  submit: (content: PullReactionContent) => Promise<boolean>;
};

type ReactionRequest = Pick<ReactionAction, 'repo' | 'number' | 'target' | 'commentId' | 'reactions'>;
type ReactionChange = (reactions: PullReactionDto[], onlyIf?: PullReactionDto[]) => void;

export function nextViewerReaction(reactions: PullReactionDto[], content: PullReactionContent) {
  const existing = reactions.find(reaction => reaction.content === content);
  if (!existing) return true;
  return typeof existing.viewerHasReacted === 'boolean' ? !existing.viewerHasReacted : null;
}

export function applyViewerReaction(
  reactions: PullReactionDto[],
  content: PullReactionContent,
  viewerHasReacted: boolean,
) {
  const index = reactions.findIndex(reaction => reaction.content === content);
  if (index < 0) {
    return viewerHasReacted
      ? [...reactions, { content, count: 1, viewerHasReacted: true }]
      : reactions;
  }
  const existing = reactions[index];
  const delta = typeof existing.viewerHasReacted === 'boolean'
    ? Number(viewerHasReacted) - Number(existing.viewerHasReacted)
    : viewerHasReacted ? 1 : -1;
  const count = Math.max(0, existing.count + delta);
  if (count === 0) return reactions.filter((_, reactionIndex) => reactionIndex !== index);
  return reactions.map((reaction, reactionIndex) => reactionIndex === index
    ? { ...reaction, count, viewerHasReacted }
    : reaction);
}

function serverReflectsReaction(
  reactions: PullReactionDto[],
  content: PullReactionContent,
  viewerHasReacted: boolean,
) {
  const reaction = reactions.find(value => value.content === content);
  if (!reaction) return !viewerHasReacted;
  return reaction.viewerHasReacted === viewerHasReacted;
}

export function useReactionAction(
  repo: string,
  number: number,
  target: PullReactionTarget,
  commentId: number | null,
  serverReactions: PullReactionDto[],
  enabled = true,
  onChange?: ReactionChange,
): ReactionAction | undefined {
  const bridge = usePullRequestBridge();
  const workspaceId = usePullRequestWorkspace();
  const key = `${workspaceId ?? ''}:${repo.toLowerCase()}#${number}:${target}:${commentId ?? ''}`;
  const [local, setLocal] = useState<{ key: string; reactions: PullReactionDto[] } | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const confirmation = useRef<{
    key: string;
    content: PullReactionContent;
    viewerHasReacted: boolean;
  } | null>(null);
  const optimisticEcho = useRef<{ key: string; reactions: PullReactionDto[] } | null>(null);
  const reactions = local?.key === key ? local.reactions : serverReactions;

  useEffect(() => {
    setError(null);
    confirmation.current = null;
    optimisticEcho.current = null;
    setLocal(current => current?.key === key ? current : null);
  }, [key]);

  useEffect(() => {
    const expected = confirmation.current;
    if (inFlight.current || local?.key !== key || expected?.key !== key
        || (optimisticEcho.current?.key === key && optimisticEcho.current.reactions === serverReactions)
        || !serverReflectsReaction(serverReactions, expected.content, expected.viewerHasReacted)) return;
    confirmation.current = null;
    optimisticEcho.current = null;
    setLocal(null);
  }, [key, local, serverReactions]);

  const submit = async (content: PullReactionContent) => {
    if (inFlight.current) return false;
    const base = local?.key === key ? local.reactions : serverReactions;
    const previousLocal = local?.key === key ? local : null;
    const previousConfirmation = confirmation.current;
    const previousEcho = optimisticEcho.current;
    const desired = nextViewerReaction(base, content);
    const optimistic = desired === null ? null : applyViewerReaction(base, content, desired);
    inFlight.current = true;
    setSaving(true);
    setError(null);
    if (optimistic !== null) {
      setLocal({ key, reactions: optimistic });
      onChange?.(optimistic);
    }
    try {
      const result = await submitPullReaction(bridge, { repo, number, target, commentId, reactions: base }, content);
      recordPullRequestAttentionAction(repo, number, 'update', workspaceId);
      const confirmed = applyViewerReaction(
        optimistic ?? base, content, result.viewerHasReacted);
      confirmation.current = { key, content, viewerHasReacted: result.viewerHasReacted };
      optimisticEcho.current = { key, reactions: confirmed };
      setLocal({ key, reactions: confirmed });
      onChange?.(confirmed);
      return true;
    }
    catch (failure) {
      confirmation.current = previousConfirmation;
      optimisticEcho.current = previousEcho;
      setLocal(current => current?.key === key
        ? previousLocal
        : current);
      if (optimistic !== null) onChange?.(base, optimistic);
      setError(`Could not update reaction. ${errorMessage(failure)}`);
      return false;
    }
    finally {
      inFlight.current = false;
      setSaving(false);
    }
  };

  return enabled ? {
    repo,
    number,
    target,
    commentId,
    reactions,
    saving,
    error,
    intent: content => nextViewerReaction(reactions, content),
    submit,
  } : undefined;
}

export async function submitPullReaction(
  bridge: Pick<Bridge, 'setPrReaction'>,
  action: ReactionRequest,
  content: PullReactionContent,
) {
  return bridge.setPrReaction(
    action.repo,
    action.number,
    action.target,
    action.commentId,
    content,
    nextViewerReaction(action.reactions, content),
  );
}

export function Reactions({ reactions, reaction, alwaysShowPicker = false }: {
  reactions: PullReactionDto[];
  reaction?: ReactionAction;
  alwaysShowPicker?: boolean;
}) {
  if (reactions.length === 0 && (!alwaysShowPicker || !reaction) && !reaction?.error) return null;
  return <div className="pr-reactions">{reactions.map(value => (
    reaction
      ? <ReactionButton key={value.content} value={value} action={reaction} />
      : <span key={value.content}>{reactionEmoji(value.content)} {value.count}</span>
  ))}{reaction && <ReactionPicker action={reaction} compact key="add-reaction" />}
  {reaction?.error && <span className="pr-reaction-error" role="alert">{reaction.error}</span>}</div>;
}

const REACTION_CHOICES = [
  ['+1', '👍', 'thumbs up'],
  ['-1', '👎', 'thumbs down'],
  ['laugh', '😄', 'laugh'],
  ['hooray', '🎉', 'hooray'],
  ['confused', '😕', 'confused'],
  ['heart', '❤️', 'heart'],
  ['rocket', '🚀', 'rocket'],
  ['eyes', '👀', 'eyes'],
] as const;

function ReactionButton({ value, action }: { value: PullReactionDto; action: ReactionAction }) {
  const label = REACTION_CHOICES.find(([content]) => content === value.content)?.[2] ?? value.content;
  const active = value.viewerHasReacted === true;
  return (
    <button type="button" className={`pr-reaction${active ? ' is-active' : ''}`} disabled={action.saving}
      aria-pressed={active}
      aria-label={`Add or remove ${label} reaction`} title={`Add or remove ${label} reaction`}
      onClick={() => { void action.submit(value.content); }}>{reactionEmoji(value.content)} {value.count}</button>
  );
}

export function ReactionPicker({ action, compact = false }: { action: ReactionAction; compact?: boolean }) {
  const [open, setOpen] = useState(false);
  const menuId = useId();

  const pickerRef = usePopoverDismiss(() => setOpen(false), false, open && !action.saving);

  const choose = async (content: PullReactionContent) => {
    const optimistic = action.intent(content) !== null;
    if (optimistic) setOpen(false);
    const succeeded = await action.submit(content);
    if (succeeded) setOpen(false);
    else if (optimistic) setOpen(true);
  };

  return (
    <div ref={pickerRef} className={`pr-reaction-picker${compact ? ' is-compact' : ''}${open ? ' is-open' : ''}`}
      data-reaction-target={action.target} data-reaction-comment-id={action.commentId ?? undefined}>
      <button type="button" className="pr-reaction-picker__trigger" aria-label="Add reaction" title="Add reaction"
        aria-expanded={open} aria-haspopup="menu" aria-controls={menuId} disabled={action.saving}
        onClick={() => setOpen(value => !value)}><SmilePlus size={16} strokeWidth={1.3} aria-hidden="true" /></button>
      <div className="pr-reaction-picker__menu" id={menuId} role="menu" aria-label="Choose a reaction" aria-busy={action.saving}>
        {REACTION_CHOICES.map(([content, emoji, label]) => {
          const active = action.reactions.some(reaction => reaction.content === content && reaction.viewerHasReacted === true);
          return <button type="button" role="menuitemcheckbox" disabled={action.saving}
            className={active ? 'is-active' : undefined}
            aria-checked={active}
            aria-label={`Add or remove ${label} reaction`} key={content}
            onClick={() => { void choose(content); }}>
            {emoji}
          </button>;
        })}
        {action.error && <div className="pr-reaction-picker__error" role="alert">{action.error}</div>}
      </div>
    </div>
  );
}

export function reactionEmoji(content: string) {
  return REACTION_CHOICES.find(([choice]) => choice === content)?.[1] ?? content;
}
