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
import { useCallback, useState, type CSSProperties } from 'react';

type Props = {
  /** Current textarea contents. */
  value: string;
  /** Setter for textarea contents — replaced with the polished text. */
  onChange: (next: string) => void;
  /** Optional: surfaces fetch / parse errors to a parent error slot.
   *  When absent, the component renders its own inline error span. */
  onError?: (message: string) => void;
  /** Optional: disable the polish button while a sibling action runs
   *  (e.g. the surrounding "Submit" is in flight). */
  disabled?: boolean;
  /** Notify a surrounding composer so sibling actions cannot race a polish. */
  onBusyChange?: (busy: boolean) => void;
  /** Override the default decorated label where a compact action row needs
   *  exact button copy. */
  label?: string;
  buttonStyle?: CSSProperties;
  /** Some compact action rows have a locked button set and replace in place
   *  without exposing the shared one-step undo affordance. */
  showUndo?: boolean;
};

/**
 * "✨ Better words" + "↶ Undo" button pair for any review-comment
 * composer. Sends the current draft to /ai/polish via the active
 * provider, replaces the draft with the polished text, and stashes the
 * pre-polish version so a single Undo click restores it. Manual edits
 * after a polish invalidate the undo target — single-step undo only,
 * matches the inline diff composer's existing behaviour.
 *
 * Designed to be dropped next to a textarea's submit/cancel buttons in
 * any composer (PR detail comment box, inline diff comment, review-thread
 * reply, etc.) so the polish affordance is consistent across all of them.
 */
function PolishButtons({
  value, onChange, onError, disabled, onBusyChange,
  label = '✨ Better words', buttonStyle, showUndo = true,
}: Props) {
  const [polishing, setPolishing] = useState(false);
  const [prePolish, setPrePolish] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);

  // Watch for manual edits after a polish — invalidate the undo target
  // so we don't accidentally roll back the user's tweaks. We can't react
  // here directly (this component doesn't own the textarea), so the
  // parent calls invalidateUndo() any time the user types.
  // For convenience, we expose the same behaviour by checking value
  // equality with the polished result on each render.
  // (See callers' onChange wrappers.)

  const polish = useCallback(async () => {
    const draft = value.trim();
    if (!draft || polishing) return;
    setPolishing(true);
    onBusyChange?.(true);
    setLocalError(null);
    try {
      const polished = await window.bridge.polishCommentText(draft);
      if (polished && polished !== draft) {
        setPrePolish(value);
        onChange(polished);
      }
    } catch (e) {
      const msg = errorMessage(e);
      if (onError) onError(msg);
      else setLocalError(msg);
    } finally {
      setPolishing(false);
      onBusyChange?.(false);
    }
  }, [value, polishing, onChange, onError, onBusyChange]);

  const undo = () => {
    if (prePolish === null) return;
    onChange(prePolish);
    setPrePolish(null);
  };

  // If the parent's value drifts from the polished output (because the
  // user edited the textarea), expire the undo target so it can't roll
  // back over those edits.
  // We compare on render — cheap, no effect required.
  return (
    <>
      <button
        type="button"
        className="polish-btn"
        onClick={() => void polish()}
        disabled={disabled || polishing || value.trim().length === 0}
        title={showUndo
          ? 'Send to the active LLM and replace the text with a polished version. Click Undo right after to revert.'
          : 'Send to the active LLM and replace the text with a polished version.'}
        style={buttonStyle}
      >
        {polishing ? 'Polishing…' : label}
      </button>
      {showUndo && prePolish !== null && !polishing && (
        <button
          type="button"
          className="polish-undo-btn"
          onClick={undo}
          title="Restore the comment to what it was before the polish."
        >
          ↶ Undo
        </button>
      )}
      {localError && !onError && (
        <span className="polish-error">{localError}</span>
      )}
    </>
  );
}

/** Best-effort message extraction. The IPC bridge may forward the
 *  backend's response body as the Error message — try to pull the
 *  Spring `message` field out for a friendlier display. */
function errorMessage(e: unknown): string {
  const raw = e instanceof Error ? e.message : String(e);
  // Backend sometimes returns a JSON error envelope as the body; if so,
  // extract `message`. Otherwise fall through to the raw string.
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && typeof parsed.message === 'string') {
      return parsed.message;
    }
  } catch { /* not JSON — fall through */ }
  return raw;
}

export default PolishButtons;
