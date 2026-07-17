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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import type { WorkspaceInsightsDto } from '../types';

type InsightsWindow = '24h' | '7d' | '30d';

const WINDOWS: InsightsWindow[] = ['24h', '7d', '30d'];
const KIND_COLORS = ['#0969da', '#2da44e', '#8250df', '#cf222e'];

type Props = {
  workspaceId: string;
  presentation?: 'page' | 'provider-card';
};

/**
 * Exact implementation of frame 3i. Values stay live and workspace scoped;
 * only the presentation is copied from the supplied source design.
 */
function WorkspaceInsightsPage({ workspaceId, presentation = 'page' }: Props) {
  const [insights, setInsights] = useState<WorkspaceInsightsDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [windowKey, setWindowKey] = useState<InsightsWindow>('7d');

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setInsights(await window.bridge.getWorkspaceInsights(workspaceId, windowKey));
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId, windowKey]);

  useEffect(() => { void refresh(); }, [refresh]);

  const usages = insights?.usageByProvider ?? insights?.usageByKind ?? [];
  const totalTokens = useMemo(
    () => usages.reduce((sum, usage) => sum + usage.tokensIn + usage.tokensOut, 0),
    [usages],
  );
  const costByKind = insights?.usageByKind ?? [];

  if (presentation === 'provider-card') {
    return (
      <div className="wu-insights-provider-frame">
        <ProviderCostCard
          usages={insights?.usageByProvider ?? []}
          windowKey={windowKey}
          loading={loading}
        />
      </div>
    );
  }

  return (
    <div className="wu-insights">
      <header className="wu-insights__header">
        <strong>Insights</strong>
        <div className="wu-insights__window" role="tablist">
          {WINDOWS.map(value => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={windowKey === value}
              className={windowKey === value ? 'is-active' : ''}
              onClick={() => setWindowKey(value)}
            >
              {value}
            </button>
          ))}
        </div>
      </header>

      <div className="wu-insights__body">
        {error !== null && <div className="wu-insights__error" role="alert">{error}</div>}

        <div className="wu-insights__kpis">
          <InsightKpi
            icon={<TaskBranchIcon />}
            tone="violet"
            value={loading ? '—' : String(insights?.tasksShippedInWindow ?? 0)}
            label="tasks shipped"
          />
          <InsightKpi
            icon={<TrunkIcon />}
            tone="blue"
            value={loading ? '—' : String(insights?.activeThreads ?? 0)}
            label="threads active"
          />
          <InsightKpi
            icon={<SpendIcon />}
            tone="green"
            value={loading ? '—' : formatMilliUsd(insights?.spendInWindowMilli ?? 0)}
            label={`spend · ${windowKey}`}
          />
          <InsightKpi
            icon={<TokensIcon />}
            tone="amber"
            value={loading ? '—' : formatTokens(totalTokens)}
            label={`tokens · ${windowKey}`}
          />
        </div>

        <div className="wu-insights__lower">
          <section className="wu-insights__spend" aria-label="Spend">
            <div className="wu-insights__card-head">
              <strong>Spend</strong>
              <span>
                {windowKey} · <b>{formatMilliUsd(insights?.spendInWindowMilli ?? 0)}</b>
              </span>
            </div>
            <SpendChart series={insights?.spendByDay ?? []} />
          </section>

          <div className="wu-insights__side">
            <RateLimitCard rate={insights?.githubRateLimit ?? null} loading={loading} />
            <section className="wu-insights__kind-cost" aria-label="Session cost by kind">
              <strong>Session cost by kind</strong>
              {costByKind.length === 0 && !loading && (
                <span className="wu-insights__empty">No session spend in this window.</span>
              )}
              {costByKind.slice(0, 3).map((usage, index) => (
                <div className="wu-insights__kind-row" key={usage.key}>
                  <i style={{ background: KIND_COLORS[index] }} />
                  <span>{usage.key}</span>
                  <code>{formatWholeUsd(usage.costUsdMilli)}</code>
                </div>
              ))}
              {loading && <span className="wu-insights__empty">Loading…</span>}
            </section>
          </div>
        </div>
      </div>
    </div>
  );
}

