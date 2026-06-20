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
import type { TaskBrainViewData } from '../../types/brainView';
import { formatCost, formatDuration } from './format';

type Props = {
  aggregate: TaskBrainViewData['aggregate'];
  /** Label for the live pill on the right, or null to hide it. Set when
   *  a monitor/fixing stage is currently ACTIVE. */
  liveLabel: string | null;
};

type Metric = { label: string; value: string; valueClass?: string };

/**
 * The "∑ across all stages" strip: inline `label × value` metrics with
 * an amber auto-push budget pill and an optional live indicator. Wraps
 * to multiple lines on narrow viewports (`flex-wrap`).
 */
export function AggregateMetricsStrip({ aggregate, liveLabel }: Props) {
  const metrics: Metric[] = [
    { label: 'pushes', value: String(aggregate.pushes) },
    { label: 'active', value: formatDuration(aggregate.activeTimeSec) },
    { label: 'waiting', value: formatDuration(aggregate.waitingUserTimeSec) },
    { label: 'tool calls', value: String(aggregate.toolCalls) },
    { label: 'turns', value: String(aggregate.turns) },
    { label: 'messages', value: String(aggregate.messages) },
    { label: 'panels', value: String(aggregate.panels) },
    { label: 'cost', value: formatCost(aggregate.costCents), valueClass: 'cost' },
  ];

  return (
    <div className="agg-strip">
      <span className="lbl" title="Aggregate across all stages" aria-label="Aggregate across all stages">∑</span>
      <div className="items">
        {metrics.map(m => (
          <div className="m" key={m.label}>
            <span className="n">{m.label}</span>
            <span className="x" aria-hidden>×</span>
            <span className={`v${m.valueClass ? ` ${m.valueClass}` : ''}`}>{m.value}</span>
          </div>
        ))}
        {aggregate.autoPushBudget !== null && (
          <div className="m budget" title="CiFix auto-push budget">
            <span className="n">auto-push</span>
            <span className="x" aria-hidden>×</span>
            <span className="v">
              {aggregate.autoPushBudget.used}/{aggregate.autoPushBudget.limit}
            </span>
          </div>
        )}
      </div>
      {liveLabel !== null && (
        <div className="right">
          <span className="live-pill"><span className="d" />{liveLabel}</span>
        </div>
      )}
    </div>
  );
}
