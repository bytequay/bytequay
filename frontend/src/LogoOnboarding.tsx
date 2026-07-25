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
import { useId } from 'react';

/** Inline so the onboarding animation reliably restarts on every mount. */
export default function LogoOnboarding({ size = 130 }: { size?: number }) {
  const gradientId = `bq-onboarding-${useId().replace(/[^a-zA-Z0-9]/g, '')}`;

  return (
    <svg
      className="bq-onboarding"
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 100 100"
      width={size}
      height={size}
      role="img"
      aria-label="ByteQuay"
    >
      <defs>
        <linearGradient id={gradientId} x1="15.8%" y1="-9.2%" x2="84.2%" y2="109.2%">
          <stop offset="0%" stopColor="#7C3AED" />
          <stop offset="52%" stopColor="#9A2EBE" />
          <stop offset="100%" stopColor="#B92C90" />
        </linearGradient>
      </defs>
      <rect width="100" height="100" rx="12.121" fill={`url(#${gradientId})`} />
      <circle className="bq-ring" cx="50" cy="50" r="31.818" fill="none" stroke="#FFFFFF" strokeWidth="3.03" strokeLinecap="round" transform="rotate(-90 50 50)" />
      <line className="bq-slash bq-slash-1" x1="37.121" y1="37.879" x2="43.182" y2="62.121" stroke="#FFFFFF" strokeWidth="3.788" strokeLinecap="round" />
      <line className="bq-slash bq-slash-2" x1="46.97" y1="37.879" x2="53.03" y2="62.121" stroke="#FACC15" strokeWidth="3.788" strokeLinecap="round" />
      <line className="bq-slash bq-slash-3" x1="56.818" y1="37.879" x2="62.879" y2="62.121" stroke="#FFFFFF" strokeWidth="3.788" strokeLinecap="round" />
    </svg>
  );
}