function ProviderCostCard({
  usages,
  windowKey,
  loading,
}: {
  usages: NonNullable<WorkspaceInsightsDto['usageByProvider']>;
  windowKey: InsightsWindow;
  loading: boolean;
}) {
  const providers = [
    {
      key: 'anthropic',
      label: 'Claude',
      initial: 'C',
      tone: 'claude',
      color: '#0969da',
    },
    {
      key: 'openai',
      label: 'GPT',
      initial: 'G',
      tone: 'gpt',
      color: '#2da44e',
    },
    {
      key: 'local',
      label: 'Local',
      initial: 'L',
      tone: 'local',
      color: '#8250df',
    },
  ] as const;
  const total = usages.reduce((sum, usage) => sum + usage.costUsdMilli, 0);

  return (
    <section className="wu-provider-cost-card">
      <div className="wu-provider-cost-card__head">
        <span>Session cost</span>
        <span className="wu-provider-cost-card__toggle">
          <span>by kind</span>
          <span className="is-active">by provider</span>
        </span>
      </div>
      <div className="wu-provider-cost-card__rows">
        {providers.map(provider => {
          const usage = usages.find(row => row.key.toLowerCase() === provider.key);
          const cost = usage?.costUsdMilli ?? 0;
          const percent = total === 0 ? 0 : Math.round((cost / total) * 100);
          return (
            <div className="wu-provider-cost-card__row" key={provider.key}>
              <span
                title={`Provider icon: ${provider.label}`}
                className={`wu-provider-cost-card__avatar is-${provider.tone}`}
              >
                {provider.initial}
              </span>
              <span className="wu-provider-cost-card__name">{provider.label}</span>
              <div className="wu-provider-cost-card__track">
                <div style={{ width: `${percent}%`, background: provider.color }} />
              </div>
              <span className="wu-provider-cost-card__amount">
                {loading ? '—' : formatMilliUsd(cost)}
              </span>
            </div>
          );
        })}
      </div>
      <span className="wu-provider-cost-card__foot">
        {windowKey} · sessions also filterable by provider in the Sessions tab
      </span>
    </section>
  );
}

function InsightKpi({
  icon,
  tone,
  value,
  label,
}: {
  icon: ReactNode;
  tone: 'violet' | 'blue' | 'green' | 'amber';
  value: string;
  label: string;
}) {
  return (
    <section className="wu-insights__kpi">
      <span className={`wu-insights__kpi-icon is-${tone}`} aria-hidden>{icon}</span>
      <div>
        <strong>{value}</strong>
        <span>{label}</span>
      </div>
    </section>
  );
}

function RateLimitCard({
  rate,
  loading,
}: {
  rate: WorkspaceInsightsDto['githubRateLimit'];
  loading: boolean;
}) {
  const used = rate === null || rate.limit === 0 ? 0 : (rate.remaining / rate.limit) * 100;
  return (
    <section className="wu-insights__rate" aria-label="GitHub API">
      <div className="wu-insights__card-head">
        <strong>GitHub API</strong>
        <code>
          {loading || rate === null
            ? '— / —'
            : `${rate.remaining.toLocaleString()} / ${rate.limit.toLocaleString()}`}
        </code>
      </div>
      <div className="wu-insights__rate-track">
        <i style={{ width: `${used}%` }} />
      </div>
      <span>
        {rate === null
          ? 'rate limit unavailable'
          : `resets ${new Date(rate.resetAt).toLocaleTimeString([], {
            hour: 'numeric',
            minute: '2-digit',
          })}`}
      </span>
    </section>
  );
}

function SpendChart({ series }: { series: WorkspaceInsightsDto['spendByDay'] }) {
  if (series.length === 0) {
    return <div className="wu-insights__chart is-empty">No spend in this window.</div>;
  }
  const visible = series.slice(-7);
  const peak = Math.max(...visible.map(point => point.costUsdMilli), 1);
  return (
    <div className="wu-insights__chart">
      {visible.map((point, index) => {
        const ratio = point.costUsdMilli / peak;
        const barClass = point.costUsdMilli === peak
          ? 'is-peak'
          : index >= visible.length - 3 ? 'is-recent' : '';
        return (
          <div className="wu-insights__bar-column" key={`${point.date}:${index}`}>
            <code>{formatChartUsd(point.costUsdMilli)}</code>
            <i className={barClass} style={{ height: `${Math.max(4, ratio * 86)}%` }} />
            <span className={point.costUsdMilli === peak ? 'is-peak' : ''}>{point.label}</span>
          </div>
        );
      })}
    </div>
  );
}

function TaskBranchIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="18" cy="18" r="2.6" />
      <circle cx="6" cy="6" r="2.6" />
      <path d="M6 21V9a9 9 0 0 0 9 9" />
    </svg>
  );
}

function TrunkIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="12" r="2.4" />
      <path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" />
    </svg>
  );
}

function SpendIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round">
      <path d="M12 2v20" />
      <path d="M17 5.5H9.5a3 3 0 0 0 0 6h5a3 3 0 0 1 0 6H6" />
    </svg>
  );
}

function TokensIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round">
      <path d="M6 20v-8" />
      <path d="M12 20V4" />
      <path d="M18 20v-6" />
    </svg>
  );
}

function formatMilliUsd(milli: number): string {
  return `$${(milli / 1000).toFixed(2)}`;
}

function formatWholeUsd(milli: number): string {
  const dollars = milli / 1000;
  return `$${Number.isInteger(dollars) ? dollars.toFixed(0) : dollars.toFixed(2)}`;
}

function formatChartUsd(milli: number): string {
  const dollars = milli / 1000;
  return `$${Number.isInteger(dollars) ? dollars.toFixed(0) : dollars.toFixed(2)}`;
}

function formatTokens(tokens: number): string {
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(1)}M`;
  if (tokens >= 1_000) return `${Math.round(tokens / 1_000)}K`;
  return String(tokens);
}

export default WorkspaceInsightsPage;
