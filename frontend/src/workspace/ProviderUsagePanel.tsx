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
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  workspaceApi,
  type ApiProviderUsageDto,
  type ApiUsageDto,
  type DeepSeekBalanceDto,
  type PlanUsageDto,
  type ProviderPlanUsageDto,
} from './workspaceApi';

export default function ProviderUsagePanel() {
  const [usage, setUsage] = useState<PlanUsageDto>({ providers: [] });
  const [apiUsage, setApiUsage] = useState<ApiUsageDto | null>(null);
  const [deepSeekBalance, setDeepSeekBalance]
    = useState<DeepSeekBalanceDto | null | undefined>(undefined);
  const attemptedAutomaticClaudeRefresh = useRef(false);
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

  const loadApiUsage = useCallback(async () => {
    if (window.bridge?.workspaceApi === undefined) return;
    try {
      const next = await workspaceApi.apiUsage();
      if (isApiUsage(next)) setApiUsage(next);
    }
    catch {
      // API accounting is optional; CLI limits remain independently useful.
    }
  }, []);

  const loadDeepSeekBalance = useCallback(async () => {
    if (window.bridge?.workspaceApi === undefined) return;
    try {
      const next = await workspaceApi.deepSeekBalance();
      setDeepSeekBalance(isDeepSeekBalance(next) ? next : null);
    }
    catch {
      setDeepSeekBalance(null);
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
      setClaudeError('Claude CLI refresh failed. ByteQuay could not read /usage from the local CLI.');
    }
    finally {
      setRefreshingClaude(false);
    }
  }, []);

  useEffect(() => {
    void load();
    void loadApiUsage();
    void loadDeepSeekBalance();
    const usageTimer = window.setInterval(() => {
      void load();
      void loadApiUsage();
    }, 60_000);
    const balanceTimer = window.setInterval(() => { void loadDeepSeekBalance(); }, 5 * 60_000);
    return () => {
      window.clearInterval(usageTimer);
      window.clearInterval(balanceTimer);
    };
  }, [load, loadApiUsage, loadDeepSeekBalance]);

  useEffect(() => {
    const claude = usage.providers.find(provider => provider.provider === 'anthropic');
    const hasInteractiveSnapshot = claude?.source === 'Claude CLI /usage'
      && claude.limits.length > 0;
    const cliUnavailable = claude?.message?.startsWith('Claude CLI is not available') === true;
    if (attemptedAutomaticClaudeRefresh.current
        || claude === undefined
        || hasInteractiveSnapshot
        || cliUnavailable) return;
    attemptedAutomaticClaudeRefresh.current = true;
    void refreshClaude();
  }, [refreshClaude, usage]);

  if (usage.providers.length === 0 && apiUsage === null) {
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
      {apiUsage !== null && (
        <ApiUsage usage={apiUsage} deepSeekBalance={deepSeekBalance} />
      )}
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

function ApiUsage({
  usage,
  deepSeekBalance,
}: {
  usage: ApiUsageDto;
  deepSeekBalance: DeepSeekBalanceDto | null | undefined;
}) {
  return (
    <div className="trunk-page-v2__provider-usage trunk-page-v2__api-usage">
      <div className="trunk-page-v2__provider-head">
        <span className="is-api">A</span>
        <strong>API usage</strong>
        <small>{formatMonth(usage.month)}</small>
      </div>
      <div className="trunk-page-v2__api-providers">
        {usage.providers.map(provider => (
          <ApiProviderUsage
            key={provider.provider}
            provider={provider}
            deepSeekBalance={provider.provider === 'deepseek' ? deepSeekBalance : undefined}
          />
        ))}
      </div>
      <small className="trunk-page-v2__usage-source">
        ByteQuay requests only · estimated cost
      </small>
    </div>
  );
}

function ApiProviderUsage({
  provider,
  deepSeekBalance,
}: {
  provider: ApiProviderUsageDto;
  deepSeekBalance: DeepSeekBalanceDto | null | undefined;
}) {
  return (
    <div className="trunk-page-v2__api-provider">
      <div>
        <strong>{provider.label}</strong>
        <strong>{formatUsd(provider.costUsdMilli)}</strong>
      </div>
      <span>
        {formatInteger(provider.callsCount)} {provider.callsCount === 1 ? 'request' : 'requests'}
      </span>
      {provider.provider === 'deepseek' && (
        <small>{formatDeepSeekBalance(deepSeekBalance)}</small>
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

function formatUsd(costUsdMilli: number): string {
  const dollars = Math.max(0, costUsdMilli) / 1_000;
  return dollars.toLocaleString('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: dollars > 0 && dollars < 0.01 ? 3 : 2,
  });
}

function formatInteger(value: number): string {
  return Math.max(0, value).toLocaleString('en-US');
}

function formatMonth(month: string): string {
  const parsed = new Date(`${month}-01T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) return 'Month to date';
  return new Intl.DateTimeFormat('en-US', {
    month: 'short', year: 'numeric', timeZone: 'UTC',
  }).format(parsed);
}

function formatDeepSeekBalance(
  balance: DeepSeekBalanceDto | null | undefined,
): string {
  if (balance === undefined) return 'Checking balance…';
  if (balance === null) return 'Balance unavailable';
  if (!balance.configured) return 'Balance not configured';
  if (balance.available !== true || balance.balances.length === 0) {
    return balance.message ?? 'Balance unavailable';
  }
  return `Balance ${balance.balances.map(info => {
    const amount = Number(info.totalBalance);
    const display = Number.isFinite(amount)
      ? amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
      : info.totalBalance;
    return `${info.currency} ${display}`;
  }).join(' · ')}`;
}

function isPlanUsage(value: unknown): value is PlanUsageDto {
  return value !== null && typeof value === 'object'
    && 'providers' in value && Array.isArray(value.providers);
}

function isApiUsage(value: unknown): value is ApiUsageDto {
  return value !== null && typeof value === 'object'
    && 'month' in value && typeof value.month === 'string'
    && 'providers' in value && Array.isArray(value.providers);
}

function isDeepSeekBalance(value: unknown): value is DeepSeekBalanceDto {
  return value !== null && typeof value === 'object'
    && 'configured' in value && typeof value.configured === 'boolean'
    && 'balances' in value && Array.isArray(value.balances);
}
