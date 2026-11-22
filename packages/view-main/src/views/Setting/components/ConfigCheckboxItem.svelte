<script lang="ts">
  import { LX_SOURCE_COMMANDS, LX_SOURCE_MAX_ENABLED } from '@any-listen/common/command'
  import Checkbox from '@/components/base/Checkbox.svelte'
  import TitleContent from './TitleContent.svelte'
  import Btn from '@/components/base/Btn.svelte'
  import { t } from '@/plugins/i18n'
  import { executeCommand } from '@/modules/command/actions'
  let {
    item,
    onchange,
    onremove,
  }: {
    item: AnyListen.Extension.FormConfigValue<
      AnyListen.Extension.FormConfigCheckbox | AnyListen.Extension.FormConfigCheckboxMultiple
    >
    multiple?: boolean
    onchange: (value: string, checked: boolean) => void
    onremove: (value: string) => Promise<void>
  } = $props()
  const linkedSources = $derived(import.meta.env.VITE_IS_DESKTOP && item.field === 'enabledScripts' && item.actionCommands?.includes(LX_SOURCE_COMMANDS.add))
  const hasLink = (row: AnyListen.Extension.ConfigEnum) => !!(row.raw as { sourceUrl?: string } | null)?.sourceUrl
  const enabledIds = $derived(Array.isArray(item.value) ? item.value : [])
  const visibleRows = $derived.by(() => {
    if (!linkedSources) return item.enum
    const rank = (id: string) => enabledIds.includes(id) ? enabledIds.indexOf(id) : enabledIds.length
    return [...item.enum].sort((a, b) => rank(a.value) - rank(b.value))
  })
  const maximum = $derived(linkedSources ? LX_SOURCE_MAX_ENABLED : item.type === 'configCheckboxMultiple' ? item.max : null)
  const disabled = $derived(maximum != null && enabledIds.length >= maximum)
</script>

{#snippet CheckBoxItem(
  checkboxItem: AnyListen.Extension.ConfigEnum,
  removeable: boolean,
  checked: boolean,
  onchange: (checked: boolean) => void
)}
  <div class="settings-item-config-checkbox-item">
    <div class="settings-item-config-checkbox-item-content">
      <Checkbox
        label={checkboxItem.name}
        id={`extenstion_${item.type}_${item.field}_${checkboxItem.value}`}
        {checked}
        {onchange}
        disabled={!checked && disabled}
      />
      {#if linkedSources}
        {#if checked}
          <span>{$t('lx_source_priority', { rank: enabledIds.indexOf(checkboxItem.value) + 1 })}</span>
          {#if enabledIds.indexOf(checkboxItem.value) > 0}
            <Btn min onclick={async () => executeCommand(LX_SOURCE_COMMANDS.up, checkboxItem.value)}>{$t('lx_source_up')}</Btn>
          {/if}
          {#if enabledIds.indexOf(checkboxItem.value) < enabledIds.length - 1}
            <Btn min onclick={async () => executeCommand(LX_SOURCE_COMMANDS.down, checkboxItem.value)}>{$t('lx_source_down')}</Btn>
          {/if}
        {/if}
        <Btn min onclick={async () => executeCommand(LX_SOURCE_COMMANDS.test, checkboxItem.value)}>{$t('lx_source_test')}</Btn>
        {#if hasLink(checkboxItem)}
          <Btn min onclick={async () => executeCommand(LX_SOURCE_COMMANDS.update, checkboxItem.value)}>{$t('lx_link_update')}</Btn>
        {/if}
        <Btn min onclick={async () => executeCommand(LX_SOURCE_COMMANDS.bind, checkboxItem.value)}>{$t(hasLink(checkboxItem) ? 'lx_link_edit' : 'lx_link_bind')}</Btn>
      {/if}
      {#if removeable}
        <Btn
          min
          onclick={async () => {
            await onremove(checkboxItem.value)
          }}
        >
          {$t('btn_remove')}
        </Btn>
      {/if}
    </div>
    {#if checkboxItem.description}
      <p class="settings-item-desc">{checkboxItem.description}</p>
    {/if}
  </div>
{/snippet}

<TitleContent name={item.name} desc={linkedSources ? $t('lx_source_order_help', { count: LX_SOURCE_MAX_ENABLED }) : item.description}>
  <div class="settings-item-config-checkbox">
    {#each visibleRows as enumItem, idx (linkedSources ? enumItem.value : idx)}
      {@render CheckBoxItem(enumItem, item.removeable ?? false, item.value?.includes(enumItem.value) ?? false, (checked) => {
        onchange(enumItem.value, checked)
      })}
    {/each}
    {#if item.actionCommands}
      <div class="settings-item-config-checkbox-action">
        {#each item.actionCommands as cmd, idx (idx)}
          <Btn
            min
            onclick={async () => {
              await executeCommand(cmd)
            }}>{item.actionCommandNames?.[idx] ?? cmd}</Btn
          >
        {/each}
      </div>
    {/if}
  </div>
</TitleContent>

<style lang="less">
  .settings-item-config-checkbox {
    margin-left: 16px;
    :global {
      .checkbox {
        margin-bottom: 5px;
        font-size: 14px;
      }
    }
  }
  .settings-item-desc {
    margin-top: -3px;
    margin-bottom: 6px;
    font-size: 12px;
    opacity: 0.7;
  }
  .settings-item-config-checkbox-action {
    display: flex;
    flex-flow: row nowrap;
    gap: 8px;
    margin-top: 8px;
  }
  .settings-item-config-checkbox-item {
    display: flex;
    flex-flow: column nowrap;

    .settings-item-config-checkbox-item-content {
      display: flex;
      flex-flow: row wrap;
      gap: 10px;
      align-items: center;
      margin-bottom: 3px;

      :global(.checkbox) {
        margin-bottom: 0;
      }
    }

    :global(.btn) {
      flex: none;
    }
  }
</style>
