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

/**
 * The branded loading mark — three slashes pulsing on a 1.5s loop with
 * a slow 3s wave shift underneath. Drop in anywhere we'd otherwise
 * show a textual "Loading…" so the visual language stays consistent.
 *
 * Inlined as JSX (instead of <img src=…svg>) because the renderer
 * doesn't always re-trigger SVG-embedded CSS animations on subsequent
 * mounts — see LogoOnboarding for the same reasoning. Animation
 * class / keyframe names are namespaced via useId() so multiple
 * spinners on the page don't collide.
 */
export default function LogoLoading({ size = 80, label = 'Loading' }: { size?: number; label?: string }) {
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');
  const cSlash = `bq-lo-slash-${id}`;
  const cSlash1 = `bq-lo-slash1-${id}`;
  const cSlash2 = `bq-lo-slash2-${id}`;
  const cSlash3 = `bq-lo-slash3-${id}`;
  const cWaveFill = `bq-lo-wave-fill-${id}`;
  const cWaveLine = `bq-lo-wave-line-${id}`;
  const kPulse = `bq-lo-pulse-${id}`;
  const kWaveFill = `bq-lo-wave-fill-anim-${id}`;
  const kWaveLine = `bq-lo-wave-line-anim-${id}`;

  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 100 100"
      width={size}
      height={size}
      role="img"
      aria-label={label}
    >
      <style>{`
        .${cSlash} {
          stroke: #FFFFFF;
          stroke-width: 3.5;
          stroke-linecap: round;
          animation: ${kPulse} 1.5s ease-in-out infinite;
        }
        .${cSlash1} { animation-delay: 0s; }
        .${cSlash2} { animation-delay: 0.2s; }
        .${cSlash3} { animation-delay: 0.4s; }
        @keyframes ${kPulse} {
          0%, 100% { opacity: 0.3; }
          40%      { opacity: 1; }
        }
        .${cWaveFill} {
          fill: #8B5CF6;
          transform-origin: 50px 75px;
          animation: ${kWaveFill} 3s ease-in-out infinite;
        }
        .${cWaveLine} {
          stroke: #A78BFA;
          stroke-width: 2;
          fill: none;
          animation: ${kWaveLine} 3s ease-in-out infinite;
        }
        @keyframes ${kWaveFill} {
          0%, 100% { transform: translateX(0); }
          50%      { transform: translateX(-2px); }
        }
        @keyframes ${kWaveLine} {
          0%, 100% { transform: translateX(0); opacity: 1; }
          50%      { transform: translateX(2px); opacity: 0.7; }
        }
        @media (prefers-reduced-motion: reduce) {
          .${cSlash}, .${cWaveFill}, .${cWaveLine} {
            animation: none;
            opacity: 1;
          }
        }
      `}</style>
      <rect fill="#7C3AED" x="15" y="15" width="70" height="70" rx="12" />
      <line className={`${cSlash} ${cSlash1}`} x1="32" y1="28" x2="42" y2="52" />
      <line className={`${cSlash} ${cSlash2}`} x1="45" y1="28" x2="55" y2="52" />
      <line className={`${cSlash} ${cSlash3}`} x1="58" y1="28" x2="68" y2="52" />
      <path className={cWaveFill} d="M 15 68 Q 30 62 42 68 T 70 68 L 85 68 L 85 85 C 85 85 85 85 77 85 L 23 85 C 15 85 15 85 15 85 Z" />
      <path className={cWaveLine} d="M 15 68 Q 30 62 42 68 T 70 68 L 85 68" />
    </svg>
  );
}
