import { ArrowLeft, ArrowRight, Check, Plug, Sparkles, Wallet } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { useConnectorMetrics } from "../../hooks/useConnectorMetrics";
import { type Billing, useBilling } from "../../hooks/useBilling";
import { useModelProviders } from "../../hooks/useModelProviders";
import { type OpenRouterConnectStatus, useOpenRouterConnect } from "../../hooks/useOpenRouterConnect";
import { navigate } from "../../hooks/useRoute";
import { formatMoney } from "../../lib/format";
import { centsFor, formatHours, lasts, perDay } from "../../lib/pricing";
import { routePath } from "../../lib/routes";
import { type VmSize, useVmSizes } from "../../hooks/useVmSizes";
import type { CreateResult, NewWorkspace } from "../../hooks/useWorkspaces";
import { Button, ChoiceCard, Spinner, TextField } from "../atoms";
import { initialVmSize, Modal, ModalBody, ModalFooter, VmSizePicker } from "../molecules";

import { isRejectedConnectorKey, useAiConnectors } from "../../hooks/useAiConnectors";

import { AiProviderNotice } from "../molecules/AiProviderNotice";

// The API rejects anything longer, so the field never lets the user type past it.
const MAX_NAME = 100;
const MAX_KEY = 1000;

const STEPS = ["Name", "VM type", "Model provider"] as const;
const LAST = STEPS.length - 1;

/** The host alone, which is what tells two providers apart at a glance. */
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

/**
 * What every workspace the user has costs with one of this size over the period the API requires
 * the balance to cover before it creates one, and how much the balance falls short of that.
 */
function coverWith(billing: Billing, size: VmSize) {
  const monthCents = billing.monthlySpendCents + size.monthlyGross * 100;
  const neededCents = Math.ceil(centsFor(monthCents, billing.requiredHours));
  return { monthCents, neededCents, shortCents: Math.max(0, neededCents - billing.balanceCents) };
}

/** Why the balance cannot pay for the size: by how much, once the balance and the size are known. */
function shortfall(billing: Billing | null, size: VmSize | undefined, serverType: string): string {
  if (!billing || !size || coverWith(billing, size).shortCents === 0) {
    return `Your balance does not cover ${size?.plan ?? serverType}${size ? ` (${perDay(size.monthlyGross * 100)})` : ""}. Add funds, then create the workspace.`;
  }
  const { neededCents, shortCents } = coverWith(billing, size);
  const span = formatHours(billing.requiredHours);
  const what = billing.monthlySpendCents === 0
    ? `${span} of ${size.plan} (${formatMoney(neededCents)})`
    : `${span} of your workspaces with this one (${formatMoney(neededCents)})`;
  return `Your balance of ${formatMoney(billing.balanceCents)} does not cover ${what}. Add ${formatMoney(shortCents)}, then create the workspace.`;
}

/** What the OpenRouter popup is doing, once the user has started it; null while there is nothing to say. */
function connectNote(status: OpenRouterConnectStatus): { text: string; error: boolean } | null {
  switch (status) {
    case "waiting":
      return { text: "Approve the key in the OpenRouter window.", error: false };
    case "exchanging":
      return { text: "Getting the key from OpenRouter…", error: false };
    case "blocked":
      return { text: "The browser blocked the OpenRouter window. Allow pop-ups for this site and try again.", error: true };
    case "failed":
      return { text: "OpenRouter did not issue a key. Try again, or paste one.", error: true };
    default:
      return null;
  }
}

/** Leaves the dialog for the billing page. */
function AddFundsLink({ onClose }: { readonly onClose: () => void }) {
  return (
    <a
      href={routePath({ name: "billing" })}
      onClick={(event) => {
        event.preventDefault();
        onClose();
        navigate({ name: "billing" });
      }}
      className="inline-flex w-fit items-center gap-1.5 rounded-lg border border-zinc-300 bg-white px-3 py-1.5 font-medium text-zinc-900 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:hover:bg-zinc-800"
    >
      <Wallet aria-hidden className="size-4" />
      Add funds
    </a>
  );
}

