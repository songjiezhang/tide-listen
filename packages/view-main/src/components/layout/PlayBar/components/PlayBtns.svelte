<script lang="ts">
  import { skipNext, skipPrev, togglePlay } from '@/modules/player/actions'
  import { playing } from '@/modules/player/reactive.svelte'
  import { t } from '@/plugins/i18n'
  import { onDomSizeChanged } from '@any-listen/web'
  import { onMount } from 'svelte'
  let domContainer: HTMLDivElement | null = $state(null)
  let iconSize = $state('42px')
  let iconSize2 = $state('46px')

  onMount(() => {
    if (!domContainer) return
    return onDomSizeChanged(domContainer, (width, height) => {
      iconSize = `${Math.trunc(height * 0.52)}px`
      iconSize2 = `${Math.trunc(height * 0.65)}px`
    })
  })
</script>

<div class="container" bind:this={domContainer}>
  <button class="btn" aria-label={$t('player__prev')} onclick={async () => skipPrev()}>
    <svg version="1.1" xmlns="http://www.w3.org/2000/svg" width={iconSize} height={iconSize} viewBox="0 0 24 24">
      <use xlink:href="#icon-skip-prev" />
    </svg>
  </button>
  <button class="btn play-toggle" aria-label={$playing ? $t('player__pause') : $t('player__play')} onclick={togglePlay}>
    {#if $playing}
      <svg version="1.1" xmlns="http://www.w3.org/2000/svg" width={iconSize2} height={iconSize2} viewBox="0 0 24 24">
        <use xlink:href="#icon-pause" />
      </svg>
    {:else}
      <svg version="1.1" xmlns="http://www.w3.org/2000/svg" width={iconSize2} height={iconSize2} viewBox="0 0 24 24">
        <use xlink:href="#icon-play" />
      </svg>
    {/if}
  </button>
  <button class="btn" aria-label={$t('player__next')} onclick={async () => skipNext()}>
    <svg version="1.1" xmlns="http://www.w3.org/2000/svg" width={iconSize} height={iconSize} viewBox="0 0 24 24">
      <use xlink:href="#icon-skip-next" />
    </svg>
  </button>
</div>

<style lang="less">
  .container {
    display: flex;
    flex: none;
    flex-flow: row nowrap;
    gap: 14px;
    align-items: center;
    height: 100%;
    padding-right: 10px;
    padding-left: 10px;
  }
  .btn {
    display: flex;
    flex: none;
    // padding: 5px;
    padding: 0;
    color: var(--color-button-font);
    cursor: pointer;
    background-color: transparent;
    border: none;
    opacity: 1;
    // height: 52%;
    // margin-top: -2px;
    transition: @transition-fast;
    transition-property: color, opacity;

    // svg {
    //   filter: drop-shadow(0 0 1px rgba(0, 0, 0, 0.2));
    // }
    &:hover {
      opacity: 0.8;
    }
    &:active {
      opacity: 0.6;
    }
  }
  :global(.desktop) .container {
    gap: var(--transport-gap, 6px);

    .btn {
      align-items: center;
      justify-content: center;
      height: var(--transport-skip-size, 52%);
      aspect-ratio: 1;
      color: var(--color-850);
      border-radius: 50%;

      svg {
        width: 72%;
        height: 72%;
      }
      &:hover {
        background-color: var(--color-primary-background-hover);
      }
      &:focus-visible {
        outline: 2px solid var(--color-primary-dark-200);
        outline-offset: 2px;
      }
    }
    .play-toggle {
      height: var(--transport-main-size, 64%);
      color: white;
      background: var(--action-fill);

      svg {
        width: 55%;
        height: 55%;
      }

      &:hover {
        filter: brightness(1.12);
      }
    }
  }
</style>
