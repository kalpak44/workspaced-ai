import { send } from "./http";

/** A plan a workspace can be created as, with the specs of the Hetzner server behind it. */
export interface VmSize {
  /** What users choose it by, e.g. Starter. */
  readonly plan: string;
  readonly serverType: string;
  /** Taken when a create names no size. */
  readonly isDefault: boolean;
  readonly cores: number;
  readonly memoryGb: number;
  readonly diskGb: number;
  /** shared or dedicated */
  readonly cpuType: string;
  readonly architecture: string;
  /** What the user pays a month, in EUR before the VAT charged on a top-up. */
  readonly monthlyGross: number;
  /** False while Hetzner has it sold out, when a create would be refused. */
  readonly available: boolean;
}

export const VM_SIZES_URL = "/api/v1/vm-sizes";

export async function listVmSizes(): Promise<VmSize[]> {
  return (await send(VM_SIZES_URL)).json();
}
