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
import { ArrowLeft, ArrowRight, Check, Monitor, Moon, Sun } from 'lucide-react';
import HostingOnboarding from '../hosting/HostingOnboarding';
import { WindowControls } from '../AppSidebar';
import { agentAvailability, canUseAgent, usableAgentProviders } from '../agents/availability';
import { AUTOMATION_STEPS, includesLevel, levelIndex } from '../agents/developmentAutomation';
import { AutomationSlider, CiProviderSelect } from '../agents/AutomationControl';
import { AgentModelControls, type ModelChoice } from '../settings/ModelsPage';
import { modelEffort } from '../settings/model';
import { SettingsCard, SettingsRow, TimePill, Toggle } from '../settings/primitives';
import type { AgendaScheduleDto, AppPreferencesDto, AppThemeDto, AssistantPermissionsDto, DevelopmentAutomationDto, ModelSettingsDto, SetupPreferencesDto, SetupStep } from '../types';
import AgentSetup from './AgentSetup';
import FeaturePreview from './FeaturePreview';
import { SETUP_CHANGED, SETUP_STEPS } from './setup';
import '../css/settings-window.css';
import './setup.css';

export default function SetupFlow({ initial, connected, revisit = false, onSaved, onConnected, onFinish }: {
  initial: SetupPreferencesDto;
  connected: boolean;
  revisit?: boolean;
  onSaved: (value: SetupPreferencesDto) => void;
  onConnected: () => void;
  onFinish: () => void;
}) {
  const [step, setStep] = useState<SetupStep>(initial.step === 'COMPLETE' ? 'APPEARANCE' : initial.step);
  const [preferences, setPreferences] = useState<AppPreferencesDto | null>(null);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [automation, setAutomation] = useState<DevelopmentAutomationDto | null>(null);
  const [agenda, setAgenda] = useState<AgendaScheduleDto | null>(null);
  const [permissions, setPermissions] = useState<AssistantPermissionsDto | null>(null);
  const [assistantEnabled, setAssistantEnabled] = useState(initial.assistantEnabled);
  const [choice, setChoice] = useState<ModelChoice | null>(null);
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const working = useRef(false);
  const modelRequest = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const report = useCallback((failure: unknown) => setError(failure instanceof Error ? failure.message : String(failure)), []);
  const receiveModels = useCallback((value: ModelSettingsDto) => {
    setModels(value);
    window.dispatchEvent(new Event('bytequay:models-changed'));
  }, []);
  const load = useCallback(async () => {
    setError(null);
    try {
      const [prefs, catalog, savedAutomation, schedule, actions] = await Promise.all([
        window.bridge.getAppPreferences(), window.bridge.getModelSettings(), window.bridge.getDevelopmentAutomation(),
        window.bridge.getAgendaSchedule(), window.bridge.getAssistantPermissions(),
      ]);
      setPreferences(prefs); setModels(current => current ?? catalog); setAutomation(savedAutomation); setAgenda(schedule); setPermissions(actions);
    }
    catch (failure) { report(failure); }
  }, [report]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { heading.current?.focus(); }, [step]);

  const check = useCallback(async (force = true) => {
    const ticket = ++modelRequest.current;
    setChecking(true); setError(null);
    try {
      const catalog = await window.bridge.refreshModelSettings(force);
      if (ticket === modelRequest.current) receiveModels(catalog);
    }
    catch (failure) { if (ticket === modelRequest.current) report(failure); }
    finally { if (ticket === modelRequest.current) setChecking(false); }
  }, [receiveModels, report]);
  useEffect(() => { if (step === 'AGENT') void check(false); }, [step, check]);

  const act = async (action: () => Promise<void>) => {
    if (working.current) return;
    working.current = true; setBusy(true); setError(null);
    try { await action(); }
    catch (failure) { report(failure); }
    finally { working.current = false; setBusy(false); }
  };
  const saveStep = async (next: SetupStep, enabled = assistantEnabled) => {
    const value = await window.bridge.saveSetupPreferences({ step: revisit ? 'COMPLETE' : next, assistantEnabled: enabled });
    onSaved(value);
    window.dispatchEvent(new Event(SETUP_CHANGED));
    if (next === 'COMPLETE') onFinish();
    else setStep(next);
  };
  const index = SETUP_STEPS.findIndex(item => item.id === step);
  const current = SETUP_STEPS[index] ?? SETUP_STEPS[0];
  const ready = agentAvailability(models);
  const providers = usableAgentProviders(models).filter(provider => provider.capabilities.permissionPolicies.includes('AUTO'));
  const savedChoice = models?.agents.find(agent => agent.type === 'DEVELOPMENT');
  const selected = choice ?? (ready.development && savedChoice ? { ...savedChoice, effort: savedChoice.effort ?? null } : null);
  const first = providers[0];
  const firstModel = first?.models.find(model => model.id === first.selectedModel) ?? first?.models[0];
  const selection: ModelChoice | null = selected ?? (first && firstModel ? {
    configId: first.id, model: firstModel.id, effort: modelEffort(firstModel, first.selectedEffort) || null,
  } : null);
  const selectedProvider = providers.find(provider => provider.id === selection?.configId);
  const selectionUsable = selectedProvider?.models.some(model => model.id === selection?.model) === true;
  const ciProviders = providers.filter(provider => provider.capabilities.permissionPolicies.includes('CI_AUTOFIX'));
  const ciSupported = automation?.ciLaneId != null ? ciProviders.some(provider => provider.id === automation.ciLaneId)
    : canUseAgent(models, 'DEVELOPMENT', 'CI_AUTOFIX');
  const loaded = preferences !== null && models !== null && automation !== null && agenda !== null && permissions !== null;
  const nextDisabled = !loaded || busy || checking || connecting || step === 'GITHUB' && !connected || step === 'AGENT' && !ready.any
    || step === 'DEFAULTS' && !selectionUsable || ['AUTOMATION', 'FEATURES'].includes(step) && !ready.development
    || step === 'AUTOMATION' && automation !== null && includesLevel(automation.level, 'CI_FIX') && !ciSupported;

  const next = () => act(async () => {
    if (nextDisabled || models === null || automation === null || agenda === null || permissions === null) return;
    if (step === 'DEFAULTS' && selection !== null) {
      receiveModels(await window.bridge.setAgentModel('DEVELOPMENT', selection.configId, selection.model, selection.effort));
    }
    if (step === 'AUTOMATION') setAutomation(await window.bridge.saveDevelopmentAutomation(automation));
    if (step === 'FEATURES') {
      setAgenda(await window.bridge.saveAgendaSchedule(agenda));
      setPermissions(await window.bridge.saveAssistantPermissions(permissions));
    }
    await saveStep(SETUP_STEPS[index + 1]?.id ?? 'COMPLETE');
  });

  return <div className="setup-shell">
    <aside className="setup-sidebar">
      <WindowControls />
      <div className="setup-brand">ByteQuay<span>Your work, together.</span></div>
      <ol aria-label="Setup progress">{SETUP_STEPS.map((item, position) => <li key={item.id} aria-current={item.id === step ? 'step' : undefined}>
        <span>{position < index ? <Check size={14} /> : position + 1}</span>{item.label}
      </li>)}</ol>
      <p>Saved on this Mac.<br />Once finished, setup stays out of your way.</p>
    </aside>
    <main className="setup-main">
      <header><span className="setup-eyebrow">SET UP YOUR WORKSPACE · {index + 1} OF {SETUP_STEPS.length}</span>
        <h1 ref={heading} tabIndex={-1}>{current.title}</h1><p>{current.description}</p></header>
      {error !== null && <div className="setup-error" role="alert">{error}{!loaded && <button type="button" onClick={() => { void load(); }}>Try again</button>}</div>}
      {!loaded ? <p role="status">Loading your preferences…</p> : <div className="setup-body">
        {step === 'APPEARANCE' && <>
          <div className="setup-themes" role="group" aria-label="Appearance">
            {([{ value: 'SYSTEM', label: 'System', Icon: Monitor }, { value: 'LIGHT', label: 'Light', Icon: Sun }, { value: 'DARK', label: 'Dark', Icon: Moon }] as const)
              .map(({ value, label, Icon }) => <button key={value} type="button" aria-pressed={preferences.theme === value} disabled={busy}
                className={`setup-theme setup-theme--${value.toLowerCase()}`} onClick={() => { void act(async () => {
                  setPreferences(await window.bridge.saveAppPreferences({ ...preferences, theme: value as AppThemeDto }));
                }); }}>
                <span className="setup-theme-window"><i /><span><b /><b /><b /></span></span><span><Icon size={16} />{label}{preferences.theme === value && <Check size={16} />}</span>
              </button>)}
          </div>
          <p className="setup-hint">System follows your Mac’s appearance, including automatic changes.</p>
          <FeaturePreview feature="development" />
        </>}
        {step === 'GITHUB' && (connected ? <div className="setup-connected"><Check size={24} /><h2>Code hosting is connected</h2><p>Your existing account is ready to use.</p></div>
          : <HostingOnboarding embedded onSaved={onConnected} />)}
        {step === 'AGENT' && <AgentSetup models={models} checking={checking} busy={busy} onBusy={setConnecting} onCheck={() => { void check(); }} onChange={value => {
          modelRequest.current += 1; setChecking(false); receiveModels(value);
        }} onError={report} />}
        {step === 'DEFAULTS' && <>
          {selection !== null && <SettingsCard><SettingsRow title="Development agent" subtitle="Used by new tasks, Today, and Assistant">
            <AgentModelControls agent={{ type: 'DEVELOPMENT', explicit: true, configId: selection.configId, model: selection.model, effort: selection.effort,
              label: selectedProvider?.label ?? '', modelLabel: selectedProvider?.models.find(model => model.id === selection.model)?.name ?? selection.model }}
              settings={{ ...models, providers, apiKeys: [] }} onChoose={setChoice} />
          </SettingsRow></SettingsCard>}
          {!selectionUsable && <p role="status">Connect an available agent to choose your default.</p>}
          <p className="setup-hint">Review follows Development unless you choose a separate model in Settings. Workspaces can have their own overrides.</p>
          <FeaturePreview feature="development" />
        </>}
        {step === 'AUTOMATION' && <>
          <SettingsCard><SettingsRow title={AUTOMATION_STEPS[levelIndex(automation.level)].title} subtitle={AUTOMATION_STEPS[levelIndex(automation.level)].description}>
            <div className="agt-auto-inline">
              <AutomationSlider value={automation.level} onChange={busy || !ready.development ? undefined : level => setAutomation({ ...automation, level })} />
            </div>
          </SettingsRow>
            {includesLevel(automation.level, 'CI_FIX') && (automation.ciLaneId !== null || ciProviders.length > 1 || ciProviders.length > 0 && !canUseAgent(models, 'DEVELOPMENT', 'CI_AUTOFIX')) && <SettingsRow title="CI repair" subtitle="Choose an agent that supports CI repair">
              <CiProviderSelect value={automation.ciLaneId} providers={ciProviders} disabled={busy}
                onChange={ciLaneId => setAutomation({ ...automation, ciLaneId })} />
            </SettingsRow>}
          </SettingsCard>
          {includesLevel(automation.level, 'CI_FIX') && !ciSupported && <p role="status">This agent can’t repair CI. Choose a CI repair agent or an earlier step.</p>}
          <p className="setup-hint">Each step includes the ones before it. Auto-Commit keeps work on this Mac; every later step can publish to GitHub.</p>
          <FeaturePreview feature="automation" />
        </>}
        {step === 'FEATURES' && <div className="setup-features">
          <section className="setup-feature"><SettingsRow title="Today · Daily agenda" subtitle="A daily shortlist from your work. Runs on your working days and uses a model session.">
            <Toggle label="Enable daily agenda" on={agenda.enabled} disabled={busy || !ready.development} onChange={enabled => setAgenda({ ...agenda, enabled })} />
          </SettingsRow>
            {agenda.enabled && <SettingsRow title="Make it at" subtitle="Change working days in Settings">
              <TimePill minute={agenda.minute} ariaLabel="Daily agenda time" onSelect={minute => setAgenda({ ...agenda, minute })} disabled={busy} />
            </SettingsRow>}
            <FeaturePreview feature="today" />
          </section>
          <section className="setup-feature"><SettingsRow title="Assistant" subtitle="Ask about your day and the work on screen. Uses your model when you send a message.">
            <Toggle label="Enable Assistant" on={assistantEnabled} disabled={busy || !ready.development} onChange={setAssistantEnabled} />
          </SettingsRow>
            {assistantEnabled && <SettingsCard>
              <SettingsRow title="Start tasks when I ask" subtitle="Uses the automation preferences you chose.">
                <Toggle label="Assistant can start tasks" on={permissions.startTasks} disabled={busy} onChange={startTasks => setPermissions({ ...permissions, startTasks })} />
              </SettingsRow>
              <SettingsRow title="Answer tasks when I ask" subtitle="Send your replies and answer supported approvals and questions.">
                <Toggle label="Assistant can answer tasks" on={permissions.answerTasks} disabled={busy} onChange={answerTasks => setPermissions({ ...permissions, answerTasks })} />
              </SettingsRow>
            </SettingsCard>}
            <FeaturePreview feature="assistant" />
          </section>
        </div>}
        {['AUTOMATION', 'FEATURES'].includes(step) && !ready.development && <p className="setup-error">Your default agent needs attention. Go back to connect an available agent, or continue without one.</p>}
      </div>}
      <footer className="setup-footer">
        {index > 0 && <button type="button" className="setw-btn" disabled={busy || checking || connecting} onClick={() => { void act(() => saveStep(SETUP_STEPS[index - 1].id)); }}><ArrowLeft size={15} />Back</button>}
        <span />
        {revisit && <button type="button" className="setup-text-button" disabled={busy || connecting} onClick={onFinish}>Close setup</button>}
        {connected && index >= 2 && <button type="button" className="setup-text-button" disabled={busy || checking || connecting} onClick={() => { void act(() => saveStep('COMPLETE', initial.assistantEnabled)); }}>
          {ready.any ? 'Skip remaining setup' : 'Continue without an agent'}
        </button>}
        <button type="button" className="setup-next" disabled={nextDisabled} onClick={() => { void next(); }}>{busy ? 'Saving…' : step === 'FEATURES' ? 'Open ByteQuay' : 'Continue'}<ArrowRight size={15} /></button>
      </footer>
    </main>
  </div>;
}
