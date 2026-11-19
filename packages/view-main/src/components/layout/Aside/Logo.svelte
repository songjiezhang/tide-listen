<script lang="ts">
  import { windowDarg } from '@/shared/browser/widnow.svelte'
</script>

{#snippet logo()}
  <svg class="header-logo" aria-hidden="true" viewBox="0 0 1692.4 253.96" width="90%">
    <use xlink:href="#icon-header-logo2" />
  </svg>
{/snippet}

{#if import.meta.env.VITE_IS_DESKTOP}
  <div class="aside-logo drag-no-any-modal">
    <svg aria-hidden="true" viewBox="0 0 24 24" width="30" height="30">
      <path d="M3 15l3-8 3 12 3-14 3 16 3-10 3 4" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
    <span>Any Listen</span>
  </div>
{/if}
{#if import.meta.env.VITE_IS_WEB}
  <div class="aside-logo" {@attach windowDarg}>
    {@render logo()}
  </div>
{/if}

<style lang="less">
  .aside-logo {
    display: flex;
    flex-flow: row nowrap;
    gap: 8px;
    align-items: flex-end;
    padding: 16px 20px;
    // font-weight: bold;
    color: var(--color-primary-dark-100-alpha-200);
    svg {
      filter: drop-shadow(0 0 4px rgb(0 0 0 / 16%));
    }
  }
  .header-logo {
    min-width: 112px;
    max-width: 200px;
    // color: var(--color-primary-dark-100-alpha-200);
  }
  :global(.desktop) .aside-logo {
    gap: clamp(6px, 0.8vw, 10px);
    align-items: center;
    padding: 20px clamp(4px, 0.75vw, 10px) 16px;
    font-size: clamp(0.95rem, 1.65vw, 1.25rem);
    font-weight: 600;
    color: var(--color-font);
    letter-spacing: 0.04em;
    white-space: nowrap;
    svg {
      flex: none;
      width: clamp(22px, 2.4vw, 30px);
      min-width: 0;
      color: var(--color-primary);
      filter: none;
    }
  }
  // .header-name {
  //   min-width: 86px;
  //   max-width: 120px;
  // }
</style>
