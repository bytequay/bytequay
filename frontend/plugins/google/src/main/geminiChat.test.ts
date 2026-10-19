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
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { GeminiChat } from './geminiChat';
import type { GeminiSetup } from './geminiSetup';

const mocks = vi.hoisted(() => ({ spawn: vi.fn(), mkdirSync: vi.fn(), writeFileSync: vi.fn(), rmSync: vi.fn() }));
vi.mock('node:child_process', () => ({ spawn: mocks.spawn }));
vi.mock('node:fs', () => ({ mkdirSync: mocks.mkdirSync, writeFileSync: mocks.writeFileSync, rmSync: mocks.rmSync }));
const profile = '00000000-0000-0000-0000-000000000001';
const second = '00000000-0000-0000-0000-000000000002';
const secret = 'test-only-secret';
const setup = {
  status: vi.fn(async () => ({ configured: true, installed: true })),
  credentials: vi.fn(async () => ({ executable: '/test/gemini', key: secret, searchPath: '/test/bin' })),
} as unknown as GeminiSetup;
let chat: GeminiChat;
let requests: Record<string, unknown>[];
let children: ReturnType<typeof child>[];
const command = vi.fn(async () => ['/test/node', '-e', 'connector', 'profile']);
function child() {
  const value = Object.assign(new EventEmitter(), { pid: 12345, killed: false, stdout: new PassThrough(), stderr: new PassThrough(), stdin: new PassThrough(), kill: vi.fn() });
  value.stdin.on('data', (data: Buffer) => {
    const request = JSON.parse(data.toString()) as Record<string, unknown>;
    requests.push(request);
    if (request.method === 'initialize' || request.method === 'authenticate' || request.method === 'session/new') {
      queueMicrotask(() => value.stdout.write(`${JSON.stringify({ jsonrpc: '2.0', id: request.id, result: request.method === 'session/new' ? { sessionId: profile } : {} })}\n`));
    }
  });
  return value;
}
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function receive(message: unknown, index = 0) { children[index].stdout.write(`${JSON.stringify(message)}\n`); }
beforeEach(() => {
  vi.clearAllMocks(); requests = []; children = [];
  vi.spyOn(process, 'kill').mockImplementation(() => true);
  mocks.spawn.mockImplementation(() => { const value = child(); children.push(value); return value; });
  chat = new GeminiChat('/private/test', setup, command);
});
afterEach(() => { chat.dispose(); vi.restoreAllMocks(); vi.useRealTimers(); });

it('rejects missing setup before creating a conversation and allows sending after setup', async () => {
  vi.mocked(setup.status).mockResolvedValueOnce({ configured: false, installed: true });
  await expect(chat.prompt(profile, 'Read mail')).rejects.toThrow('Add a Gemini API key');
  expect(chat.status(profile)).toMatchObject({ messages: [], busy: false, error: null });
  expect(command).not.toHaveBeenCalled();
  expect(mocks.spawn).not.toHaveBeenCalled();
  vi.mocked(setup.status).mockResolvedValueOnce({ configured: true, installed: false });
  await expect(chat.prompt(profile, 'Read mail')).rejects.toThrow('Install Gemini CLI');
  expect(chat.status(profile).messages).toEqual([]);
  await chat.prompt(profile, 'Read mail'); await settle();
  expect(chat.status(profile)).toMatchObject({ messages: [{ role: 'user', text: 'Read mail' }], busy: true });
});

