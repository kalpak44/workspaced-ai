import { Check, Search } from "lucide-react";
import { useMemo, useState } from "react";
import type { ModelCatalogue, ModelOption } from "../../hooks/useModelSettings";
import { formatPrice, formatTokens } from "../../lib/format";
import { Spinner } from "../atoms";
import { ErrorBanner, SettingsSection } from "../molecules";

interface Props {
  /** Null while there is no harness to ask, e.g. while the workspace starts. */
  readonly catalogue: ModelCatalogue | null;
  readonly current: string | null;
  readonly loading: boolean;
  readonly available: boolean;
  /** The choice a save is in flight for; undefined when none is. */
  readonly saving: string | null | undefined;
  readonly error: string | null;
  readonly onDismissError: () => void;
  readonly onChoose: (model: string | null) => void;
}

interface RowProps {
  readonly label: string;
  readonly detail: string;
  readonly meta?: string | null;
  readonly selected: boolean;
  readonly saving: boolean;
  readonly onChoose: () => void;
}

function Row({ label, detail, meta, selected, saving, onChoose }: RowProps) {
  return (
    <li>
      <button
        type="button"
        role="radio"
        aria-checked={selected}
        onClick={onChoose}
        className={`flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-zinc-50 dark:hover:bg-zinc-800/60 ${
          selected ? "bg-zinc-50 dark:bg-zinc-800/60" : ""
        }`}
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">{label}</span>
          <span className="block truncate font-mono text-xs text-zinc-500 dark:text-zinc-400">
            {detail}
          </span>
        </span>
        {meta && <span className="shrink-0 text-xs text-zinc-500 dark:text-zinc-400">{meta}</span>}
        <span className="flex size-4 shrink-0 items-center justify-center">
          {saving && <Spinner className="size-4 text-zinc-400" />}
          {!saving && selected && <Check aria-hidden className="size-4" />}
        </span>
      </button>
    </li>
  );
}

function metaOf(model: ModelOption): string | null {
  const parts = [
    model.contextLength === null ? null : `${formatTokens(model.contextLength)} context`,
    formatPrice(model.inputCost, model.outputCost),
  ].filter(Boolean);
  return parts.length ? parts.join(" · ") : null;
}

/** Hundreds of models: a filter first, then a short scrolling list with the choice marked. */
export function ModelPanel(props: Props) {
  const { catalogue, current, saving } = props;
  const [query, setQuery] = useState("");
  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const models = catalogue?.models ?? [];
    return needle
      ? models.filter((m) => `${m.name} ${m.id}`.toLowerCase().includes(needle))
      : models;
  }, [catalogue, query]);
  const defaultName =
    catalogue?.models.find((m) => m.id === catalogue.defaultModel)?.name ?? catalogue?.defaultModel;
  const chosen = catalogue?.models.find((m) => m.id === current);

  let body;
  if (!props.available) {
    body = (
      <p className="text-sm text-zinc-500 dark:text-zinc-400">
        The model list comes from the workspace's agent, so it shows once the workspace is running.
      </p>
    );
  } else if (props.loading) {
    body = <Spinner label="Loading models" className="size-5 text-zinc-400" />;
  } else if (catalogue) {
    body = (
      <>
        <label className="flex items-center gap-2 rounded-md border border-zinc-300 px-3 py-2 text-sm focus-within:border-zinc-500 dark:border-zinc-700 dark:focus-within:border-zinc-400">
          <Search aria-hidden className="size-4 text-zinc-400" />
          <input
            aria-label="Search models"
            placeholder={`Search ${catalogue.models.length} models`}
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
            className="flex-1 bg-transparent outline-none placeholder:text-zinc-400"
          />
        </label>
        <ul
          role="radiogroup"
          aria-label="Model"
          className="max-h-80 divide-y divide-zinc-100 overflow-y-auto rounded-md border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800"
        >
          {!query && (
            <Row
              label={`Default${defaultName ? ` — ${defaultName}` : ""}`}
              detail="Whatever the agent is set up with"
              selected={current === null}
              saving={saving === null}
              onChoose={() => props.onChoose(null)}
            />
          )}
          {matches.map((model) => (
            <Row
              key={model.id}
              label={model.name}
              detail={model.id}
              meta={metaOf(model)}
              selected={model.id === current}
              saving={saving === model.id}
              onChoose={() => props.onChoose(model.id)}
            />
          ))}
          {matches.length === 0 && (
            <li className="px-3 py-6 text-center text-sm text-zinc-500">No model matches “{query}”</li>
          )}
        </ul>
      </>
    );
  }

  return (
    <SettingsSection
      title="Model"
      description={
        <>
          What the agent replies with, from the next message on, in every session.
          {current && (
            <>
              {" "}
              Now: <span className="font-medium text-zinc-700 dark:text-zinc-200">{chosen?.name ?? current}</span>.
            </>
          )}
          {" "}Prices are per million input / output tokens.
        </>
      }
    >
      {props.error && <ErrorBanner message={props.error} onDismiss={props.onDismissError} />}
      {body}
    </SettingsSection>
  );
}
