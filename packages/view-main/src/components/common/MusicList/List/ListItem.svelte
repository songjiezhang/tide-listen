<script lang="ts">
  import Badge from '@/components/base/Badge.svelte'
  import Image from '@/components/base/Image.svelte'

  // import SvgIcon from '@/components/base/SvgIcon.svelte'
  import type { MouseEventHandler } from 'svelte/elements'
  import { fade } from 'svelte/transition'
  import { buildSourceLabel } from '@any-listen/common/tools'
  import { onMount, tick } from 'svelte'
  import { getMusicPicDelay } from '@/modules/player/store/actions'
  import Btn from '@/components/base/Btn.svelte'
  import SvgIcon from '@/components/base/SvgIcon.svelte'
  import { t } from '@/plugins/i18n'
  import { useSettingValue } from '@/modules/setting/reactive.svelte'
  import MusicHeartBtn from '@/components/common/MusicHeartBtn.svelte'
  import { LIST_IDS } from '@any-listen/common/constants'
  // console.log(querystring)
  let {
    musicinfo,
    listid,
    index,
    source,
    picStyle,
    selected,
    selectedactive,
    playing,
    active,
    reorderable = false,
    oncontextmenu,
    onclick,
    onplay,
  }: {
    musicinfo: AnyListen.Music.MusicInfo
    listid: string
    source: AnyListen.Player.SourceType
    index: number
    picStyle: string
    playing?: boolean
    selected?: boolean
    active?: boolean
    selectedactive?: boolean
    reorderable?: boolean
    oncontextmenu?: MouseEventHandler<HTMLDivElement>
    onclick: (isKey: boolean) => void
    onplay?: () => void
  } = $props()

  let sourceLabel = $derived(buildSourceLabel(musicinfo))
  let picUrl = $state<null | string>(null)
  let hovered = $state(false)
  let isShowActionBtn = useSettingValue('list.isShowActionBtn')
  // let isPlaying = $derived(isplaylist && $playInfo.index === index)
  const badgeTypes = ['primary', 'secondary', 'tertiary'] as const

  const handleClick = (event: KeyboardEvent | Event) => {
    if ('key' in event) {
      if (event.repeat || event.key != 'Enter') return
      onclick(true)
    } else {
      onclick(false)
    }
  }

  let cancelLoadPic: (() => void) | undefined = undefined
  let retryedLoadPic = false
  const loadPic = () => {
    cancelLoadPic?.()
    cancelLoadPic = getMusicPicDelay({ musicInfo: musicinfo, listId: listid, source, isRefresh: retryedLoadPic }, (url) => {
      cancelLoadPic = undefined
      void tick().then(() => {
        picUrl = url
      })
    })
  }

  onMount(() => {
    retryedLoadPic = false
    loadPic()
    return () => {
      cancelLoadPic?.()
    }
  })
</script>

<div
  class="container"
  class:selected
  class:active
  class:playing
  class:selectedactive
  role="button"
  tabindex="0"
  onkeydown={handleClick}
  onclick={handleClick}
  {oncontextmenu}
  onmouseenter={() => (hovered = true)}
  onmouseleave={() => (hovered = false)}
