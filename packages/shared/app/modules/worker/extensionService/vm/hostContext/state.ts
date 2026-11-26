export interface VMState {
  extension: AnyListen.Extension.Extension
  key: string
  vmContext: AnyListen.ExtensionVM.VMContext
  preloadFuncs: WarpPromiseRecord<AnyListen.IPCExtension.ExtensionIPCActions>
  unsubscribeEvents: Array<() => void>
  logcat: (message: string) => void
}

export const contextState = {
  vmContexts: new Map<string, VMState>(),
}
