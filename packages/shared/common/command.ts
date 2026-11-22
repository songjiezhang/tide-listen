export const LX_SOURCE_LOADER = 'lx-api-source-loader'
export const LX_SOURCE_MAX_ENABLED = 5
export const LX_SOURCE_COMMANDS = {
  add: `${LX_SOURCE_LOADER}:addRemoteSource`,
  bind: 'desktop.lxSource.bind',
  test: 'desktop.lxSource.test',
  update: 'desktop.lxSource.update',
  up: 'desktop.lxSource.up',
  down: 'desktop.lxSource.down',
} as const

export const isLxSourceCommand = (command: string): command is (typeof LX_SOURCE_COMMANDS)[keyof typeof LX_SOURCE_COMMANDS] =>
  Object.values(LX_SOURCE_COMMANDS).some((value) => value === command)

const VIEW_MAIN_DESKTOP_COMMANDS = [] as const
const VIEW_MAIN_WEB_COMMANDS = ['logout', 'maximizeToggle'] as const
const VIEW_MAIN_COMMON_COMMANDS = [
  'play',
  'pause',
  'playToggle',
  'next',
  'previous',
  'favorite',
  'unfavorite',
  'dislike',
  'muteToggle',
  'volumeUp',
  'volumeDown',
  'seekForward',
  'seekBackward',
  'showMusicComment',
  'focusSearchInput',
  'run',
] as const

const MAIN_DESKTOP_COMMANDS = [
  'minimize',
  'fullscreenToggle',
  'close',
  'exit',
  'hideToggle',
  'desktopLyric.showToggle',
  'desktopLyric.alwaysOnTopToggle',
  'desktopLyric.lockToggle',
] as const
const MAIN_WEB_COMMANDS = [] as const

export const VIEW_MAIN_HIDDEN_COMMANDS = [] as const

export const MAIN_HIDDEN_COMMANDS = ['hide', 'show'] as const

export const VIEW_MAIN_COMMANDS = [
  ...(import.meta.env.VITE_IS_WEB ? VIEW_MAIN_WEB_COMMANDS : VIEW_MAIN_DESKTOP_COMMANDS),
  ...VIEW_MAIN_COMMON_COMMANDS,
] as Array<(typeof VIEW_MAIN_COMMON_COMMANDS)[number] | (typeof VIEW_MAIN_WEB_COMMANDS)[number]>
export const MAIN_COMMANDS = [...(import.meta.env.VITE_IS_DESKTOP ? MAIN_DESKTOP_COMMANDS : MAIN_WEB_COMMANDS)] as Array<
  (typeof MAIN_DESKTOP_COMMANDS)[number]
>

export const VIEW_MAIN_ALL_COMMANDS = [...VIEW_MAIN_COMMANDS, ...VIEW_MAIN_HIDDEN_COMMANDS]

export const MAIN_ALL_COMMANDS = [...MAIN_COMMANDS, ...MAIN_HIDDEN_COMMANDS]
