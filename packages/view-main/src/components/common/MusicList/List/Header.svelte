<script lang="ts">
  import Checkbox from '@/components/base/Checkbox.svelte'
  import { t } from '@/plugins/i18n'
  let {
    multimode,
    picwidth,
    selectall,
    onselectall,
    disabledselect,
  }: {
    multimode: boolean
    selectall: boolean
    picwidth: number
    disabledselect?: boolean
    onselectall: (all: boolean) => void
  } = $props()
  // console.log(params)
</script>

<div class="header">
  <div class="num" style:width={import.meta.env.VITE_IS_DESKTOP ? undefined : `${picwidth}px`}>
    {#if multimode}
      <Checkbox
        arialabel={$t('select_all')}
        checked={selectall}
        id="music_list_select_all_checkbox"
        disabled={disabledselect}
        onchange={() => {
          onselectall(!selectall)
        }}
      />
    {:else}
      #
    {/if}
  </div>
  <span class="song">{$t('music_name')}</span>
  <span>{$t('music_singer')}</span>
  <span>{$t('music_album')}</span>
  <span>{$t('music_time')}</span>
</div>

<style lang="less">
  .header {
    display: flex;
    flex: none;
    flex-flow: row nowrap;
    gap: 10px;
    align-items: center;
    height: 36px;
    padding: 0 12px;
    // border-bottom: 1px solid var(--color-border);
    font-size: 12px;
    color: var(--color-font-label);

    :global(.checkbox) {
      font-size: 14px;
    }
  }
  :global(.web) .header {
    .song {
      flex: auto;
    }
    > span:not(.song) {
      width: 22%;
    }
    > span:last-child {
      width: 9%;
    }
  }
  .num {
    .nobreak();
    .center();

    display: flex;
    align-items: center;
    justify-content: center;
    // color: var(--color-font-label);
  }
  :global(.desktop) .header {
    display: grid;
    grid-template-columns: var(--track-columns);
    column-gap: 8px;
    height: 32px;
    padding-inline: 12px;
    margin: 0 var(--list-inset) 4px;
    color: var(--color-font-label);
    border-bottom: 1px solid var(--color-border);
    .song {
      grid-column: span 2;
    }
    span:last-child {
      text-align: right;
    }
  }
</style>
