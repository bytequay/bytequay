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

type Props = {
  /** Where back goes, phrased for the label: "← back to {label}".
   *  Callers derive this from the page's carried `back` Nav (or the
   *  page's fallback when there's no carried origin) — the app has no
   *  router history, so the previous view travels in the nav state. */
  label: string;
  /** Pop to the previous view (or the fallback). */
  onClick: () => void;
};

/**
 * History-aware back control for the {@code setNav} navigation model.
 * Unlike a router back, there's no URL stack here: each page carries its
 * origin in the nav state's {@code back} field, and the host derives the
 * {@code label} + {@code onClick} from it (falling back to a per-surface
 * default when a page was deep-linked with no origin).
 */
export function BackButton({ label, onClick }: Props) {
  return (
    <button type="button" className="back-button" style={style} onClick={onClick}>
      ← back to {label}
    </button>
  );
}

const style: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  padding: '4px 10px',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  background: 'rgba(255,255,255,0.85)',
  color: 'var(--text-2)',
  cursor: 'pointer',
  font: 'inherit',
  fontSize: 12,
};
