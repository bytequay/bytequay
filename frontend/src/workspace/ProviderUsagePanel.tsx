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
import { useCallback, useEffect, useState } from 'react';
import {
  workspaceApi,
  type PlanUsageDto,
  type ProviderPlanUsageDto,
} from './workspaceApi';

export default function ProviderUsagePanel() {
  const [usage, setUsage] = useState<PlanUsageDto>({ providers: [] });
  const [refreshingClaude, setRefreshingClaude] = useState(false);
  const [claudeError, setClaudeError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (window.bridge?.workspaceApi === undefined) return;
    try {
      const next = await workspaceApi.planUsage();
      if (isPlanUsage(next)) setUsage(next);
    }
    catch {
      // Usage is optional UI data; keep the last truthful snapshot.
    }
  }, []);

  const refreshClaude = useCallback(async () => {
    if (window.bridge?.workspaceApi === undefined) return;
    setRefreshingClaude(true);
    setClaudeError(null);
    try {
      const next = await workspaceApi.refreshClaudeUsage();
      if (isPlanUsage(next)) setUsage(next);
    }
    catch {
      setClaudeError('Claude CLI refresh failed. Check that the CLI is installed and signed in.');
    }
    finally {
      setRefreshingClaude(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(); }, 60_000);
    return () => window.clearInterval(timer);
  }, [load]);

  if (usage.providers.length === 0) {
    return <p className="trunk-page-v2__usage-empty">CLI usage is unavailable.</p>;
  }
  return (
    <div className="trunk-page-v2__plan-usage">
      {usage.providers.map(provider => (
        <ProviderUsage
          key={provider.provider}
          provider={provider}
          refreshingClaude={refreshingClaude}
          claudeError={claudeError}
          onRefreshClaude={refreshClaude}
        />
      ))}
    </div>
  );
}

function ProviderUsage({
  provider,
  refreshingClaude,
  claudeError,
  onRefreshClaude,
}: {
  provider: ProviderPlanUsageDto;
  refreshingClaude: boolean;
  claudeError: string | null;
  onRefreshClaude: () => Promise<void>;
}) {
  return (
    <div className="trunk-page-v2__provider-usage">
      <div className="trunk-page-v2__provider-head">
        <span className={`is-${provider.provider}`}>{provider.label.charAt(0)}</span>
        <strong>{provider.label}</strong>
        {provider.plan !== null && <small>{formatPlan(provider.plan)}</small>}
        {provider.provider === 'anthropic' && (
          <button type="button" disabled={refreshingClaude}
            aria-label="Refresh Claude CLI usage" onClick={() => { void onRefreshClaude(); }}>
            {refreshingClaude ? 'Refreshing…' : 'Refresh'}
          </button>
        )}
      </div>
      {provider.provider === 'anthropic' && claudeError !== null && <p>{claudeError}</p>}
      {provider.limits.length === 0 ? (
        <p>{provider.message ?? `${provider.label} usage is unavailable.`}</p>
      ) : provider.limits.map(limit => {
        const tone = limit.usedPercent >= 90 ? 'critical'
          : limit.usedPercent >= 70 ? 'warning' : 'normal';
        const displayLabel = limit.model ?? limit.label;
        return (
          <div className="trunk-page-v2__limit" key={limit.id}>
            <div>
              <span>{displayLabel}</span>
              <strong>{formatPercent(limit.usedPercent)} used</strong>
            </div>
            <i aria-label={`${displayLabel} ${formatPercent(limit.usedPercent)} used`}>
              <i className={`is-${tone}`} style={{ width: `${limit.usedPercent}%` }} />
            </i>
            <small>{formatReset(limit.resetsAt)}</small>
          </div>
        );
      })}
      {provider.updatedAt > 0 && (
        <small className="trunk-page-v2__usage-source">
          {provider.source ?? provider.label} · {formatUpdated(provider.updatedAt)}
        </small>
      )}
    </div>
  );
}

function formatPercent(percent: number): string {
  return `${Number.isInteger(percent) ? percent.toFixed(0) : percent.toFixed(1)}%`;
}

function formatReset(resetsAt: number): string {
  if (resetsAt <= 0) return 'Reset time unavailable';
  const remainingMinutes = Math.max(0, Math.ceil((resetsAt - Date.now()) / 60_000));
  if (remainingMinutes < 24 * 60) {
    const hours = Math.floor(remainingMinutes / 60);
    const minutes = remainingMinutes % 60;
    if (hours === 0) return `Resets in ${minutes}m`;
    return `Resets in ${hours}h${minutes === 0 ? '' : ` ${minutes}m`}`;
  }
  return `Resets ${new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric',
  }).format(new Date(resetsAt))}`;
}

function formatUpdated(updatedAt: number): string {
  const minutes = Math.max(0, Math.floor((Date.now() - updatedAt) / 60_000));
  if (minutes < 1) return 'updated now';
  if (minutes < 60) return `updated ${minutes}m ago`;
  return `updated ${Math.floor(minutes / 60)}h ago`;
}

function formatPlan(plan: string): string {
  if (plan.toLowerCase() === 'prolite') return 'Pro Lite';
  return plan.split(/[-_\s]+/).filter(Boolean)
    .map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

function isPlanUsage(value: unknown): value is PlanUsageDto {
  return value !== null && typeof value === 'object'
    && 'providers' in value && Array.isArray(value.providers);
}
