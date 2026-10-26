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

/** The first support check that fails on this Mac, in the order the Voice setting shows them; `ready` when none does. */
export type VoiceState =
  | 'macos-too-old'
  | 'unavailable'
  | 'language-unsupported'
  | 'no-microphone'
  | 'microphone-denied'
  | 'needs-model'
  | 'needs-translation'
  | 'downloading'
  | 'ready';

/** `macos` is this Mac's version; `language` names the spoken language when the helper could tell. */
export type VoiceSupport = { state: VoiceState; macos: string; language?: string };

/** `spoken` null follows the Mac's language; `written` null keeps the spoken one, so nothing is translated. */
export type VoiceSettings = { enabled: boolean; spoken: string | null; written: string | null };

export type VoiceLanguage = { id: string; name: string };

/** What one can speak, and what the given spoken language can be written in besides itself. */
export type VoiceLanguages = { spoken: VoiceLanguage[]; written: VoiceLanguage[] };

/** Turning the setting on returns the check it ran, so the card can show why it stayed off. */
export type VoiceToggle = VoiceSettings & { support: VoiceSupport };

/** Why a dictation did not start; a support state when something changed since the setting was turned on. */
export type DictationRefusal = 'off' | 'busy' | VoiceState;

export type DictationStart = { id: string } | { refused: DictationRefusal };

/**
 * One dictation's progress, sent only to the window that started it. A `partial` replaces the previous one; a `final`
 * appends; `done` carries all finished text, empty when no speech was heard. `failed` keeps the recording for Retry.
 */
export type DictationEvent =
  | { id: string; type: 'listening' | 'finishing' | 'cancelled' }
  | { id: string; type: 'partial' | 'done'; text: string }
  /** `original` is the spoken words when `text` is their translation. */
  | { id: string; type: 'final'; text: string; original?: string }
  | { id: string; type: 'failed'; code: string; seconds: number };

export type VoiceBridge = {
  getVoiceSettings: () => Promise<VoiceSettings>;
  checkVoice: () => Promise<VoiceSupport>;
  /** Turning it on runs the check, asks for the microphone once, and reserves the language; it stays off unless all pass. */
  setVoiceEnabled: (enabled: boolean) => Promise<VoiceToggle>;
  /** Downloads the speech model for the spoken language; progress arrives through `onVoiceInstallProgress`. */
  installVoiceModel: () => Promise<void>;
  openMicrophonePrivacy: () => Promise<void>;
  getVoiceLanguages: (spoken: string | null) => Promise<VoiceLanguages>;
  /** Saves both languages and checks again; a written language the spoken one cannot be translated into is dropped. */
  setVoiceLanguages: (languages: { spoken: string | null; written: string | null }) => Promise<VoiceToggle>;
  /** Opens a small window where Apple asks to download the translation pair; resolves once it is installed. */
  downloadVoiceTranslation: () => Promise<void>;
  onVoiceChanged: (listener: (settings: VoiceSettings) => void) => () => void;
  onVoiceInstallProgress: (listener: (progress: { fraction: number }) => void) => () => void;
  startDictation: () => Promise<DictationStart>;
  /** Stops recording; the finished text follows as `done`. */
  stopDictation: (id: string) => Promise<void>;
  /** Stops recording and deletes it; nothing is inserted. */
  cancelDictation: (id: string) => Promise<void>;
  /** Transcribes a failed dictation's kept recording again. */
  retryDictation: (id: string) => Promise<DictationStart>;
  discardDictation: (id: string) => Promise<void>;
  onDictationEvent: (listener: (event: DictationEvent) => void) => () => void;
};
