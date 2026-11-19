<script module lang="ts">
  const coverColors = [
    '#e0457b, #f2a65a',
    '#2e4a5a, #c9a27a',
    '#c4d04a, #2b3a1c',
    '#6d5aa8, #2c3f6a',
    '#5b7a52, #c88a6a',
    '#7aa0b8, #3b4a63',
  ]
</script>

<script lang="ts">
  import { appState } from '@/modules/app/store/state'
  import { settingState } from '@/modules/setting/store/state'
  import { buildUrl } from '@any-listen/web'

  let {
    src,
    artworkKey,
    alt = 'PIC',
    icon = 'night_landscape',
    width = '100%',
    height = '100%',
    loading = 'lazy',
    decoding = 'async',
    onerror,
  }: {
    src?: string | null
    artworkKey?: string | null
    icon?: string
    alt?: string
    width?: number | string
    height?: number | string
    decoding?: 'async' | 'auto' | 'sync'
    loading?: 'lazy' | 'eager'
    onerror?: () => void
  } = $props()
  let isError = $state(false)
  const fallback = $derived.by(() => {
    if (!import.meta.env.VITE_IS_DESKTOP || !artworkKey || icon !== 'night_landscape') return
    let hash = 0
    for (const char of artworkKey) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
    return `linear-gradient(135deg, ${coverColors[hash % coverColors.length]})`
  })

  let derivedSrc = $derived(
    src ? buildUrl(src, settingState.setting['network.proxyAllResources'], appState.proxyServerHost) : undefined
  )
  $effect(() => {
    if (src) isError = false
  })
</script>

{#if derivedSrc && !isError}
  <img
    src={derivedSrc}
    class="pic img"
    {alt}
    style:width
    style:height
    {loading}
    {decoding}
    onerror={() => {
      isError = true
      onerror?.()
    }}
  />
{:else}
  <div
    style:width
    style:height
    style:background-image={fallback}
    class="pic empty-pic"
    class:favorite-cover={import.meta.env.VITE_IS_DESKTOP && icon === 'music_heart'}
  >
    {#if import.meta.env.VITE_IS_DESKTOP && icon === 'music_heart'}
      <svg viewBox="0 0 24 24" width="48%" aria-hidden="true">
        <path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.5-7 10-7 10Z" fill="none" stroke="white" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    {:else if !fallback}
      <svg version="1.1" viewBox="0 0 192 192" width="78%">
        <use xlink:href={`#icon-${icon}`} />
      </svg>
    {/if}
  </div>
{/if}

<style lang="less">
  .img {
    aspect-ratio: 1;
    // width: 100%;
    // height: 100%;
    object-fit: cover;
    border-radius: @radius-border;
    box-shadow: 0 0 2px var(--color-primary-dark-200-alpha-800);
  }
  .empty-pic {
    // width: 100%;
    // height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    // letter-spacing: 3px;
    aspect-ratio: 1;
    color: var(--color-primary-light-400-alpha-400);
    user-select: none;
    background-color: var(--color-primary-light-200-alpha-900);
    // font-size: 20px;
    // font-family: Consolas, 'Courier New', monospace;
    border-radius: @radius-border;
    box-shadow: 0 0 2px var(--color-primary-dark-200-alpha-800);
  }
  .favorite-cover {
    background: linear-gradient(135deg, #ff7a8a, #8b7cff);
  }
</style>
