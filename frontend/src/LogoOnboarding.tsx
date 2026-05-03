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
import logoOnboardingUrl from './assets/logo-onboarding.svg';

/**
 * The branded onboarding mark — frame fades in, the wave rises, and
 * the slashes draw in sequence, then settles into a soft 4s breathing
 * pulse. Plays once when first rendered. Used on the welcome / first-run
 * setup screen so the very first visual moment in the app is on-brand.
 */
export default function LogoOnboarding({ size = 130 }: { size?: number }) {
  return (
    <img
      src={logoOnboardingUrl}
      alt="ByteQuay"
      width={size}
      height={size}
      role="img"
      aria-label="ByteQuay"
    />
  );
}
