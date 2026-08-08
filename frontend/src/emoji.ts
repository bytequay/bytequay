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
 * GitHub-flavoured emoji shortcodes (`:shipit:`, `:+1:`, `:tada:`) used in
 * PR / issue / comment bodies. We don't ship the full ~1800-entry gemoji
 * table — instead a curated map of the everyday shortcodes plus GitHub's
 * own custom emoji (the ones with no Unicode character, served as images).
 *
 * `lookupEmoji` is the only entry point: it resolves a bare shortcode name
 * (no surrounding colons) to either a Unicode string or a GitHub image URL,
 * or null when we don't know it (the caller then leaves the `:name:` text
 * untouched, exactly as GitHub does for unknown codes).
 */

/** GitHub serves its custom (non-Unicode) emoji as PNGs from this host —
 *  same source github.com itself uses, so they match pixel-for-pixel. */
const GITHUB_EMOJI_IMAGE_BASE = 'https://github.githubassets.com/images/icons/emoji/';

/**
 * GitHub's custom emoji — these have NO Unicode code point, so they can
 * only render as images. `:shipit:` (the squirrel) is the canonical one.
 * Keyed by shortcode; the value is the asset filename (without `.png`),
 * which today always equals the shortcode but is kept explicit so a
 * rename on GitHub's side is a one-line change.
 */
const GITHUB_IMAGE_EMOJI: Record<string, string> = {
  shipit: 'shipit',
  octocat: 'octocat',
  trollface: 'trollface',
  neckbeard: 'neckbeard',
  bowtie: 'bowtie',
  suspect: 'suspect',
  godmode: 'godmode',
  goberserk: 'goberserk',
  feelsgood: 'feelsgood',
  finnadie: 'finnadie',
  hurtrealbad: 'hurtrealbad',
  rage1: 'rage1',
  rage2: 'rage2',
  rage3: 'rage3',
  rage4: 'rage4',
  basecamp: 'basecamp',
  basecampy: 'basecampy',
};

/**
 * Curated Unicode shortcodes — the everyday set you actually see in PR
 * review chatter. Not exhaustive; unknown codes pass through as literal
 * text. Aliases (thumbsup/+1) point at the same glyph on purpose.
 */
const UNICODE_EMOJI: Record<string, string> = {
  '+1': '👍',
  thumbsup: '👍',
  '-1': '👎',
  thumbsdown: '👎',
  smile: '😄',
  smiley: '😃',
  grin: '😁',
  laughing: '😆',
  joy: '😂',
  rofl: '🤣',
  wink: '😉',
  blush: '😊',
  slightly_smiling_face: '🙂',
  upside_down_face: '🙃',
  sweat_smile: '😅',
  sunglasses: '😎',
  thinking: '🤔',
  neutral_face: '😐',
  confused: '😕',
  disappointed: '😞',
  cry: '😢',
  sob: '😭',
  angry: '😠',
  rage: '😡',
  scream: '😱',
  flushed: '😳',
  partying_face: '🥳',
  heart: '❤️',
  broken_heart: '💔',
  tada: '🎉',
  confetti_ball: '🎊',
  rocket: '🚀',
  fire: '🔥',
  sparkles: '✨',
  star: '⭐',
  star2: '🌟',
  zap: '⚡',
  boom: '💥',
  '100': '💯',
  eyes: '👀',
  clap: '👏',
  raised_hands: '🙌',
  pray: '🙏',
  muscle: '💪',
  ok_hand: '👌',
  wave: '👋',
  point_up: '☝️',
  point_down: '👇',
  point_right: '👉',
  point_left: '👈',
  v: '✌️',
  white_check_mark: '✅',
  heavy_check_mark: '✔️',
  ballot_box_with_check: '☑️',
  x: '❌',
  warning: '⚠️',
  no_entry: '⛔',
  bangbang: '‼️',
  question: '❓',
  exclamation: '❗',
  bulb: '💡',
  memo: '📝',
  pencil: '📝',
  bug: '🐛',
  hammer: '🔨',
  wrench: '🔧',
  gear: '⚙️',
  lock: '🔒',
  key: '🔑',
  package: '📦',
  books: '📚',
  bookmark: '🔖',
  art: '🎨',
  recycle: '♻️',
  construction: '🚧',
  ambulance: '🚑',
  rotating_light: '🚨',
  hourglass: '⌛',
  clock: '🕐',
  checkered_flag: '🏁',
  trophy: '🏆',
  dart: '🎯',
  coffee: '☕',
  beer: '🍺',
  beers: '🍻',
  pizza: '🍕',
  poop: '💩',
  hankey: '💩',
  ghost: '👻',
  alien: '👽',
  robot: '🤖',
  skull: '💀',
  see_no_evil: '🙈',
  hear_no_evil: '🙉',
  speak_no_evil: '🙊',
  dog: '🐶',
  cat: '🐱',
  squirrel: '🐿️',
  snake: '🐍',
  snail: '🐌',
  turtle: '🐢',
  unicorn: '🦄',
  arrow_up: '⬆️',
  arrow_down: '⬇️',
  arrow_right: '➡️',
  arrow_left: '⬅️',
  heavy_plus_sign: '➕',
  heavy_minus_sign: '➖',
};

export type EmojiResolution =
  | { kind: 'unicode'; value: string }
  | { kind: 'image'; src: string };

/**
 * Resolve a bare shortcode name (no colons) to a Unicode string or a
 * GitHub image URL. Returns null for codes we don't carry, so the caller
 * can leave the original `:name:` text in place.
 */
export function lookupEmoji(name: string): EmojiResolution | null {
  const key = name.toLowerCase();
  const unicode = UNICODE_EMOJI[key];
  if (unicode !== undefined) return { kind: 'unicode', value: unicode };
  const image = GITHUB_IMAGE_EMOJI[key];
  if (image !== undefined) return { kind: 'image', src: `${GITHUB_EMOJI_IMAGE_BASE}${image}.png` };
  return null;
}
