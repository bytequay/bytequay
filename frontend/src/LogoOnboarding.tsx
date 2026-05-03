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
 * The branded onboarding mark — frame fades in, the wave rises, and
 * the slashes draw in sequence, then settles into a soft 4s breathing
 * pulse. Plays once on every render of this component.
 *
 * The SVG is rendered inline rather than as <img src="…svg"> because
 * Electron's renderer doesn't reliably re-trigger CSS animations
 * embedded in an external SVG: the file is cached and loaded once,
 * and on the second visit you see only the animation's final frame
 * (the fill-mode: forwards keyframes settle there). Inlining the
 * SVG and namespacing the animation classes via useId() guarantees a
 * fresh play every time the component mounts.
 */
export default function LogoOnboarding({ size = 130 }: { size?: number }) {
  // useId gives us a per-instance suffix so multiple LogoOnboarding
  // mounts in the same document don't collide on animation names.
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');
  const cFrame = `bq-onb-frame-${id}`;
  const cWaveFill = `bq-onb-wave-fill-${id}`;
  const cWaveLine = `bq-onb-wave-line-${id}`;
  const cSlash = `bq-onb-slash-${id}`;
  const cSlash1 = `bq-onb-slash1-${id}`;
  const cSlash2 = `bq-onb-slash2-${id}`;
  const cSlash3 = `bq-onb-slash3-${id}`;
  const kFrameIn = `bq-onb-frame-in-${id}`;
  const kBreathe = `bq-onb-breathe-${id}`;
  const kRise = `bq-onb-rise-${id}`;
  const kDraw = `bq-onb-draw-${id}`;

  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 100 100"
      width={size}
      height={size}
      role="img"
      aria-label="ByteQuay"
    >
      <style>{`
        .${cFrame} {
          fill: #7C3AED;
          opacity: 0;
          transform: scale(0.92);
          transform-origin: 50px 50px;
          animation: ${kFrameIn} 0.5s ease-out forwards,
                     ${kBreathe} 4s ease-in-out 3s infinite;
        }
        @keyframes ${kFrameIn} {
          from { opacity: 0; transform: scale(0.92); }
          to   { opacity: 1; transform: scale(1); }
        }
        @keyframes ${kBreathe} {
          0%, 100% { transform: scale(1); }
          50%      { transform: scale(1.015); }
        }
        .${cWaveFill} {
          fill: #8B5CF6;
          opacity: 0;
          transform: translateY(18px);
          animation: ${kRise} 0.7s cubic-bezier(0.4, 0, 0.2, 1) 0.5s forwards;
        }
        .${cWaveLine} {
          stroke: #A78BFA;
          stroke-width: 2;
          fill: none;
          opacity: 0;
          transform: translateY(18px);
          animation: ${kRise} 0.7s cubic-bezier(0.4, 0, 0.2, 1) 0.6s forwards;
        }
        @keyframes ${kRise} {
          from { opacity: 0; transform: translateY(18px); }
          to   { opacity: 1; transform: translateY(0); }
        }
        .${cSlash} {
          stroke: #FFFFFF;
          stroke-width: 3.5;
          stroke-linecap: round;
          stroke-dasharray: 28;
          stroke-dashoffset: 28;
          animation: ${kDraw} 0.5s cubic-bezier(0.4, 0, 0.2, 1) forwards;
        }
        .${cSlash1} { animation-delay: 1.1s; }
        .${cSlash2} { animation-delay: 1.35s; }
        .${cSlash3} { animation-delay: 1.6s; }
        @keyframes ${kDraw} { to { stroke-dashoffset: 0; } }
        @media (prefers-reduced-motion: reduce) {
          .${cFrame}, .${cSlash}, .${cWaveFill}, .${cWaveLine} {
            animation: none;
            opacity: 1;
            transform: none;
            stroke-dashoffset: 0;
          }
        }
      `}</style>
      <rect className={cFrame} x="15" y="15" width="70" height="70" rx="12" />
      <path className={cWaveFill} d="M 15 68 Q 30 62 42 68 T 70 68 L 85 68 L 85 85 C 85 85 85 85 77 85 L 23 85 C 15 85 15 85 15 85 Z" />
      <path className={cWaveLine} d="M 15 68 Q 30 62 42 68 T 70 68 L 85 68" />
      <line className={`${cSlash} ${cSlash1}`} x1="32" y1="28" x2="42" y2="52" />
      <line className={`${cSlash} ${cSlash2}`} x1="45" y1="28" x2="55" y2="52" opacity="0.7" />
      <line className={`${cSlash} ${cSlash3}`} x1="58" y1="28" x2="68" y2="52" />
    </svg>
  );
}
