import { ArrowLeft, ArrowRight, Check, Plug, Sparkles, Wallet } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { type Billing, useBilling } from "../../hooks/useBilling";
import { useModelProviders } from "../../hooks/useModelProviders";
import { type OpenRouterConnectStatus, useOpenRouterConnect } from "../../hooks/useOpenRouterConnect";
import { navigate } from "../../hooks/useRoute";
import { formatMoney } from "../../lib/format";
import { routePath } from "../../lib/routes";
import { type VmSize, useVmSizes } from "../../hooks/useVmSizes";
import type { CreateResult, DraftResult, NewWorkspace } from "../../hooks/useWorkspaces";
import { Button, ChoiceCard, Spinner, TextField } from "../atoms";
import { initialVmSize, Modal, ModalBody, ModalFooter, VmSizePicker } from "../molecules";

const PRICE = new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR" });

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
 * A month of every workspace the user has with one of this size, which the balance must cover for
 * the API to create it, and how much the balance falls short of that.
 */
function monthWith(billing: Billing, size: VmSize) {
  const monthCents = billing.monthlySpendCents + Math.round(size.monthlyGross * 100);
  return { monthCents, shortCents: Math.max(0, monthCents - billing.balanceCents) };
}

/** Why the balance cannot pay for the size: by how much, once the balance and the size are known. */
function shortfall(billing: Billing | null, size: VmSize | undefined, serverType: string): string {
  if (!billing || !size || monthWith(billing, size).shortCents === 0) {
    return `Your balance does not cover a month of ${serverType}${size ? ` (${PRICE.format(size.monthlyGross)} a month)` : ""}. Add funds, then create the workspace.`;
  }
  const { monthCents, shortCents } = monthWith(billing, size);
  const month = billing.monthlySpendCents === 0
    ? `a month of ${serverType} (${formatMoney(monthCents)})`
    : `a month of your workspaces with this one (${formatMoney(monthCents)})`;
  return `Your balance of ${formatMoney(billing.balanceCents)} does not cover ${month}. Add ${formatMoney(shortCents)}, then create the workspace.`;
}

/** "about 1.4 months", "about 12 months": how long a balance of at least a month lasts. */
function coverage(balanceCents: number, monthCents: number): string {
  const months = balanceCents / monthCents;
  return `about ${months >= 10 ? Math.floor(months) : Number(months.toFixed(1))} months`;
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
  const { monthCents, shortCents } = monthWith(billing, size);
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
      {billing.monthlySpendCents === 0 ? "It costs" : "With it, your workspaces cost"} {formatMoney(monthCents)} a month.
      Your balance of {formatMoney(billing.balanceCents)} lasts {coverage(billing.balanceCents, monthCents)}.
    </p>
  );
}

interface Props {
  readonly initialName: string;
  /** The tour uses the real name form, then its separate OpenRouter and trial-size steps. */
  readonly onNameChosen?: (name: string) => void;
  /** Resolves "created" once it exists; on anything else the dialog stays and keeps its input. */
  readonly onCreate: (workspace: NewWorkspace) => Promise<CreateResult>;
  /** Reserves the id OpenRouter's key is labelled with, before the key exists. */
  readonly onDraft: (workspace: Omit<NewWorkspace, "apiKey">) => Promise<DraftResult>;
  /** Creates the draft's VM with its key, resolving as `onCreate` does. */
  readonly onLaunch: (id: string, apiKey: string) => Promise<CreateResult>;
  /** Drops a draft the dialog will not launch. */
  readonly onDiscard: (id: string) => void;
  readonly onClose: () => void;
}

/**
 * Three steps: the name, the size of the VM it runs on, then the model provider and its key. The
 * sizes are fetched as the dialog opens, since they come from Hetzner and can change, and the
 * default one starts out chosen while Hetzner has it; so does the provider while there is only one.
 * A size that sells out before the create brings the dialog back to the sizes. The API checks the
 * key before it creates anything, so a wrong one is reported here rather than as a broken
 * workspace. OpenRouter can also issue the key itself, from the user's own account, so nobody has
 * to find and copy one: the workspace is drafted first, so the key is labelled with its id, and
 * launched once the key arrives. A draft the dialog leaves without launching is discarded. A user who pays for workspaces sees, with the size, how long the balance
 * lasts with it among their others, since the API refuses one the balance cannot pay a month of.
 */
