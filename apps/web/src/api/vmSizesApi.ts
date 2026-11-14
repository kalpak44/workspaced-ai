import { send } from "./http";

/** A size a workspace's VM can be created as, with its specs as Hetzner sells it now. */
export interface VmSize {
  readonly serverType: string;
  /** Taken when a create names no size. */
  readonly isDefault: boolean;
  readonly cores: number;
  readonly memoryGb: number;
  readonly diskGb: number;
  /** shared or dedicated */
  readonly cpuType: string;
  readonly architecture: string;
}

export const VM_SIZES_URL = "/api/v1/vm-sizes";

export async function listVmSizes(): Promise<VmSize[]> {
  return (await send(VM_SIZES_URL)).json();
}
