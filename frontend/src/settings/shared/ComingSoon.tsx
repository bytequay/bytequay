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
  title: string;
  description?: string;
};

/**
 * Placeholder rendered for sidebar sections whose features ship in a later
 * phase (Notifications / Integrations / Help, plus Teams until Phase C).
 * Keeps the sidebar shape stable and signals direction without misleading
 * users that the feature already works.
 */
function ComingSoon({ title, description }: Props) {
  return (
    <div className="settings-stub">
      <div className="settings-stub__title">{title}</div>
      <div>{description ?? 'This area is on the roadmap. We\'ll let you know when it lands.'}</div>
    </div>
  );
}

export default ComingSoon;
