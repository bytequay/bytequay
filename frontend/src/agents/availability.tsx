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
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { providerAvailable } from '../settings/model';
import { settingsRoute } from '../settings/routes';
import type { AgentModelType, ModelProviderDto, ModelSettingsDto } from '../types';

export function usableAgentProviders(settings: ModelSettingsDto | null, workspaceId?: string): ModelProviderDto[] {
  return settings?.providers.filter(provider => providerAvailable(provider.readiness) && provider.models.length > 0
    && (provider.workspaceId == null || provider.workspaceId === workspaceId)) ?? [];
}

export function canUseAgent(settings: ModelSettingsDto | null, type: AgentModelType, policy = 'AUTO', workspaceId?: string): boolean {
  const selected = settings?.agents.find(agent => agent.type === type);
  if (selected === undefined) return false;
  return usableAgentProviders(settings, workspaceId).some(provider => provider.id === selected.configId
    && provider.capabilities.permissionPolicies.includes(policy) && provider.models.some(model => model.id === selected.model));
}

export function agentAvailability(settings: ModelSettingsDto | null, workspaceId?: string) {
  return {
    any: usableAgentProviders(settings, workspaceId).some(provider => provider.capabilities.permissionPolicies.includes('AUTO')),
    development: canUseAgent(settings, 'DEVELOPMENT', 'AUTO', workspaceId),
    review: canUseAgent(settings, 'REVIEW', 'AUTO', workspaceId),
  };
}

type Catalog = { settings: ModelSettingsDto | null; loading: boolean };
type AvailabilityContext = {
  catalogs: ReadonlyMap<string, Catalog>;
  load: (workspaceId?: string, probe?: boolean) => Promise<void>;
};
const Context = createContext<AvailabilityContext | null>(null);
const LOADING: Catalog = { settings: null, loading: true };

/** One shared catalog per scope. Focus reads cached readiness; explicit setup owns expensive CLI checks. */
export function AgentAvailabilityProvider({ children }: { children: ReactNode }) {
  const [catalogs, setCatalogs] = useState<ReadonlyMap<string, Catalog>>(() => new Map());
  const pending = useRef(new Map<string, Promise<void>>());
  const scopes = useRef(new Set<string>(['']));
  const mounted = useRef(true);
  const load = useCallback((workspaceId?: string, probe = false): Promise<void> => {
    const key = workspaceId ?? '';
    scopes.current.add(key);
    const requestKey = `${key}:${probe}`;
    const existing = pending.current.get(requestKey);
    if (existing !== undefined) return existing;
    const request = Promise.resolve().then(() => probe
      ? window.bridge.refreshModelSettings(false, workspaceId) : window.bridge.getModelSettings(workspaceId))
      .then(settings => {
        if (mounted.current) setCatalogs(previous => new Map(previous).set(key, { settings, loading: false }));
      }).catch(() => {
        if (mounted.current) setCatalogs(previous => new Map(previous).set(key, { settings: null, loading: false }));
      }).finally(() => { pending.current.delete(requestKey); });
    pending.current.set(requestKey, request);
    return request;
  }, []);

  useEffect(() => {
    mounted.current = true;
    void load().then(async () => {
      if (!mounted.current) return;
      await load(undefined, true);
      if (mounted.current) for (const scope of scopes.current) if (scope !== '') void load(scope);
    });
    const reread = () => { if (mounted.current) for (const scope of scopes.current) void load(scope || undefined); };
    // A setup change can arrive while a prior read or startup probe still has old settings in flight.
    const changed = () => { void Promise.all([...pending.current.values()]).then(reread); };
    window.addEventListener('bytequay:models-changed', changed);
    window.addEventListener('focus', reread);
    return () => {
      mounted.current = false;
      window.removeEventListener('bytequay:models-changed', changed);
      window.removeEventListener('focus', reread);
    };
  }, [load]);
  const value = useMemo(() => ({ catalogs, load }), [catalogs, load]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useAgentAvailability(workspaceId?: string) {
  const context = useContext(Context);
  const catalog = context?.catalogs.get(workspaceId ?? '') ?? LOADING;
  const load = context?.load;
  useEffect(() => { if (load !== undefined) void load(workspaceId); }, [load, workspaceId]);
  const refresh = useCallback(async () => { await load?.(workspaceId); }, [load, workspaceId]);
  // Standalone surfaces are also embedded without the app shell, including existing task previews.
  if (context === null) return { settings: null, loading: false, any: true, development: true, review: true, refresh };
  return { ...catalog, ...agentAvailability(catalog.settings, workspaceId), refresh };
}

export function AgentSetupNotice({ workspaceId }: { workspaceId?: string }) {
  return <p className="agt-muted">Connect an agent in <a href={settingsRoute(workspaceId === undefined ? 'models' : 'ws-agents', workspaceId)}>Settings</a> to start agent work.</p>;
}