>
  {#if import.meta.env.VITE_IS_DESKTOP}
    <span class="track-number" data-reorder-track={reorderable ? musicinfo.id : undefined}
      title={reorderable ? $t('user_list_music_menu__sort') : undefined} aria-hidden="true">
      {#if playing}
        <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor">
          <rect x="2" y="6" width="3" height="8" rx="1.5" />
          <rect x="7" y="2" width="3" height="12" rx="1.5" />
          <rect x="12" y="4" width="3" height="10" rx="1.5" />
        </svg>
      {:else}
        {index + 1}
      {/if}
    </span>
  {/if}
  <div class="pic" style={picStyle}>
    {#if playing && !import.meta.env.VITE_IS_DESKTOP}
      <div class="play-icon" transition:fade={{ delay: 200 }}>
        <svg version="1.1" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
          <use xlink:href="#icon-play" />
        </svg>
      </div>
    {/if}
    <Image
      src={picUrl}
      artworkKey={musicinfo.id}
      onerror={() => {
        picUrl = null
        if (retryedLoadPic) return
        retryedLoadPic = true
        loadPic()
      }}
    />
    {#if hovered && !import.meta.env.VITE_IS_DESKTOP}
      <div class="num" transition:fade={{ duration: 200 }}>
        {index + 1}
      </div>
    {/if}
  </div>
  <div class="list-item-cell auto name-cell">
    <div class="name-left">
      <div class="select name" aria-label={musicinfo.name}>{musicinfo.name}</div>
      <div class="label">
        {#each sourceLabel as label, index (index)}
          <Badge {label} opacity={import.meta.env.VITE_IS_DESKTOP ? 1 : 0.7} type={badgeTypes[index % badgeTypes.length]} />
        {/each}
      </div>
    </div>
    {#if isShowActionBtn.val}
      <div class="name-right">
        {#if hovered}
          <div class="auto-hide-btns" transition:fade={{ duration: 200 }}>
            <Btn
              onclick={(evt) => {
                evt.stopPropagation()
                onplay?.()
              }}
              min
              icon
              outline
              aria-label={$t('player__play')}
              class={['play-btn']}
            >
              <SvgIcon name="play" />
            </Btn>
          </div>
        {/if}
        {#if listid !== LIST_IDS.LOVE}
          <MusicHeartBtn {musicinfo} min />
        {/if}
      </div>
    {/if}
  </div>
  <div class="list-item-cell" style="flex: 0 0 22%;">
    <span class="select" aria-label={musicinfo.singer}>{musicinfo.singer}</span>
  </div>
  <div class="list-item-cell" style="flex: 0 0 22%;">
    <span class="select" aria-label={musicinfo.meta.albumName}>{musicinfo.meta.albumName}</span>
  </div>
  <div class="list-item-cell" style="flex: 0 0 9%;">
    <span class="no-select">{musicinfo.interval || '--/--'}</span>
  </div>
</div>

<style lang="less">
  [data-reorder-track] {
    cursor: grab;
    touch-action: none;
    user-select: none;
  }
  .container {
    position: relative;
    display: flex;
    flex-flow: row nowrap;
    gap: 10px;
    align-items: center;
    height: 100%;
    padding: 5px;
    font-size: 13px;
    background-color: transparent;
    border: 1px dashed transparent;
    border-radius: @radius-border;
    transition: 0.3s ease;
    transition-property: color, background-color, opacity, border-color;
    // &:hover {
    //   .num {
    //     opacity: 1;
    //   }
    // }

    &:not(.active, .selected) {
      &:hover {
        background-color: var(--color-primary-background-hover);
      }
    }
    &.selected {
      background-color: var(--color-primary-background-selected);
    }
    &.active {
      background-color: var(--color-primary-background-active);
    }
    &.selectedactive {
      border-color: var(--color-primary-alpha-700);
    }
    &.playing {
      box-shadow: inset 3px 0 0 var(--color-primary);
      background-color: color-mix(in srgb, var(--color-primary) 14%, transparent);

      .name {
        font-weight: 600;
        color: var(--color-primary);
      }

      .list-item-cell {
        color: color-mix(in srgb, var(--color-primary) 72%, var(--color-font));
      }

      &:hover {
        background-color: color-mix(in srgb, var(--color-primary) 20%, transparent);
      }
      &.selected,
      &.active {
        background-color: color-mix(in srgb, var(--color-primary) 24%, transparent);
      }
    }
  }
  // .active {
  //   background-color: var(--color-primary-background);
  // }

  .pic {
    position: relative;
    flex: none;
    overflow: hidden;
    // background-color: var(--color-primary-light-200-alpha-900);
    // display: flex;
    // align-items: center;
    // justify-content: center;
    border-radius: @radius-border;
    // user-select: none;
    // flex: none;
    // > span {
    //   // width: 100%;
    //   // height: 80%;
    //   color: var(--color-primary-light-400-alpha-200);
    //   font-size: 18px;
    //   font-family: Consolas, 'Courier New', monospace;
    //   span {
    //     padding-left: 2px;
    //   }
    // }
    :global(.pic) {
      transition: opacity @transition-normal;
    }
  }
  // .num {
  //   position: absolute;
  //   bottom: 0;
  //   right: 0;
  //   .nobreak;
  //   .center;
  //   opacity: 0;
  //   transition: opacity .2s ease;
  //   padding-left: 2px;
  //   padding-right: 2px;
  //   font-size: 11px;
  //   line-height: 1.2;
  //   color: var(--color-button-font);
  //   background-color: var(--color-button-background);
  //   border-top-left-radius: @radius-border;
  //   border-bottom-right-radius: @radius-border;
  // }
  .play-icon {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    padding: 6px;
    color: var(--color-button-font);

    + :global(.pic) {
      opacity: 0.1;
    }
  }

  .num {
    position: absolute;
    right: 0;
    bottom: 0;
    max-width: 100%;
    padding: 0 2px;
    font-size: 11px;
    line-height: 1.2;
    color: var(--color-000);
    user-select: none;
    background-color: var(--color-primary-dark-800-alpha-200);
    border-top-left-radius: @radius-border;
  }

  // .right {
  //   flex: auto;
  //   display: flex;
  //   flex-flow: row nowrap;
  //   font-size: 14px;
  //   gap: 2px;
  //   min-width: 0;
  //   span {
  //     .mixin-ellipsis-1();
  //   }
  // }

  .list-item-cell {
    // padding: 0 6px;
    position: relative;
    flex: none;
    // transition:  0.3s cubic-bezier(0.4, 0, 0.2, 1);
    line-height: 16px;
    vertical-align: middle;
    .mixin-ellipsis-1();

    &.auto {
      flex: auto;
    }

    // .badge {
    //   margin-left: 3px;
    //   opacity: 0.85;
    // }

    // &.meta {
    //   font-size: 12px;
    //   color: var(--color-font-label);
    // }
  }
  .name-cell {
    display: flex;
    flex-flow: row nowrap;
    align-items: center;
    justify-content: space-between;
  }
  .name-left {
    display: flex;
    flex: auto;
    flex-flow: column nowrap;
    align-items: flex-start;
    // gap: 4px;
    justify-content: center;
    min-width: 0;
    overflow: hidden;
    text-overflow: initial;
    white-space: initial;

    > .name {
      .mixin-ellipsis-1();

      max-width: 100%;
      padding: 2px 0;
    }

    .label {
      display: flex;
      flex-flow: row nowrap;
      gap: 8px;
      padding: 2px 0;
      :global(.badge) {
        padding: 0;
      }
    }
  }
  .name-right {
    display: flex;
    flex: none;
    flex-flow: row nowrap;
    gap: 4px;
    align-items: center;
    margin: 0 8px;
    color: var(--color-font-label);

    :global {
      .play-btn {
        padding: 3px;
      }
    }
  }
  .auto-hide-btns {
    display: flex;
    flex: none;
    flex-flow: row nowrap;
    gap: 4px;
    align-items: center;
  }
  :global(.desktop) {
    .container {
      display: grid;
      grid-template-columns: var(--track-columns);
      column-gap: 8px;
      padding: 3px 11px;
      border-radius: 0.75rem;
    }
    .pic,
    .pic :global(.pic) {
      border-radius: 0.5rem;
    }
    .track-number {
      display: grid;
      place-items: center;
      font-size: 12px;
      font-variant-numeric: tabular-nums;
      color: var(--color-font-label);
    }
    .list-item-cell {
      min-width: 0;
      color: var(--color-font-label);
    }
    .name {
      font-size: 14px;
      font-weight: 500;
      color: var(--color-font);
    }
    .label {
      gap: 4px;
      padding: 0;
      :global(.badge) {
        padding: 1px 5px;
        font-size: 10px;
        line-height: 1.25;
        background: color-mix(in srgb, currentcolor 12%, transparent);
        border-radius: 4px;
      }
    }
    .playing {
      .track-number {
        color: var(--color-primary);
      }
      .name {
        font-weight: 600;
        color: var(--color-primary);
      }
      .list-item-cell {
        color: color-mix(in srgb, var(--color-primary) 72%, var(--color-font));
      }
    }
    .list-item-cell:last-child {
      font-variant-numeric: tabular-nums;
      text-align: right;
    }
  }
</style>
