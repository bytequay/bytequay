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

/**
 * Shared helpers for rendering agent tool calls. Centralised so the
 * several conversation surfaces (task chat, thread detail, the structured
 * / tile / terminal panes) agree on what counts as a shell command and
 * how to read it — otherwise each one re-lists the tool names and a new
 * agent's naming (e.g. Codex's {@code command_execution}) slips through
 * one surface as a raw JSON dump.
 */

/** Tool names that run a shell command. Claude uses {@code Bash}; Codex
 *  CLI uses {@code command_execution}; older agents {@code run_shell} /
 *  {@code shell}. They all carry the command under {@code command}/
 *  {@code cmd}. */
const SHELL_TOOLS: ReadonlySet<string> = new Set([
  'Bash',
  'command_execution',
  'run_shell',
  'shell',
]);

export function isShellTool(toolName: string): boolean {
  return SHELL_TOOLS.has(toolName);
}

/**
 * Pull the command string from a shell tool's input — a plain string, or
 * an argv array (some Codex payloads) joined back into one line. Returns
 * an empty string when no command field is present.
 */
export function shellCommand(input: unknown): string {
  if (input === null || typeof input !== 'object') return '';
  const obj = input as Record<string, unknown>;
  const cmd = obj.command ?? obj.cmd;
  if (Array.isArray(cmd)) return cmd.map(String).join(' ');
  return cmd == null ? '' : String(cmd);
}
