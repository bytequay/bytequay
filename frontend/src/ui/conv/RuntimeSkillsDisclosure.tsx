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
import type { CSSProperties } from 'react';

export function RuntimeSkillsDisclosure({ skills }: { skills: string[] }) {
  if (skills.length === 0) {
    return null;
  }
  return (
    <details style={detailsStyle}>
      <summary style={summaryStyle}>runtime</summary>
      <div style={bodyStyle}>Managed skills: {skills.join(', ')}</div>
    </details>
  );
}

const detailsStyle: CSSProperties = {
  display: 'inline-block',
  marginLeft: 8,
  fontSize: 10.5,
  fontWeight: 600,
  color: 'var(--text-4)',
};

const summaryStyle: CSSProperties = {
  cursor: 'pointer',
  userSelect: 'none',
};

const bodyStyle: CSSProperties = {
  marginTop: 4,
  fontFamily: 'var(--mono)',
  fontSize: 10,
  fontWeight: 500,
  color: 'var(--text-3)',
};
