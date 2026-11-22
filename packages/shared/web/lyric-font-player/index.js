import FontPlayer from './font-player'
import LinePlayer from './line-player'

const fontTimeExp = /<(\d+),(\d+)>/g

export default class Lyric {
  /** @param {import('./types').LyricOptions} options */
  constructor({
    lyric = '',
    extendedLyrics = [],
    offset = 0,
    rate = 1,
    lineContentClassName = 'line-content',
    lineClassName = 'line',
    shadowClassName = 'shadow',
    fontModeClassName = 'font-mode',
    lineModeClassName = 'line-mode',
    fontLrcClassName = 'font-lrc',
    extendedLrcClassName = 'extended',
    activeLineClassName = 'active',
    shadowContent = false,
    mergeExtendedLyrics = false,
    showExtendedLyrics = true,
    isVertical = false,
    onPlay = function (line, text) {},
    onSetLyric = function (lines, offset) {},
    onUpdateLyric = function (lines) {},
  }) {
    this.lyric = lyric
    this.extendedLyrics = extendedLyrics
    this.offset = offset
    this.rate = rate
    this.onPlay = onPlay
    this.onSetLyric = onSetLyric
    this.onUpdateLyric = onUpdateLyric

    this.lineContentClassName = lineContentClassName
    this.lineClassName = lineClassName
    this.shadowClassName = shadowClassName
    this.fontModeClassName = fontModeClassName
    this.lineModeClassName = lineModeClassName
    this.fontLrcClassName = fontLrcClassName
    this.extendedLrcClassName = extendedLrcClassName
    this.activeLineClassName = activeLineClassName
    this.shadowContent = shadowContent
    this.mergeExtendedLyrics = mergeExtendedLyrics
    this.showExtendedLyrics = showExtendedLyrics

    this.isVertical = isVertical
    this.playingLineNum = -1
    this.isLineMode = false

    /** @type {FontPlayer[]} */
    this._lineFonts = []
    /** @type {import('./types').RenderedLine[]} */
    this._lines = []
    /** @type {{ lines: import('./types').LyricLine[], offset: number }} */
    this.initInfo = {
      lines: [],
      offset: 0,
    }

    this.linePlayer = new LinePlayer({
      offset: this.offset,
      rate: this.rate,
      onPlay: this._handleLinePlayerOnPlay,
      onSetLyric: this._handleLinePlayerOnSetLyric,
    })
  }

  _init() {
    this.playingLineNum = -1
    this.isLineMode = false

    this.linePlayer.setLyric(this.lyric, this.extendedLyrics)
  }

  /**
   * @param {number} num
   * @param {string} text
   * @param {number} curTime
   */
  _handleLinePlayerOnPlay = (num, text, curTime) => {
    if (this.isLineMode) {
      if (num < this.playingLineNum + 1) {
        for (let i = this.playingLineNum, minNum = Math.max(num, 0) - 1; i > minNum; i--) {
          const font = this._lineFonts[i]
          font.reset()
          font.lineContent.classList.remove(this.activeLineClassName)
        }
      } else if (num > this.playingLineNum) {
        for (let i = Math.max(this.playingLineNum, 0); i < num; i++) {
          const font = this._lineFonts[i]
          font.reset()
          font.lineContent.classList.remove(this.activeLineClassName)
        }
      } else if (this.playingLineNum > -1) {
        const font = this._lineFonts[this.playingLineNum]
        font.reset()
        font.lineContent.classList.remove(this.activeLineClassName)
      }
    } else {
      if (num < this.playingLineNum + 1) {
        for (let i = this.playingLineNum, minNum = Math.max(num, 0) - 1; i > minNum; i--) {
          const font = this._lineFonts[i]
          font.lineContent.classList.remove(this.activeLineClassName)
          font.reset()
        }
      } else if (num > this.playingLineNum) {
        for (let i = Math.max(this.playingLineNum, 0); i < num; i++) {
          const font = this._lineFonts[i]
          font.lineContent.classList.remove(this.activeLineClassName)
          font.finish()
        }
      } else if (this.playingLineNum > -1) {
        const font = this._lineFonts[this.playingLineNum]
        font.lineContent.classList.remove(this.activeLineClassName)
      }
    }
    this.playingLineNum = num
    if (num > -1) {
      const font = this._lineFonts[num]
      font.lineContent.classList.add(this.activeLineClassName)
      font.play(curTime - this._lines[num].time)
    }
    this.onPlay(num, this._lines[num]?.text ?? '')
  }