it('streams responses and confines secrets, tools and temporary history to the native process', async () => {
  await chat.prompt(profile, 'Find my booking reply'); await settle();
  expect(requests.map(item => item.method)).toEqual(['initialize', 'authenticate', 'session/new', 'session/prompt']);
  expect(mocks.spawn).toHaveBeenCalledWith('/test/gemini', ['--acp', '--model', 'gemini-3.8-flash'], expect.any(Object));
  expect(JSON.stringify(mocks.spawn.mock.calls)).not.toContain(secret);
  expect(JSON.stringify(mocks.writeFileSync.mock.calls)).not.toContain(secret);
  const config = JSON.parse(mocks.writeFileSync.mock.calls[0][1]);
  expect(config.tools.core.every((tool: string) => tool.startsWith('mcp_google-workspace_'))).toBe(true);
  expect(config.tools.confirmationRequired).toContain('mcp_google-workspace_gmail_send');
  expect(config.mcpServers['google-workspace'].includeTools).not.toContain('drive_search');
  expect(config.mcpServers['google-workspace'].trust).toBe(false);
  expect(config.mcpServers['google-workspace'].timeout).toBe(45_000);
  expect(requests.find(item => item.method === 'session/prompt')?.params).toEqual({ sessionId: profile,
    prompt: [{ type: 'text', text: expect.stringContaining('Current local time:') }, { type: 'text', text: 'Find my booking reply' }] });
  receive({ method: 'session/update', params: { sessionId: profile, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Confirmed ' } } } });
  receive({ method: 'session/update', params: { sessionId: profile, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: '✓' } } } });
  const prompt = requests.find(item => item.method === 'session/prompt');
  receive({ id: prompt?.id, result: { stopReason: 'end_turn' } }); await settle();
  expect(chat.status(profile).messages.at(-1)?.text).toBe('Confirmed ✓');
  expect(chat.status(profile).busy).toBe(false);
});

it.each([
  { code: 429, message: 'Quota exceeded: limit: 0, model: gemini-3.8-flash. Retry in 60s.' },
  { code: 403, message: `API key ${secret} is invalid. ${secret}` },
])('shows provider error $code without leaking the key or error data', async error => {
  await chat.prompt(profile, 'Read mail'); await settle();
  receive({ id: requests.find(item => item.method === 'session/prompt')?.id, error: { ...error, data: { private: 'private-diagnostic' } } });
  await settle();
  expect(chat.status(profile)).toMatchObject({ busy: false, activity: '', approval: null,
    error: `Gemini (${error.code}): ${error.message.replaceAll(secret, '[REDACTED]')} If a send was in progress, check Sent mail before retrying.` });
  expect(JSON.stringify(chat.status(profile))).not.toContain(secret);
  expect(JSON.stringify(chat.status(profile))).not.toContain('private-diagnostic');
  expect(process.kill).toHaveBeenCalledWith(-12345, 'SIGKILL');
  expect(requests.filter(item => item.method === 'session/prompt')).toHaveLength(1);
});

it.each([{}, { message: { secret } }, { message: '' }])('handles provider errors without a text message', async error => {
  await chat.prompt(profile, 'Read mail'); await settle();
  receive({ id: requests.find(item => item.method === 'session/prompt')?.id, error }); await settle();
  expect(chat.status(profile).error).toContain('Gemini: Request failed.');
  expect(JSON.stringify(chat.status(profile))).not.toContain(secret);
});

it('redacts secrets before truncating lengthy provider errors', async () => {
  await chat.prompt(profile, 'Read mail'); await settle();
  receive({ id: requests.find(item => item.method === 'session/prompt')?.id, error: { code: 429, message: 'x'.repeat(3995) + secret + 'y'.repeat(5000) } });
  await settle();
  expect(chat.status(profile).error).not.toContain('test-');
  expect(chat.status(profile).error?.length).toBeLessThan(4200);
});

it('clears completed Gmail activity while retaining other in-progress tools and accepts the final answer', async () => {
  await chat.prompt(profile, 'Any urgent emails today?'); await settle();
  const update = (value: unknown) => receive({ method: 'session/update', params: { sessionId: profile, update: value } });
  update({ sessionUpdate: 'tool_call', toolCallId: 'one', title: 'Reading first email', status: 'in_progress' });
  update({ sessionUpdate: 'tool_call', toolCallId: 'two', title: 'Reading second email', status: 'in_progress' });
  update({ sessionUpdate: 'tool_call_update', toolCallId: 'two', status: 'in_progress' });
  expect(chat.status(profile).activity).toBe('Reading second email');
  update({ sessionUpdate: 'tool_call_update', toolCallId: 'two', title: 'Reading second email', status: 'completed' });
  expect(chat.status(profile).activity).toBe('Reading first email');
  update({ sessionUpdate: 'tool_call_update', toolCallId: 'one', status: 'failed' });
  expect(chat.status(profile).activity).toBe('Gemini is reviewing Google results…');
  update({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Here is what I found.' } });
  receive({ id: requests.find(item => item.method === 'session/prompt')?.id, result: { stopReason: 'end_turn' } }); await settle();
  expect(chat.status(profile)).toMatchObject({ busy: false, activity: '', error: null });
  expect(chat.status(profile).messages.slice(1)).toEqual([{ role: 'tool', text: 'Reading first email' }, { role: 'tool', text: 'Reading second email' },
    { role: 'assistant', text: 'Here is what I found.' }]);
});

it('times out silence rather than the whole turn, and permits a fresh conversation', async () => {
  vi.useFakeTimers();
  await chat.prompt(profile, 'Read mail'); await settle();
  await vi.advanceTimersByTimeAsync(90_000);
  receive({ method: 'session/update', params: { sessionId: profile,
    update: { sessionUpdate: 'tool_call', toolCallId: 'mail', title: 'Read email', status: 'in_progress' } } });
  await vi.advanceTimersByTimeAsync(90_000);
  expect(chat.status(profile).busy).toBe(true);
  await vi.advanceTimersByTimeAsync(30_000);
  expect(chat.status(profile)).toMatchObject({ busy: false, activity: '', error: expect.stringContaining('stopped responding') });
  expect(process.kill).toHaveBeenCalledWith(-12345, 'SIGKILL');
  chat.clear(profile);
  await chat.prompt(profile, 'Try again'); await settle();
  expect(chat.status(profile)).toMatchObject({ busy: true, error: null });
});

it('does not time out while the user is considering a write approval', async () => {
  vi.useFakeTimers();
  await chat.prompt(profile, 'Send my reply'); await settle();
  receive({ id: 'approval-1', method: 'session/request_permission', params: { sessionId: profile,
    toolCall: { title: 'Send email' }, options: [{ kind: 'allow_once', optionId: 'once', name: 'Allow once' }] } });
  await vi.advanceTimersByTimeAsync(300_000);
  expect(chat.status(profile).busy).toBe(true);
  expect(chat.status(profile).approval?.id).toBe('approval-1');
  chat.approve(profile, 'approval-1', 'once');
  await vi.advanceTimersByTimeAsync(120_000);
  expect(chat.status(profile).busy).toBe(false);
  expect(chat.status(profile).error).toContain('check Sent mail');
});

it('waits for explicit one-time approval and rejects stale or persistent approvals', async () => {
  await chat.prompt(profile, 'Send my reply'); await settle();
  receive({ id: 'approval-1', method: 'session/request_permission', params: { sessionId: profile,
    toolCall: { title: 'Send email', content: [{ type: 'text', text: 'To: test@example.com' }] },
    options: [{ kind: 'allow_once', optionId: 'once', name: 'Allow once' }, { kind: 'allow_always', optionId: 'always', name: 'Always' }] } });
  expect(chat.status(profile).approval?.options).toEqual([{ id: 'once', label: 'Allow once' }]);
  expect(requests.some(item => item.id === 'approval-1')).toBe(false);
  expect(() => chat.approve(profile, 'approval-1', 'always')).toThrow('no longer');
  chat.approve(profile, 'approval-1', 'once');
  expect(requests.at(-1)?.result).toEqual({ outcome: { outcome: 'selected', optionId: 'once' } });
  expect(() => chat.approve(profile, 'approval-1', 'once')).toThrow('no longer');
});

it('isolates account conversations and cancels the whole process group without replaying writes', async () => {
  await chat.prompt(profile, 'One'); await settle();
  await chat.prompt(second, 'Two'); await settle();
  expect(chat.status(second).messages[0].text).toBe('Two');
  chat.stop(profile); await settle();
  expect(process.kill).toHaveBeenCalledWith(-12345, 'SIGKILL');
  expect(chat.status(profile).busy).toBe(false);
  expect(chat.status(second).busy).toBe(true);
  await expect(chat.prompt(profile, 'Retry')).rejects.toThrow('Clear the conversation');
  chat.clear(profile);
  expect(chat.status(profile).messages).toEqual([]);
});

it('fails closed on malformed protocol data and cleans runtime files on quit', async () => {
  await chat.prompt(profile, 'Read mail'); await settle();
  children[0].stdout.write('not-json\n'); await settle();
  expect(chat.status(profile).error).toContain('stopped');
  chat.dispose();
  expect(mocks.rmSync).toHaveBeenLastCalledWith('/private/test/google-chat-runtime', { recursive: true, force: true });
});