export function CreateWorkspaceDialog({ initialName, onNameChosen, onCreate, onDraft, onLaunch, onDiscard, onClose }: Props) {
  const [step, setStep] = useState(0);
  const [name, setName] = useState(initialName);
  const [serverType, setServerType] = useState("");
  const [providerId, setProviderId] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [rejected, setRejected] = useState(false);
  const [pending, setPending] = useState(false);
  const [soldOut, setSoldOut] = useState<string | null>(null);
  const [noFunds, setNoFunds] = useState(false);
  const { sizes, status, reload } = useVmSizes();
  const chosen = sizes.find((size) => size.serverType === serverType);
  const providers = useModelProviders();
  const { account } = useAccount();
  const { billing } = useBilling();
  // Admins' workspaces are free, so only everyone else is told what one costs them.
  const charged = account !== null && account.role !== "ADMIN";
  const provider = providers.providers.find((p) => p.id === providerId);
  const nameInput = useRef<HTMLInputElement>(null);
  const types = useRef<HTMLFieldSetElement>(null);
  const keyInput = useRef<HTMLInputElement>(null);
  // A draft holds the name and size it was made with, so it is dropped whenever those may change.
  const draftId = useRef<string | null>(null);
  const discardDraft = () => {
    if (draftId.current) {
      onDiscard(draftId.current);
      draftId.current = null;
    }
  };
  const openRouter = useOpenRouterConnect((key) => {
    setApiKey(key);
    setRejected(false);
    void finish(key);
  });
  const note = connectNote(openRouter.status);

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
    if (!serverType || !sizes.some((s) => s.serverType === serverType && s.available)) {
      setServerType(initialVmSize(sizes));
    }
  }, [sizes, status, serverType]);

  useEffect(() => {
    if (!providerId && providers.providers.length === 1) {
      setProviderId(providers.providers[0].id);
    }
  }, [providers.providers, providerId]);

  // The key is what is left to type once a provider is chosen, so focus waits there.
  useEffect(() => {
    if (step === LAST && provider) {
      keyInput.current?.focus();
    }
  }, [step, provider]);

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
      discardDraft();
      onClose();
    }
  };

  // What the create, the draft or the launch refused, shown where the user can act on it.
  const refused = (result: Exclude<CreateResult, "created">) => {
    if (result === "rejected") {
      setRejected(true);
      keyInput.current?.focus();
    } else if (result === "noFunds") {
      setNoFunds(true);
    } else if (result === "soldOut") {
      discardDraft();
      setSoldOut(serverType);
      setServerType("");
      setStep(1);
      reload();
    }
  };

  /** The id to label OpenRouter's key with, drafting the workspace the first time. */
  const keyLabel = async (): Promise<string | null> => {
    if (!draftId.current) {
      setNoFunds(false);
      const result = await onDraft({ name, serverType, modelProvider: providerId }).catch((): DraftResult => "failed");
      if (typeof result === "string") {
        refused(result);
        return null;
      }
      draftId.current = result.id;
    }
    return `workspaced: ${draftId.current}`;
  };

  // A draft is launched with the key; without one, the workspace is created in one go.
  const finish = async (key: string) => {
    setPending(true);
    setRejected(false);
    setNoFunds(false);
    try {
      const draft = draftId.current;
      const result = await (draft
        ? onLaunch(draft, key)
        : onCreate({ name, serverType, modelProvider: providerId, apiKey: key })
      ).catch((): CreateResult => "failed");
      if (result === "created") {
        draftId.current = null;
        onClose();
      } else {
        refused(result);
      }
    } finally {
      setPending(false);
    }
  };

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    if (step === 0 && onNameChosen) {
      if (name.trim()) onNameChosen(name.trim());
      return;
    }
    if (step < LAST) {
      setStep(step + 1);
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
    stepBody = (
      <>
        <fieldset className="flex min-w-0 flex-col gap-2">
          <legend className="mb-1.5 text-sm font-medium text-zinc-700 dark:text-zinc-300">Model provider</legend>
          {providerChoices}
        </fieldset>
        {provider?.id === "openrouter" && (
          <div className="flex flex-col gap-1.5">
            <div className="flex flex-wrap items-center gap-3">
              <Button
                label={openRouter.status === "connected" ? "Connected to OpenRouter" : "Connect OpenRouter"}
                variant="secondary"
                icon={openRouter.status === "connected" ? Check : Plug}
                busy={openRouter.status === "exchanging"}
                disabled={pending}
                onClick={() => openRouter.connect(keyLabel)}
              />
              <span className="text-sm text-zinc-500 dark:text-zinc-400">or paste a key below</span>
            </div>
            {note && (
              <p
                role={note.error ? "alert" : "status"}
                className={note.error ? "text-xs text-rose-600 dark:text-rose-400" : "text-xs text-zinc-500 dark:text-zinc-400"}
              >
                {note.text}
              </p>
            )}
          </div>
        )}
        {provider && (
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
            hint={`Checked with ${provider.name} before the workspace is created, then kept as a workspace secret that cannot be removed.`}
            error={rejected ? `${provider.name} did not accept this key` : null}
            onChange={(e) => {
              setApiKey(e.currentTarget.value);
              setRejected(false);
            }}
          />
        )}
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
    next = <Button label="Next" type="submit" icon={ArrowRight} disabled={!serverType || status !== "ready"} />;
  } else {
    next = (
      <Button
        label="Create workspace"
        type="submit"
        icon={ArrowRight}
        busy={pending}
        disabled={!provider || !apiKey.trim() || rejected}
      />
    );
  }

  return (
    <Modal
      title="New workspace"
      description={onNameChosen ? "Name it, connect OpenRouter, then confirm the free trial machine." : "Name it, choose the VM it runs on, then connect the model provider its agent will use."}
      onClose={close}
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
                discardDraft();
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
