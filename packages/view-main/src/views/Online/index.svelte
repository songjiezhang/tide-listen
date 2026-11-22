<script lang="ts">
  import Header from './Header.svelte'
  import { location, query } from '@/plugins/routes'
  import { availableOnlineViews, resolveOnlineView, urlParamKeyMap } from './shared.svelte'
  import Search from './Search/Search.svelte'
  import Songlist from './Songlist/Songlist.svelte'
  import TopSongs from './TopSongs/TopSongs.svelte'
  import Album from './Album/Album.svelte'
  import Singer from './Singer/Singer.svelte'
  import Empty from '@/components/material/Empty.svelte'
  import { setLastHistory } from '@/modules/resource/actions'

  const activeView = $derived(resolveOnlineView($query[urlParamKeyMap.type], $availableOnlineViews))

  $effect(() => {
    setLastHistory($location, { ...$query })
  })
</script>

<div class="view-container container">
  {#if activeView}
    <Header activeview={activeView} />
    {#if activeView == 'search'}
      <Search />
    {:else if activeView == 'songlist'}
      <Songlist />
    {:else if activeView == 'topSongs'}
      <TopSongs />
    {:else if activeView == 'album'}
      <Album />
    {:else if activeView == 'singer'}
      <Singer />
    {/if}
  {:else}
    <Empty />
  {/if}
</div>

<style lang="less">
  .container {
    // padding: 10px 15px;
    display: flex;
    flex-flow: column nowrap;
    > :global(div) {
      min-height: 0;
    }
  }
</style>
