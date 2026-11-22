<script lang="ts">
  import type { ResourceListType } from '../../shared.svelte'
  let { sourceList }: { sourceList: NonNullable<ResourceListType['albumSearch']> } = $props()
</script>
