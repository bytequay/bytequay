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

/**
 * Client-side extraction of scannable chips from the planning seed prose
 * (the trunk handoff). The seed is a single prose blob — there is no
 * structured seed in the brain view (DISCOVERY-FINDINGS #8) — so this is a
 * best-effort keyword parse, render-only (A1.4: no backend change). Whatever
 * doesn't match is simply absent; the full rendered markdown is always one
 * click away.
 */
export type SeedChips = {
  type?: string;
  validate?: string;
  push?: string;
  outOfScope?: string;
};

/** First capture group of the first matching pattern, trimmed + clamped. */
function firstMatch(text: string, patterns: RegExp[]): string | undefined {
  for (const re of patterns) {
    const m = re.exec(text);
    if (m !== null && typeof m[1] === 'string') {
      const v = m[1].replace(/\s+/g, ' ').trim().replace(/[.;]+$/, '');
      if (v.length > 0) return v.length > 48 ? `${v.slice(0, 47)}…` : v;
    }
  }
  return undefined;
}

export function extractSeedChips(seed: string): SeedChips {
  const t = seed.replace(/`/g, '');
  const chips: SeedChips = {};

  const type = firstMatch(t, [/\b(refactor|cleanup|bug ?fix|feature|chore|test|docs)\b/i]);
  if (type !== undefined) chips.type = capitalize(type);

  // A validation gate — "gate: mvn verify", "run mvn verify", "npm test".
  chips.validate = firstMatch(t, [
    /\bgate[^.:]*?:?\s*(mvn [a-z]+|npm [a-z]+|tsc[^.\n]*)/i,
    /\brun\s+(mvn [a-z]+|npm test|npx tsc[^.\n]*)/i,
    /\b(mvn verify|mvn test|npm test)\b/i,
  ]);

  // Push strategy — "no push", "local only", "open a PR".
  if (/\b(do not push|don't push|no push|local(?:ly)? only|leave it committed locally|commit locally)\b/i.test(t)) {
    chips.push = 'local only';
  }
  else if (/\bopen (?:a )?pr\b|\bpull request\b/i.test(t)) {
    chips.push = 'open PR';
  }
  else if (/\bpush\b/i.test(t)) {
    chips.push = 'push';
  }

  chips.outOfScope = firstMatch(t, [
    /out[- ]of[- ]scope[^.\n:]*[:—-]\s*([^.\n]+)/i,
    /\bdo NOT bundle\s+([^.\n]+)/i,
    /\bnot in scope[^.\n:]*[:—-]?\s*([^.\n]+)/i,
  ]);

  return chips;
}

function capitalize(s: string): string {
  return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1).toLowerCase();
}
