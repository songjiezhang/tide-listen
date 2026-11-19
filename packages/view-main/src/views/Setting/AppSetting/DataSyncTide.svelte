<script lang="ts">
  import { onMount } from 'svelte'
  import TitleContent from '../components/TitleContent.svelte'
  import { showSimpleConfirmModal } from '@/components/apis/dialog'
  import { ipc } from '@/shared/ipc/ipc'

  let info = $state<AnyListen.IPCSync.TideState>()
  let url = $state('')
  let code = $state('')
  let working = $state(false)
  let feedback = $state('')
  let migration = $state<NonNullable<AnyListen.IPCSync.TideState['preview']>>()

  onMount(() => {
    const refresh = async () => {
      if (working) return
      try { info = await ipc.tideSync({ action: 'status' }) }
      catch { /* The desktop IPC may still be connecting. */ }
    }
    void refresh()
    const timer = setInterval(refresh, 3000)
    return () => clearInterval(timer)
  })

  async function apply(options: AnyListen.IPCSync.TideOptions) {
    if (working) return
    working = true
    feedback = ''
    try {
      const next = await ipc.tideSync(options)
      info = next
      if (options.action === 'pair') code = ''
      if (options.action === 'preview') migration = next.preview
      if (options.action === 'start' || options.action === 'disconnect') migration = undefined
    } catch (error) {
      feedback = error instanceof Error ? error.message : String(error)
    } finally {
      working = false
    }
  }
</script>

<TitleContent name="汐听同步服务">
  <div class="sync-controls">
    {#if !info?.account}
      <form onsubmit={(event) => { event.preventDefault(); void apply({ action: 'pair', url, code }) }}>
        <label>服务器地址 <input type="url" placeholder="https://sync.example.com" bind:value={url} required /></label>
        <label>一次性配对码 <input type="text" autocomplete="off" spellcheck="false" bind:value={code} required /></label>
        <button disabled={working}>配对</button>
      </form>
    {:else}
      <p>{info.account} · {info.url}</p>
      <div class="actions">
        {#if info.started}
          <button disabled={working} onclick={() => apply({ action: 'sync' })}>立即同步</button>
        {:else}
          <button disabled={working} onclick={() => apply({ action: 'preview' })}>从 WebDAV 迁移</button>
          <button disabled={working} onclick={async () => {
            if (await showSimpleConfirmModal('将本机歌单与当前账号的歌单合并，并开始同步？')) void apply({ action: 'start' })
          }}>合并本机歌单并开启同步</button>
        {/if}
        <button disabled={working} onclick={async () => {
          if (await showSimpleConfirmModal('断开此设备的汐听同步？')) void apply({ action: 'disconnect' })
        }}>断开连接</button>
      </div>
      {#if migration}
        <div class="preview">
          <strong>迁移预览</strong>
          <ul>{#each migration.lists as list}<li>{list.name} · {list.count} 首</li>{/each}</ul>
          <button disabled={working} onclick={() => { if (migration) void apply({ action: 'start', preview: migration.id }) }}>备份并迁移</button>
        </div>
      {/if}
      {#if info.started}
        <label class="check">
          <input type="checkbox" checked={info.sources} disabled={working} onchange={(event) => void apply({ action: 'sources', enabled: event.currentTarget.checked })} />
          同步洛雪 JS 音源
        </label>
      {/if}
      {#if info.pendingSources && info.pendingRevision !== undefined}
        <div class="preview">
          <strong>服务器音源待确认</strong>
          <p>确认后按服务器列表替换本机洛雪 JS 音源。</p>
          <ul>{#each info.pendingSources as source}<li>{source.name} · {source.enabled ? '启用' : '停用'}</li>{/each}</ul>
          <button disabled={working} onclick={async () => {
            const revision = info?.pendingRevision
            if (revision !== undefined && await showSimpleConfirmModal('确认信任这些脚本并应用服务器音源？')) {
              void apply({ action: 'approve', revision })
            }
          }}>确认并应用音源</button>
        </div>
      {/if}
    {/if}
    <p role="status">{working ? '正在处理…' : feedback || info?.message || ''}</p>
  </div>
</TitleContent>

<style>
  .sync-controls, form { display: grid; gap: 10px; justify-items: start; }
  label { display: grid; gap: 5px; }
  label.check, .actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
  input:not([type='checkbox']), button { font: inherit; color: inherit; background: transparent; border: 1px solid currentColor; border-radius: 8px; padding: 6px 10px; }
  input[type='url'] { width: min(32rem, 68vw); }
  button:disabled { opacity: .55; }
  .preview { padding: 12px; border: 1px solid #8274aa70; border-radius: 8px; }
  .preview ul { max-height: 15rem; overflow: auto; }
</style>
