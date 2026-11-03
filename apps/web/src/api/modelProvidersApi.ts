import { send } from "./http";

export interface ModelProvider {
  readonly id: string;
  readonly name: string;
  readonly baseUrl: string;
  /** The workspace secret the provider's key is kept in. */
  readonly apiKeyVariable: string;
}

export const MODEL_PROVIDERS_URL = "/api/v1/model-providers";

export async function listModelProviders(): Promise<ModelProvider[]> {
  return (await send(MODEL_PROVIDERS_URL)).json();
}
