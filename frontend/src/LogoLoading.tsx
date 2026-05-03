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
import logoLoadingUrl from './assets/logo-loading.svg';

/**
 * The branded loading mark — a 1.5s pulsing-slash animation around the
 * project logo. The animation lives inside the SVG itself (CSS in a
 * <style> block), so dropping the file into an <img> is enough to make
 * it run; no wrapping JS or CSS-in-JS is needed.
 *
 * Use this anywhere we'd otherwise show a textual "Loading…" — the
 * page's first-load shell, deep-link landing, and the PR detail
 * spinner all standardize on this component so the visual language
 * stays consistent.
 */
export default function LogoLoading({ size = 80, label = 'Loading' }: { size?: number; label?: string }) {
  return (
    <img
      src={logoLoadingUrl}
      alt={label}
      width={size}
      height={size}
      // role=img + aria-label lets assistive tech read the textual
      // intent, even though the visible content is a decorative SVG.
      role="img"
      aria-label={label}
    />
  );
}
