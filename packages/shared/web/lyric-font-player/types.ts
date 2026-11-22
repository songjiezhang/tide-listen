export interface LyricLine {
  time: number
  text: string
  extendedLyrics: string[]
}

export interface LinePlayerOptions {
  offset?: number
  globalOffset?: number
  rate?: number
  onPlay?: (index: number, text: string, time: number) => void
  onSetLyric?: (lines: LyricLine[], offset: number) => void
}

export interface RenderedLine extends LyricLine {
  dom_line: HTMLDivElement
}

export interface DisplayOptions {
  mergeExtendedLyrics?: boolean
  showExtendedLyrics?: boolean
  isVertical?: boolean
}

export interface FontOptions extends DisplayOptions {
  lyric?: string
  extendedLyrics?: string[]
  rate?: number
  lineContentClassName?: string
  lineClassName?: string
  shadowClassName?: string
  fontModeClassName?: string
  lineModeClassName?: string
  fontLrcClassName?: string
  extendedLrcClassName?: string
  shadowContent?: boolean
}

export interface LyricOptions extends FontOptions {
  offset?: number
  activeLineClassName?: string
  onPlay?: (line: number, text: string) => void
  onSetLyric?: (lines: RenderedLine[], offset: number) => void
  onUpdateLyric?: (lines: RenderedLine[]) => void
}

export interface TimerFunctions {
  setTimeout: (callback: () => void, delay: number) => number
  clearTimeout: (id: number) => void
  nextTick: (callback: () => void) => number
  cancelNextTick: (id: number) => void
}