/** How long the balance lasts with the size, or what it lacks for the API to create it. */
function BalanceHint({ billing, size, onClose }: { readonly billing: Billing; readonly size: VmSize; readonly onClose: () => void }) {
  const { monthCents, shortCents } = coverWith(billing, size);
  if (shortCents > 0) {
    return (
      <div role="status" aria-label="Balance" className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 text-sm leading-6 text-zinc-700 dark:border-amber-950 dark:bg-amber-950/30 dark:text-zinc-300">
        <p>{shortfall(billing, size, size.serverType)}</p>
        <AddFundsLink onClose={onClose} />
      </div>
    );
  }
  return (
    <p role="status" aria-label="Balance" className="text-sm text-zinc-500 dark:text-zinc-400">
      {billing.monthlySpendCents === 0 ? "It costs" : "With it, your workspaces cost"} {perDay(monthCents)} ({formatMoney(monthCents)} a month).
      Your balance of {formatMoney(billing.balanceCents)} lasts {lasts(billing.balanceCents, monthCents)}.
    </p>
  );
}

interface Props {
  readonly initialName: string;
  /** The tour uses the real name form, then its separate OpenRouter and trial-size steps. */
  readonly onNameChosen?: (name: string) => void;
  /** Resolves "created" once it exists; on anything else the dialog stays and keeps its input. */
  readonly onCreate: (workspace: NewWorkspace) => Promise<CreateResult>;
  readonly onClose: () => void;
}

