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

import type { ThreadDto, WorkModelDto } from '../types';

/** What the conversation renderers need to show "who is speaking" —
 *  a short name in the bubble header, a single-letter glyph in the
 *  avatar circle, and an accent color so different providers stay
 *  visually distinct at a glance. */
export type AssistantLabel = {
  name: string;
  glyph: string;
  color: string;
};

/** Default surfaced when a thread has no resolved work-model yet
 *  (brand-new thread before the first turn, or a legacy row from
 *  before the work-model column existed). Falls back to a neutral
 *  "Assistant" rather than picking a specific provider's chip — the
 *  old hardcoded "Claude" badge actively misled users who'd picked
 *  DeepSeek or a local model. */
const FALLBACK: AssistantLabel = {
  name: 'Assistant',
  glyph: 'A',
  color: '#94a3b8',
};

/** Best-effort identifier for what's actually responding on the
 *  current turn. Prefers the thread's per-thread work-model
 *  override; falls back to the legacy {@code provider} / {@code model}
 *  scalars stamped on the thread row. Either input may be null on a
 *  freshly-created thread — we return {@link FALLBACK} for that. */
export function assistantLabel(thread: Pick<ThreadDto, 'workModel' | 'provider' | 'model'> | null | undefined): AssistantLabel {
  if (thread == null) return FALLBACK;
  if (thread.workModel != null) {
    return fromWorkModel(thread.workModel);
  }
  // Legacy scalar fields. Threads created before the work-model
  // cascade landed only have these, and they may be empty strings on
  // rows that never picked a model.
  const fallbackKey = (thread.model || thread.provider || '').toLowerCase();
  if (fallbackKey.length === 0) return FALLBACK;
  return fromKey(fallbackKey, thread.model || fallbackKey);
}

function fromWorkModel(wm: WorkModelDto): AssistantLabel {
  // For API providers we have provider + model separately and want
  // the *model* name on the chip ("DeepSeek V4 Flash" beats just
  // "DeepSeek"). For CLI agents the agent id is what matters
  // ("Claude Code", "Codex") because the actual model is whatever
  // the CLI picks per turn.
  if (wm.kind === 'CLI') {
    return fromKey(wm.agentOrProvider.toLowerCase(), wm.agentOrProvider);
  }
  const modelId = (wm.model || '').toLowerCase();
  const provId = wm.agentOrProvider.toLowerCase();
  // Prefer the model id when present; fall back to provider id.
  return fromKey(modelId.length > 0 ? modelId : provId, wm.model || wm.agentOrProvider);
}

/** Maps a normalized key (model id or provider id) to display chrome.
 *  {@code rawName} is the canonical id we'll surface verbatim when the
 *  key doesn't match anything friendlier — better to show
 *  "qwen-3-coder" raw than to invent a fake "Assistant" label that
 *  hides what's actually running. */
function fromKey(key: string, rawName: string): AssistantLabel {
  // Anthropic family — both API models and the Claude Code CLI agent.
  if (key === 'claude-code') {
    return { name: 'Claude Code', glyph: 'C', color: '#d97757' };
  }
  if (key.startsWith('claude') || key === 'anthropic') {
    return { name: friendlyClaude(key, rawName), glyph: 'C', color: '#d97757' };
  }
  // DeepSeek — both cloud and local variants. The "(local)" suffix
  // matters for the user; that's the entire point of the picker.
  if (key.includes('deepseek')) {
    const local = key.includes('local');
    const base = key.includes('v4-flash') ? 'DeepSeek V4 Flash'
        : key.includes('reasoner') ? 'DeepSeek Reasoner'
        : key.includes('chat') ? 'DeepSeek Chat'
        : 'DeepSeek';
    return { name: local ? `${base} (local)` : base, glyph: 'D', color: '#4d6bfe' };
  }
  // OpenAI — GPT models + the Codex CLI agent.
  if (key === 'codex') {
    return { name: 'Codex', glyph: 'X', color: '#10a37f' };
  }
  if (key.startsWith('gpt') || key === 'openai') {
    return { name: friendlyGpt(key, rawName), glyph: 'G', color: '#10a37f' };
  }
  // Google.
  if (key.startsWith('gemini') || key === 'google') {
    return { name: friendlyGemini(key, rawName), glyph: 'G', color: '#4285f4' };
  }
  // Local-only generic ("local" provider id used by ollama / lm-studio).
  if (key === 'local') {
    return { name: 'Local model', glyph: 'L', color: '#94a3b8' };
  }
  // Unknown — surface the raw id so the user can see what's actually
  // serving the turn, plus a neutral letter glyph from the id itself.
  return {
    name: rawName,
    glyph: (rawName.match(/[A-Za-z]/)?.[0] ?? '?').toUpperCase(),
    color: '#94a3b8',
  };
}

function friendlyClaude(key: string, raw: string): string {
  if (key.includes('opus-4-8')) return 'Claude Opus 4.8';
  if (key.includes('opus-4-7')) return 'Claude Opus 4.7';
  if (key.includes('opus')) return 'Claude Opus';
  if (key.includes('sonnet-4-6')) return 'Claude Sonnet 4.6';
  if (key.includes('sonnet')) return 'Claude Sonnet';
  if (key.includes('haiku-4-5')) return 'Claude Haiku 4.5';
  if (key.includes('haiku')) return 'Claude Haiku';
  return raw.startsWith('claude') ? 'Claude' : raw;
}

function friendlyGpt(key: string, raw: string): string {
  if (key.startsWith('gpt-5')) return 'GPT-5';
  if (key.startsWith('gpt-4o')) return 'GPT-4o';
  if (key.startsWith('gpt-4')) return 'GPT-4';
  if (key === 'openai') return 'OpenAI';
  return raw;
}

function friendlyGemini(key: string, raw: string): string {
  if (key.includes('2.5-pro') || key.includes('2-5-pro')) return 'Gemini 2.5 Pro';
  if (key.includes('2.5')) return 'Gemini 2.5';
  if (key === 'google') return 'Gemini';
  return raw;
}
