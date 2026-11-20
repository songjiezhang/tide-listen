<script lang="ts">
  import { i18n, t } from '@/plugins/i18n'
  import { createUserList, editUserList } from './shared'
  import Input from '@/components/base/Input.svelte'
  import FormItem from './FormItem.svelte'
  import { resourceList } from '@/modules/extension/reactive.svelte'
  import { extT } from '@/modules/extension/i18n'
  import Checkbox from '@/components/base/Checkbox.svelte'

  let {
    item,
    targetId,
  }: {
    item?: AnyListen.List.OnlineListInfo | AnyListen.List.GeneralListInfo | null
    targetId?: AnyListen.List.ParentId
  } = $props()

  const supportedKinds = ['songlist', 'topSongs'] as const
  const initData: AnyListen.List.OnlineListInfo = {
    id: '',
    name: '',
    parentId: null,
    type: 'online',
    meta: {
      createTime: 0,
      updateTime: 0,
      desc: '',
      playCount: 0,
      posTime: 0,
      extensionId: '',
      source: '',
      syncId: '',
      autoSync: false,
      syncTime: 0,
      pic: '',
      songCount: 0,
      sourceType: 'songlist',
    },
  }
  let listInfo = $state<AnyListen.List.OnlineListInfo>({
    ...initData,
    meta: { ...initData.meta },
  })

  let sourceName = $derived.by(() => {
    if (!listInfo.meta.source || !listInfo.meta.extensionId) return ''
    const resources = $resourceList.resources[listInfo.meta.sourceType === 'topSongs' ? 'topSongs' : 'songlist']
    const ext = resources?.find((e) => e.id === listInfo.meta.source && e.extensionId === listInfo.meta.extensionId)
    if (!ext) return listInfo.meta.source
    return $extT(ext.extensionId, ext.name)
  })

  export const verify = () => {
    const name = listInfo.name.trim()
    if (!name.length || name.length > 30) {
      return false
    }
    return true
  }
  export const reset = () => {
    listInfo = {
      ...initData,
      meta: { ...initData.meta },
    }
  }
  export const submit = async () => {
    if (!verify()) throw new Error(i18n.t('edit_list_modal__form_err_name'))
    if (!listInfo.meta.syncId.trim() || !listInfo.meta.source) throw new Error(i18n.t('edit_list_modal__form_err_source'))
    const info = $state.snapshot(listInfo)
    info.meta.syncId = info.meta.syncId.trim()
    if (item) {
      await editUserList(info)
    } else {
      await createUserList({ ...info, parentId: targetId || null })
    }
  }

  $effect(() => {
    if (!item) return
    listInfo = {
      ...item,
      type: 'online',
      meta: { ...initData.meta, ...item.meta },
    }
  })
</script>

<main class="main">
  <Input autofocus placeholder={$t('edit_list_modal__form_list_name')} bind:value={listInfo.name} />
  <FormItem>
    <Checkbox
      id="auto_sync"
      checked={listInfo.meta.autoSync ?? false}
      onchange={(checked) => {
        listInfo.meta.autoSync = checked
      }}
      label={$t('edit_list_modal.online_list_form.auto_sync')}
    />
    <p class="tip">{$t('edit_list_modal.online_list_form.auto_sync_tip')}</p>
  </FormItem>
  <FormItem label={$t('edit_list_modal.online_list_form.source_type')}>
    {#if item?.type !== 'online'}
      <select bind:value={listInfo.meta.sourceType}>
        {#each supportedKinds as kind}
          <option value={kind}>{$t(`edit_list_modal.online_list_form.source_type_${kind}`)}</option>
        {/each}
      </select>
    {:else}
      <p>{$t(`edit_list_modal.online_list_form.source_type_${listInfo.meta.sourceType}`)}</p>
    {/if}
  </FormItem>
  <FormItem label={$t('edit_list_modal.online_list_form.source')}>
    {#if item?.type !== 'online'}
      <select value={listInfo.meta.source} onchange={(event) => {
        listInfo.meta.source = event.currentTarget.value
        listInfo.meta.extensionId = 'online-metadata'
      }}>
        <option value="">—</option>
        {#each ($resourceList.resources[listInfo.meta.sourceType === 'topSongs' ? 'topSongs' : 'songlist'] ?? []).filter((r) => r.extensionId === 'online-metadata') as provider}
          <option value={provider.id}>{$extT(provider.extensionId, provider.name)}</option>
        {/each}
      </select>
    {:else}
      <p>{sourceName}</p>
    {/if}
  </FormItem>
  <FormItem label={$t('edit_list_modal.online_list_form.sync_id')}>
    {#if item?.type !== 'online'}
      <Input bind:value={listInfo.meta.syncId} />
    {:else}
      <p class="path code select">{listInfo.meta.syncId}</p>
    {/if}
  </FormItem>
</main>

<style lang="less">
  .main {
    display: flex;
    // flex: auto;
    // padding: 0 15px;
    // width: 320px;
    flex-flow: column nowrap;
    // min-height: 0;
    // max-height: 100%;
    // overflow: hidden;
    gap: 8px;
    p {
      font-size: 14px;
    }
  }
  .path {
    padding-right: 8px;
    font-size: 13px;
    // color: var(--color-primary);
  }
  .tip {
    // padding-left: 19px;
    font-size: 12px;
    opacity: 0.7;
  }
</style>
