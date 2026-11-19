<script lang="ts">
  import { t } from '@/plugins/i18n'
  import { showListEditModal } from '@/components/apis/listEditModal'
</script>

<div class="container">
  <h2>{$t('my_list')}</h2>
  {#if import.meta.env.VITE_IS_DESKTOP}
    <button type="button" aria-label={$t('user_list_menu__create')} onclick={async () => showListEditModal()}>
      <svg aria-hidden="true" viewBox="0 0 24 24" width="18" height="18">
        <path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" />
      </svg>
    </button>
  {/if}
</div>

<style lang="less">
  .container {
    flex: none;
    // display: flex;
    // flex-flow: row nowrap;
    // align-items: center;
    padding: 6px 12px;
  }

  h2 {
    font-size: 12px;
    color: var(--color-primary-light-300);
  }
  :global(.desktop) .container {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 8px 8px 12px;

    button {
      display: grid;
      place-items: center;
      width: 26px;
      height: 26px;
      color: var(--color-font-label);
      cursor: pointer;
      background: transparent;
      border: 0;
      border-radius: 50%;

      &:hover {
        color: var(--color-font);
        background: var(--color-primary-background-hover);
      }
    }

    h2 {
      font-weight: 600;
      color: var(--color-700);
    }
  }
</style>