/** Name and size the workspace, then choose or save a reusable personal AI connector. */
export function CreateWorkspaceDialog({ initialName, onNameChosen, onCreate, onClose }: Props) {
  const [step, setStep] = useState(0);
  const [name, setName] = useState(initialName);
  const [serverType, setServerType] = useState("");
  const [providerId, setProviderId] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [connectorId, setConnectorId] = useState("");
  const [accepted, setAccepted] = useState(false);
  const defaultChosen = useRef(false);
  // OpenRouter's own approval screen is the default; pasting a key is the fallback.
  const [pasting, setPasting] = useState(false);
  const [connectorError, setConnectorError] = useState("");
  const connectors = useAiConnectors();
  const [rejected, setRejected] = useState(false);
  const [pending, setPending] = useState(false);
  const [soldOut, setSoldOut] = useState<string | null>(null);
  const [noFunds, setNoFunds] = useState(false);
  const { sizes, status, reload, waiting, wait } = useVmSizes();
  const chosen = sizes.find((size) => size.serverType === serverType);
  const providers = useModelProviders();
  const { account } = useAccount();
  const { billing } = useBilling();
  // Admins' workspaces are free, so only everyone else is told what one costs them.
  const charged = account !== null && account.role !== "ADMIN";
  const insufficientFunds = charged && billing !== null && chosen !== undefined && coverWith(billing, chosen).shortCents > 0;
  const provider = providers.providers.find((p) => p.id === providerId);
  const nameInput = useRef<HTMLInputElement>(null);
  const types = useRef<HTMLFieldSetElement>(null);
  const keyInput = useRef<HTMLInputElement>(null);
  const openRouter = useOpenRouterConnect((key) => {
    setApiKey(key);
    setRejected(false);
    void finish(key);
  });
  const note = connectNote(openRouter.status);
  const selected = connectors.connectors.find(connector => connector.id === connectorId);
  const creditCheckId = step === LAST && selected?.provider === "openrouter" ? selected.id : "";
  const connectorUsage = useConnectorMetrics(creditCheckId, 30_000);
  const remainingCredits = connectorUsage.metrics?.limitRemaining
    ?? (connectorUsage.metrics?.usage != null && (connectorUsage.metrics.limit != null || selected?.trial)
      ? (connectorUsage.metrics.limit ?? 1) - connectorUsage.metrics.usage : null);
  const noConnectorCredits = remainingCredits !== null && remainingCredits <= 0;
  const checkingConnectorCredits = !!creditCheckId && !connectorUsage.metrics && !connectorUsage.error;
  const connectorCreditBlocked = noConnectorCredits || checkingConnectorCredits;
  const ownConnectors = connectors.connectors.filter(c => c.provider === providerId);
  const connecting = !connectorId && provider?.id === "openrouter" && !pasting;
  const popupOpen = openRouter.status === "waiting" || openRouter.status === "exchanging";

  useEffect(() => {
    if (connectors.loading || defaultChosen.current) return;
    defaultChosen.current = true;
    const trial = connectors.connectors.find(connector => connector.trial && connector.provider === "openrouter");
    if (trial) { setConnectorId(trial.id); setProviderId(trial.provider); }
  }, [connectors.loading, connectors.connectors]);

  // The suggested name is selected, so typing replaces it and Enter moves on.
  useEffect(() => {
    if (step === 0) {
      nameInput.current?.focus();
      nameInput.current?.select();
    }
  }, [step]);

  // A size that is sold out after a reload cannot stay chosen.
  useEffect(() => {
    if (status !== "ready") return;
    setServerType((current) => sizes.some((s) => s.serverType === current && s.available)
      ? current : initialVmSize(sizes));
  }, [sizes, status]);

  useEffect(() => {
    if (!providerId && providers.providers.length === 1) {
      setProviderId(providers.providers[0].id);
    }
  }, [providers.providers, providerId]);

  // The key is what is left to type once a provider is chosen, so focus waits there.
  useEffect(() => {
    if (step === LAST && provider && !connecting) {
      keyInput.current?.focus();
    }
  }, [step, provider, connecting]);

  // The name field is gone once the step moves on, so focus follows to the chosen type and
  // Enter creates the workspace from there.
  useEffect(() => {
    if (step === 1 && serverType) {
      types.current?.querySelector<HTMLInputElement>("input:checked")?.focus();
    }
  }, [step, serverType]);

  // Closing mid-request would hide whether the workspace was made, so it waits.
  const close = () => {
    if (!pending) {
      onClose();
    }
  };

  // Show refusals where the user can act on them.
  const refused = (result: Exclude<CreateResult, "created">) => {
    if (result === "rejected") {
      setRejected(true);
      keyInput.current?.focus();
    } else if (result === "noFunds") {
      setNoFunds(true);
    } else if (result === "soldOut") {
      setSoldOut(chosen?.plan ?? serverType);
      setServerType("");
      setStep(1);
      reload();
    }
  };

  const keyLabel = async (): Promise<string | null> => `workspaced: ${name.trim()}`;

  // Every new key becomes a named personal connector before the workspace uses it.
  const finish = async (key: string) => {
    if (connectorCreditBlocked) return;
    if ((!connectorId || selected?.trial) && !accepted) return;
    setPending(true);
    setRejected(false);
    setNoFunds(false);
    setConnectorError("");
    try {
      if (selected?.trial) await connectors.acknowledge(selected.id);
      let savedId = connectorId;
      if (!savedId) {
        const saved = await connectors.add(name.trim(), providerId, key, accepted);
        savedId = saved.id;
        setConnectorId(savedId);
      }
      const result = await onCreate({ name, serverType, modelProvider: providerId, apiKey: "", connectorId: savedId }).catch((): CreateResult => "failed");
      if (result === "created") {
        onClose();
      } else {
        refused(result);
      }
    } catch (cause) {
      if (isRejectedConnectorKey(cause)) setRejected(true);
      else setConnectorError("Could not save the AI connector. Please try again.");
    } finally {
      setPending(false);
    }
  };

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    if (step === 1 && insufficientFunds) return;
    if (step === 0 && onNameChosen) {
      if (name.trim()) onNameChosen(name.trim());
      return;
    }
    if (step < LAST) {
      setStep(step + 1);
      return;
    }
    if (connecting) {
      // A popup the user lost behind the page can be opened again; an exchange in flight cannot.
      if (accepted && openRouter.status !== "exchanging") {
        setRejected(false);
        openRouter.connect(keyLabel);
      }
      return;
    }
    await finish(apiKey);
  };

  let providerChoices;
  if (providers.status === "loading") {
    providerChoices = (
      <div className="flex items-center gap-2 rounded-lg border border-dashed border-zinc-300 px-4 py-3 text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
        <Spinner label="Loading providers" />
        Loading providers…
      </div>
    );
  } else if (providers.status === "failed" || providers.providers.length === 0) {
    providerChoices = (
      <p className="rounded-lg border border-rose-300 px-4 py-3 text-sm text-rose-600 dark:border-rose-900 dark:text-rose-400">
        Could not load providers. Close this and try again.
      </p>
    );
  } else {
    providerChoices = providers.providers.map((p) => (
      <ChoiceCard
        key={p.id}
        name="workspace-provider"
        value={p.id}
        label={p.name}
        detail={hostOf(p.baseUrl)}
        icon={Sparkles}
        checked={p.id === providerId}
        disabled={pending}
        onChange={(id) => {
          setProviderId(id);
          setConnectorId("");
          setRejected(false);
        }}
      />
    ));
  }

  let stepBody;
  if (step === 0) {
    stepBody = (
      <TextField
        ref={nameInput}
        id="workspace-name"
        label="Name"
        value={name}
        maxLength={MAX_NAME}
        onChange={(e) => setName(e.currentTarget.value)}
      />
    );
  } else if (step === 1) {
    stepBody = (
      <fieldset ref={types} className="flex min-w-0 flex-col gap-2">
        <legend className="mb-1.5 text-sm font-medium text-zinc-700 dark:text-zinc-300">VM type</legend>
        <VmSizePicker
          sizes={sizes}
          status={status}
          value={serverType}
          disabled={pending}
          soldOut={soldOut}
          waiting={waiting}
          onWait={wait}
          onChange={(type) => {
            setServerType(type);
            setSoldOut(null);
            setNoFunds(false);
          }}
          onReload={reload}
        />
        {charged && billing && chosen && <BalanceHint billing={billing} size={chosen} onClose={onClose} />}
      </fieldset>
    );
  } else {
    const switchTo = (paste: boolean) => (
      <button type="button" disabled={pending} onClick={() => { setPasting(paste); setRejected(false); }} className="w-fit text-sm font-medium text-zinc-600 underline underline-offset-4 hover:text-zinc-900 disabled:opacity-50 dark:text-zinc-400 dark:hover:text-zinc-100">
        {paste ? "Paste a key instead" : "Connect OpenRouter instead"}
      </button>
    );
    stepBody = (
      <>
        {/* A single provider is no choice, so the card shows only while there is something to say. */}
        {(providers.status !== "ready" || providers.providers.length !== 1) && (
          <fieldset className="flex min-w-0 flex-col gap-2">
            <legend className="mb-1.5 text-sm font-medium text-zinc-700 dark:text-zinc-300">Model provider</legend>
            {providerChoices}
          </fieldset>
        )}
        {ownConnectors.length > 0 && (
          <label className="block text-sm font-medium">AI connector
            <select aria-label="AI connector" className="mt-1.5 block w-full rounded-lg border border-zinc-300 bg-transparent p-2 dark:border-zinc-700" value={connectorId} disabled={pending || connectors.loading} onChange={event => { setConnectorId(event.target.value); setRejected(false); }}>
              {ownConnectors.map(c => <option key={c.id} value={c.id}>{c.name}{c.trial ? " — Default" : ""}</option>)}
              <option value="">New {provider?.name ?? "provider"} key</option>
            </select>
          </label>
        )}
        {connectors.error && <p role="alert" className="text-sm text-rose-600">{connectors.error}</p>}
        {connectorError && <p role="alert" className="text-sm text-rose-600">{connectorError}</p>}
        {connectorId && rejected && <p role="alert" className="text-sm text-rose-600">The provider rejected this connector's key. Select another connector.</p>}
        {checkingConnectorCredits && <p role="status" className="text-sm text-zinc-500">Checking connector credits…</p>}
        {noConnectorCredits && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">This AI connector has no credits remaining. Select another connector or add a funded key.</p>}
        {connecting && (
          <div className="flex flex-col gap-2 text-sm text-zinc-600 dark:text-zinc-400">
            <p>Sign in to OpenRouter and approve a key for this workspace. It is saved to your account, so later workspaces can reuse it.</p>
            {rejected && <p role="alert" className="text-xs text-rose-600 dark:text-rose-400">OpenRouter did not accept this key</p>}
            {note && (
              <p role={note.error ? "alert" : "status"} className={note.error ? "text-xs text-rose-600 dark:text-rose-400" : "text-xs text-zinc-500 dark:text-zinc-400"}>
                {note.text}
              </p>
            )}
            {switchTo(true)}
          </div>
        )}
        {!connectorId && provider && !connecting && (
          <div className="flex flex-col gap-2">
            <TextField
              ref={keyInput}
              id="workspace-api-key"
              label={`${provider.name} API key`}
              type="password"
              placeholder="Paste your key"
              autoComplete="off"
              spellCheck={false}
              value={apiKey}
              maxLength={MAX_KEY}
              disabled={pending}
              hint={`Checked with ${provider.name}, then saved to your account so later workspaces can reuse it.`}
              error={rejected ? `${provider.name} did not accept this key` : null}
              onChange={(e) => {
                setApiKey(e.currentTarget.value);
                setRejected(false);
              }}
            />
            {provider.id === "openrouter" && switchTo(false)}
          </div>
        )}
        {(!connectorId || selected?.trial) && <AiProviderNotice accepted={accepted} onChange={setAccepted} disabled={pending || popupOpen} />}
        {noFunds && (
          <div role="alert" className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 text-sm leading-6 text-zinc-700 dark:border-amber-950 dark:bg-amber-950/30 dark:text-zinc-300">
            <p>{shortfall(billing, chosen, serverType)}</p>
            <AddFundsLink onClose={onClose} />
          </div>
        )}
      </>
    );
  }

  let next;
  if (step === 0) {
    next = <Button label="Next" type="submit" icon={ArrowRight} disabled={!name.trim()} />;
  } else if (step === 1) {
    next = <Button label="Next" type="submit" icon={ArrowRight} disabled={!serverType || status !== "ready" || insufficientFunds} />;
  } else if (connecting) {
    next = (
      <Button
        label={openRouter.status === "connected" ? "Connected to OpenRouter" : "Connect OpenRouter"}
        type="submit"
        icon={openRouter.status === "connected" ? Check : Plug}
        busy={pending || openRouter.status === "exchanging"}
        disabled={!accepted || openRouter.status === "exchanging"}
      />
    );
  } else {
    next = (
      <Button
        label="Create workspace"
        type="submit"
        icon={ArrowRight}
        busy={pending}
        disabled={!provider || (!connectorId && (!apiKey.trim() || !accepted)) || (!!selected?.trial && !accepted) || rejected || connectorCreditBlocked}
      />
    );
  }

  return (
    <Modal
      title="New workspace"
      description={onNameChosen ? "Name it, connect OpenRouter, then confirm the free trial machine." : undefined}
      onClose={close}
      wide={step === 1}
    >
      <form data-tour="workspace-name-form" onSubmit={submit} className="flex min-h-0 flex-col overflow-hidden">
        <ModalBody>
          <ol aria-label="Steps" className="flex items-center gap-2 text-xs font-medium">
            {(onNameChosen ? ["Name", "OpenRouter", "Trial machine"] : STEPS).map((label, index) => {
              const reached = index <= step;
              return (
                <li
                  key={label}
                  aria-current={index === step ? "step" : undefined}
                  className={`flex items-center gap-2 ${
                    reached ? "text-zinc-900 dark:text-zinc-100" : "text-zinc-400 dark:text-zinc-500"
                  }`}
                >
                  {index > 0 && <span aria-hidden className="h-px w-6 bg-zinc-300 dark:bg-zinc-700" />}
                  <span
                    aria-hidden
                    className={`flex size-5 items-center justify-center rounded-full border ${
                      reached
                        ? "border-zinc-900 bg-zinc-900 text-white dark:border-white dark:bg-white dark:text-zinc-900"
                        : "border-zinc-300 dark:border-zinc-600"
                    }`}
                  >
                    {index < step ? <Check className="size-3" strokeWidth={3} /> : index + 1}
                  </span>
                  {label}
                </li>
              );
            })}
          </ol>
          {stepBody}
        </ModalBody>
        <ModalFooter>
          {step === 0 ? (
            <Button label="Cancel" variant="secondary" onClick={close} />
          ) : (
            <Button
              label="Back"
              variant="secondary"
              icon={ArrowLeft}
              onClick={() => {
                setStep(step - 1);
              }}
              disabled={pending}
            />
          )}
          {next}
        </ModalFooter>
      </form>
    </Modal>
  );
}
