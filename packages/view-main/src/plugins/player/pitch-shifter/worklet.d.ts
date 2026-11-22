/** AudioWorklet globals are available only inside the processor module. */
export interface ProcessorOptions {
  numberOfInputs: number
  numberOfOutputs: number
  processorOptions?: { blockSize?: number }
}

export type AudioBuses = Float32Array[][]
export type AudioParameters = Record<string, Float32Array>

interface AudioProcessor {
  readonly port: MessagePort
}

export interface WorkletScope {
  AudioWorkletProcessor: new () => AudioProcessor
  registerProcessor: (name: string, processor: new (options: ProcessorOptions) => AudioProcessor & {
    process(inputs: AudioBuses, outputs: AudioBuses, parameters: AudioParameters): boolean
  }) => void
}
