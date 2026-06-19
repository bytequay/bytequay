/**
 * Context-window occupancy for the task/thread metrics gauge.
 *
 * The gauge used to divide the task's *cumulative* `tokensIn` by the model
 * window. Cumulative tokens grow without bound across a task's turns (and,
 * before the per-task accounting fix, inherited the whole thread's lifetime
 * spend), so a brand-new task read "100% critical · 26.0M / 200k". That number
 * is "total tokens ever processed", not "how full the current context is".
 *
 * The right measure is the size of the LAST prompt actually sent — i.e. the
 * input tokens of the most recent completed turn (`turn_done` rows carry the
 * per-turn snapshot), or the in-flight turn's live input if larger.
 */

/** Default model context window, in tokens. */
export const CONTEXT_WINDOW_LIMIT = 200_000;

type Tokenized = { tokensIn: number | null };

/**
 * Current context-window occupancy in tokens. Expects `messages` in ascending
 * seq order (as the transcript is stored); returns the input-token count of
 * the most recent turn that carries one, or the live in-flight count if that
 * is larger. Returns 0 when there's no turn data yet.
 */
export function currentContextTokens(
  messages: readonly Tokenized[] | null | undefined,
  liveTokensIn?: number | null,
): number {
  let lastTurnTokens = 0;
  for (const m of messages ?? []) {
    if (typeof m.tokensIn === 'number' && m.tokensIn > 0) {
      lastTurnTokens = m.tokensIn;
    }
  }
  return Math.max(lastTurnTokens, liveTokensIn ?? 0);
}

/** Occupancy as a clamped 0–100 percentage of the model's context window. */
export function contextWindowPct(
  messages: readonly Tokenized[] | null | undefined,
  liveTokensIn?: number | null,
  limit: number = CONTEXT_WINDOW_LIMIT,
): number {
  if (limit <= 0) {
    return 0;
  }
  const pct = Math.round((currentContextTokens(messages, liveTokensIn) / limit) * 100);
  return Math.max(0, Math.min(100, pct));
}
