<script>
  import PlayInfo from './components/PlayInfo.svelte'
  import Pic from './components/Pic.svelte'
  import ControlBtns from './components/ControlBtns.svelte'
  import MiniPlayProgress from './components/MiniPlayProgress.svelte'
  import CenterControlLayout from './components/CenterControlLayout.svelte'
  import Container from './components/Container.svelte'
  import PlayBtns from './components/PlayBtns.svelte'
  import MiddlePlayProgress from './components/MiddlePlayProgress.svelte'
</script>

{#if import.meta.env.VITE_IS_DESKTOP}
  <Container>
    <div class="dock">
      <div class="now-playing">
        <Pic />
        <PlayInfo />
      </div>
      <div class="transport">
        <div class="buttons"><PlayBtns /></div>
        <MiddlePlayProgress />
      </div>
      <div class="tools"><ControlBtns /></div>
    </div>
  </Container>
{:else}
  <CenterControlLayout>
    {#snippet left()}
      <Pic />
      <PlayInfo />
    {/snippet}
    {#snippet right()}
      <MiniPlayProgress />
      <ControlBtns />
    {/snippet}
  </CenterControlLayout>
{/if}

<style lang="less">
  .dock {
    display: grid;
    grid-template-rows: minmax(0, 1fr);
    grid-template-columns: minmax(0, 1fr) minmax(12rem, 42%) minmax(0, 1fr);
    align-items: center;
    width: 100%;
    height: 100%;
    padding-inline: clamp(0.375rem, 1vw, 1rem);
  }
  .now-playing,
  .tools {
    display: flex;
    align-items: center;
    min-width: 0;
  }
  .now-playing {
    height: min(3.5rem, 78%);
    min-height: 0;
    padding-right: 8px;
    > :global(.container:first-child) {
      padding: 0 12px 0 0;
    }
    :global(.pic) {
      border-radius: 0.875rem;
    }
  }
  .transport {
    display: grid;
    align-content: center;
    justify-items: center;
    min-width: 0;
    height: 100%;

    --transport-main-size: clamp(2rem, 5.5vh, 2.75rem);
    --transport-skip-size: 1.875rem;
    --transport-gap: clamp(0.5rem, 1.25vw, 1rem);

    .buttons {
      height: var(--transport-main-size);
    }
    :global(.middle-play-progress) {
      width: 100%;
      font-size: 11px;
      font-variant-numeric: tabular-nums;
      color: var(--color-font-label);
    }
    :global(.progress-bar2) {
      background: var(--action-fill);
    }
  }
  .tools {
    justify-content: flex-end;

    :global(button.container) {
      color: var(--color-font);
    }
  }
</style>
