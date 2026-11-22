<script lang="ts">
  import Tab from '@/components/base/Tab.svelte'
  import { t } from '@/plugins/i18n'
  import { availableOnlineViews, tabIcons, urlParamKeyMap, type ViewType } from './shared.svelte'
  import { pushRoute } from '@/modules/resource/actions'

  let { activeview }: { activeview: ViewType } = $props()
  const typeList = $derived($availableOnlineViews.map((id) => ({ id, icon: tabIcons[id], label: $t(`online__type_${id}`) })))
</script>

<header class="header">
  {#if import.meta.env.VITE_IS_DESKTOP}
    <h1>{$t(`online__type_${activeview}`)}</h1>
  {:else}
    <Tab
      list={typeList}
      itemkey="id"
      itemlabel="label"
      itemicon="icon"
      value={activeview}
      min
      onchange={(item) => {
        pushRoute('/online', { [urlParamKeyMap.type]: item.id })
      }}
    />
  {/if}
  <div id="online-header-right"></div>
</header>

<style lang="less">
  .header {
    display: flex;
    flex: none;
    flex-flow: row nowrap;
    align-items: center;
    justify-content: space-between;
    padding: 0 15px;
    h1 {
      margin: 0;
      font-size: 20px;
      font-weight: 600;
    }
  }
</style>