  /**
   * @param {import('./types').LyricLine[]} lyricLines
   * @param {number} offset
   * @param {boolean} isUpdate
   */
  _initLines = (lyricLines, offset, isUpdate) => {
    // console.log(lyricLines)
    // this._lines = lyricsLines
    this.isLineMode = lyricLines.length > 0 && !/^<\d+,\d+>/.test(lyricLines[0].text)
    this.playingLineNum = -1

    this._lineFonts = []
    this._lines = lyricLines.map((line) => {
      const fontPlayer = new FontPlayer({
        time: line.time,
        rate: this.rate,
        lyric: line.text,
        extendedLyrics: line.extendedLyrics,
        lineContentClassName: this.lineContentClassName,
        lineClassName: this.lineClassName,
        shadowClassName: this.shadowClassName,
        fontModeClassName: this.fontModeClassName,
        lineModeClassName: this.lineModeClassName,
        fontLrcClassName: this.fontLrcClassName,
        extendedLrcClassName: this.extendedLrcClassName,
        shadowContent: this.shadowContent,
        mergeExtendedLyrics: this.mergeExtendedLyrics,
        showExtendedLyrics: this.showExtendedLyrics,
        isVertical: this.isVertical,
      })

      this._lineFonts.push(fontPlayer)
      return {
        text: this.isLineMode ? line.text : line.text.replace(fontTimeExp, ''),
        time: line.time,
        extendedLyrics: line.extendedLyrics,
        dom_line: fontPlayer.lineContent,
      }
    })

    // 如果是逐行歌词，则添加 60ms 的偏移
    this.linePlayer.setGlobalOffset(this.isLineMode ? 60 : 0)
    if (isUpdate) this.onUpdateLyric(this._lines)
    else this.onSetLyric(this._lines, offset)
  }

  /**
   * @param {import('./types').LyricLine[]} lyricLines
   * @param {number} offset
   */
  _handleLinePlayerOnSetLyric = (lyricLines, offset) => {
    this._initLines(lyricLines, offset, false)
    this.initInfo.lines = lyricLines
    this.initInfo.offset = offset
  }

  /** @param {number} curTime */
  setTime(curTime) {
    if (!this.linePlayer) return
    this.linePlayer.setTime(curTime)
  }

  /** @param {number} curTime */
  play(curTime) {
    if (!this.linePlayer) return
    this.linePlayer.play(curTime)
  }

  pause() {
    if (!this.linePlayer) return
    this.linePlayer.pause()
    if (this.playingLineNum > -1) this._lineFonts[this.playingLineNum]?.pause()
  }

  /** @param {number} offset */
  setOffset(offset) {
    this.linePlayer.setOffset(offset)
  }

  /**
   * @param {string} lyric
   * @param {string[]} [extendedLyrics]
   */
  setLyric(lyric, extendedLyrics = []) {
    this.lyric = lyric
    this.extendedLyrics = extendedLyrics
    this._init()
  }

  /** @param {number} rate */
  setPlaybackRate(rate) {
    this.rate = rate
    this.linePlayer.setPlaybackRate(rate)
    this._initLines(this.initInfo.lines, this.initInfo.offset, true)
  }

  /** @param {boolean} isVertical */
  setVertical(isVertical) {
    if (this.isVertical === isVertical) return
    this.isVertical = isVertical
    this._initLines(this.initInfo.lines, this.initInfo.offset, true)
  }

  /** @param {boolean} merge */
  setMergeExtendedLyrics(merge) {
    if (this.mergeExtendedLyrics === merge) return
    this.mergeExtendedLyrics = merge
    this._initLines(this.initInfo.lines, this.initInfo.offset, true)
  }

  /** @param {boolean} show */
  setShowExtendedLyrics(show) {
    if (this.showExtendedLyrics === show) return
    this.showExtendedLyrics = show
    this._initLines(this.initInfo.lines, this.initInfo.offset, true)
  }

  /** @param {import('./types').TimerFunctions} [tools] */
  setTimeoutTools(tools) {
    this.linePlayer.setTimeoutTools(tools)
  }

  /** @param {import('./types').DisplayOptions} options */
  setOptions(options) {
    let updated = false
    if (options.isVertical !== undefined && this.isVertical !== options.isVertical) {
      this.isVertical = options.isVertical
      updated ||= true
    }
    if (options.mergeExtendedLyrics !== undefined && this.mergeExtendedLyrics !== options.mergeExtendedLyrics) {
      this.mergeExtendedLyrics = options.mergeExtendedLyrics
      updated ||= true
    }
    if (options.showExtendedLyrics !== undefined && this.showExtendedLyrics !== options.showExtendedLyrics) {
      this.showExtendedLyrics = options.showExtendedLyrics
      updated ||= true
    }
    if (updated) {
      this._initLines(this.initInfo.lines, this.initInfo.offset, true)
    }
  }
}
