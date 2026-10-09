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
import { useCallback, useEffect, useRef, useState } from 'react';
import { GOOGLE_CHANGED, type GoogleStatus } from './contracts';

export function useGoogle() {
  const [status, setStatus] = useState<GoogleStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const revision = useRef(0);
  const mounted = useRef(true);
  const reload = useCallback(async () => {
    const current = ++revision.current;
    try {
      const next = await window.bridge.getGoogleStatus();
      if (mounted.current && current === revision.current) { setStatus(next); setError(null); }
    } catch (cause) {
      if (mounted.current && current === revision.current) setError(cause instanceof Error ? cause.message : 'Unable to load Google data.');
    }
  }, []);
  useEffect(() => {
    mounted.current = true;
    const refresh = () => { void reload(); };
    refresh();
    window.addEventListener(GOOGLE_CHANGED, refresh);
    window.addEventListener('focus', refresh);
    return () => { mounted.current = false; window.removeEventListener(GOOGLE_CHANGED, refresh); window.removeEventListener('focus', refresh); };
  }, [reload]);
  useEffect(() => {
    if (!status?.operation) return;
    const timer = window.setTimeout(() => { void reload(); }, 1200);
    return () => window.clearTimeout(timer);
  }, [status, reload]);
  const act = async (action: () => Promise<GoogleStatus>) => {
    revision.current++; setPending(true); setError(null);
    try {
      const next = await action();
      if (mounted.current) { setStatus(next); window.dispatchEvent(new Event(GOOGLE_CHANGED)); }
    } catch (cause) {
      if (mounted.current) setError(cause instanceof Error ? cause.message : 'Google request failed.');
    } finally { if (mounted.current) setPending(false); }
  };
  return { status, error: error ?? status?.error ?? null, busy: pending || Boolean(status?.operation), act, reload };
}
