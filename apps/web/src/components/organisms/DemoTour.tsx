import { ArrowRight, Sparkles } from "lucide-react";
import { type KeyboardEvent as ReactKeyboardEvent, type ReactNode, useEffect, useRef, useState } from "react";
import { addTrialSecret, applyTourRecipe, isTrialReady, loadTrialMachines, loadTrialSecretNames, restartTrialWorkspace, scheduleTourTask, type VmSize } from "../../hooks/useDemoTour";
import { useOpenRouterConnect } from "../../hooks/useOpenRouterConnect";
import { navigate } from "../../hooks/useRoute";
import type { CreateResult, DraftResult, NewWorkspace, Workspace } from "../../hooks/useWorkspaces";
import { setTourProgress, TOUR_DESIGN_PROMPT, TOUR_RECIPE_ID, TOUR_TASK, type TourProgress, typeInto, useChatBusy, useTourProgress } from "../../lib/tour";
import { Button, Spinner, TextField } from "../atoms";
import { timeLeft } from "../molecules/DemoBanner";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly loading: boolean;
  readonly onDraft: (workspace: Omit<NewWorkspace, "apiKey">) => Promise<DraftResult>;
  readonly onLaunch: (id: string, apiKey: string) => Promise<CreateResult>;
}

const STEP = { WELCOME: 0, OPENROUTER: 1, BOX: 2, STARTING: 3, RECIPE: 4, SECRETS: 5, RESTART: 6, BUILDING: 7, VERIFY: 8, SCHEDULE: 9, TASKS: 10, DESIGN: 11, DONE: 12 } as const;
const STEPS = 13;
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const SECRET_USER = "WORDPRESS_ADMIN_USER";
const SECRET_PASSWORD = "WORDPRESS_ADMIN_PASSWORD";
const SECRET_DATABASE = "WORDPRESS_DB_PASSWORD";

/** A guided creation flow followed by the real recipe, secrets, chat and task screens. */
export function DemoTour(props: Props) {
  const progress = useTourProgress();
  return progress ? <Tour {...props} progress={progress} /> : null;
}

function Tour({ workspaces, loading, onDraft, onLaunch, progress }: Props & { readonly progress: TourProgress }) {
  const { step, workspaceId, sessionId } = progress;
  const demo = workspaces.find((w) => w.id === workspaceId) ?? null;
  const busy = useChatBusy(sessionId);
  const [working, setWorking] = useState(false);
  const [trialConsent, setTrialConsent] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [typed, setTyped] = useState("");
  const draftId = useRef(progress.draftId);
  // Keys and credentials stay in memory only, never in the persisted tour progress.
  const key = useRef<string | null>(null);
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const [sizes, setSizes] = useState<readonly VmSize[] | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [savedSecrets, setSavedSecrets] = useState<readonly string[] | null>(null);
  const [restarted, setRestarted] = useState(false);
  const cheapest = sizes?.filter((size) => size.available).toSorted((a, b) => a.monthlyGross - b.monthlyGross || a.serverType.localeCompare(b.serverType))[0];

  const go = (next: Partial<TourProgress>) => {
    if (!active.current) return;
    setProblem(null);
    setTourProgress({ ...progress, ...next });
  };

  const openRouter = useOpenRouterConnect((apiKey) => {
    key.current = apiKey;
    go({ step: STEP.BOX, draftId: draftId.current });
  });

  const keyLabel = async (): Promise<string | null> => {
    if (!draftId.current) {
      const result = await onDraft({ name: "My WordPress trial", serverType: "", modelProvider: "openrouter", demo: true }).catch((): DraftResult => "failed");
      if (typeof result !== "object") {
        setProblem("The free trial could not be prepared. It may be unavailable, or this account has already used its trial. Try again later.");
        return null;
      }
      draftId.current = result.id;
      go({ draftId: result.id });
    }
    return `workspaced trial: ${draftId.current}`;
  };

  const loadSizes = () => {
    setProblem(null);
    void loadTrialMachines().then(setSizes).catch(() => setProblem("Machine sizes could not be loaded. Click Refresh machines to retry."));
  };
  useEffect(() => {
    if (step === STEP.BOX) loadSizes();
  }, [step]);

  useEffect(() => {
    if (step === STEP.STARTING && workspaceId) navigate({ name: "workspace", workspaceId });
    if (step === STEP.RECIPE) navigate({ name: "recipe", recipeId: TOUR_RECIPE_ID });
    if ((step === STEP.SECRETS || step === STEP.RESTART) && workspaceId) navigate({ name: "settings", workspaceId, section: "secrets" });
    if ((step === STEP.BUILDING || step === STEP.VERIFY || step === STEP.DESIGN || step === STEP.DONE) && workspaceId && sessionId) navigate({ name: "session", workspaceId, sessionId });
    if ((step === STEP.SCHEDULE || step === STEP.TASKS) && workspaceId) navigate({ name: "settings", workspaceId, section: "tasks" });
  }, [step, workspaceId, sessionId]);

  useEffect(() => {
    if (!loading && workspaceId && !demo) setTourProgress(null);
  }, [loading, workspaceId, demo]);

  const checkSecrets = () => {
    if (!workspaceId) return;
    setProblem(null);
    void loadTrialSecretNames(workspaceId).then((secrets) => {
      if (active.current) setSavedSecrets(secrets);
    }).catch(() => { if (active.current) setProblem("Could not check secrets. Click Check secrets again to retry."); });
  };

  useEffect(() => {
    if (step !== STEP.SECRETS || !workspaceId) return;
    let live = true;
    void loadTrialSecretNames(workspaceId).then((secrets) => {
      if (live) setSavedSecrets(secrets);
    }).catch(() => { if (live) setProblem("Could not check secrets. Click Check secrets again to retry."); });
    return () => { live = false; };
  }, [step, workspaceId]);

  // A fresh list confirms the restart has completed, including after a page reload.
  useEffect(() => {
    if (step !== STEP.RESTART || !workspaceId) return;
    let live = true;
    const check = () => void isTrialReady(workspaceId).then((ready) => {
      if (live) setRestarted(ready);
    }).catch(() => { /* A reboot can briefly disconnect the workspace; keep waiting. */ });
    check();
    const timer = setInterval(check, 2500);
    return () => { live = false; clearInterval(timer); };
  }, [step, workspaceId]);

  const launch = async () => {
    if (!key.current || !draftId.current) { go({ step: STEP.OPENROUTER }); return; }
    if (!trialConsent) return;
    setWorking(true);
    try {
      const result = await onLaunch(draftId.current, key.current);
      if (!active.current) return;
      if (result === "created") {
        key.current = null;
        go({ step: STEP.STARTING, workspaceId: draftId.current });
      } else if (result === "rejected") {
        key.current = null;
        go({ step: STEP.OPENROUTER });
        setProblem("OpenRouter did not accept the connection. Connect again and check your OpenRouter credit.");
      } else {
        loadSizes();
        setProblem("Your trial could not start. Refresh the machines and try again; no paid workspace is created.");
      }
    } catch { setProblem("Your trial could not start. Try again in a moment."); }
    finally { setWorking(false); }
  };

  const saveSecrets = async () => {
    if (!workspaceId || !savedSecrets) return;
    setWorking(true);
    try {
      if (!savedSecrets.includes(SECRET_USER)) {
        await addTrialSecret(workspaceId, SECRET_USER, username.trim());
        setSavedSecrets((names) => [...(names ?? []), SECRET_USER]);
        setUsername("");
      }
      if (!savedSecrets.includes(SECRET_PASSWORD)) {
        await addTrialSecret(workspaceId, SECRET_PASSWORD, password);
        setSavedSecrets((names) => [...(names ?? []), SECRET_PASSWORD]);
        setPassword("");
      }
      if (!savedSecrets.includes(SECRET_DATABASE)) {
        const bytes = crypto.getRandomValues(new Uint8Array(32));
        await addTrialSecret(workspaceId, SECRET_DATABASE, Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(""));
        setSavedSecrets((names) => [...(names ?? []), SECRET_DATABASE]);
      }
      await restartTrialWorkspace(workspaceId);
      go({ step: STEP.RESTART });
    } catch { setProblem("Could not save the credentials or restart. Saved secrets are kept; click Next to retry the remaining step."); }
    finally { setWorking(false); }
  };

  const applyRecipe = async () => {
    if (!workspaceId) return;
    setWorking(true);
    const session = await applyTourRecipe(workspaceId);
    setWorking(false);
    if (session) go({ step: STEP.BUILDING, sessionId: session });
    else setProblem("The recipe could not be applied. Click Next to try again.");
  };

  const schedule = async () => {
    if (!workspaceId || !demo) return;
    setWorking(true);
    const text = `Every 5 minutes: ${TOUR_TASK.prompt}`;
    for (let length = 4; length < text.length + 4; length += 4) {
      if (!active.current) return;
      setTyped(text.slice(0, length));
      await sleep(16);
    }
    const saved = await scheduleTourTask(workspaceId, demo.timeZone);
    setWorking(false);
    if (saved) go({ step: STEP.TASKS });
    else setProblem("The task could not be saved. Click Next to try again.");
  };

  const prepareDesign = async () => {
    const field = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message"]');
    if (!field || field.disabled) { setProblem("The chat is still loading. Click Next again in a moment."); return; }
    if (field.value.trim()) { go({ step: STEP.DONE }); return; }
    setWorking(true);
    const complete = await typeInto(field, TOUR_DESIGN_PROMPT);
    setWorking(false);
    if (complete) go({ step: STEP.DONE });
    else setProblem("The chat moved while typing. Click Next to try again.");
  };

  const until = demo?.expiresAt ? Date.parse(demo.expiresAt) : null;
  const clock = until ? new Date(until).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "the end of the trial";
  const savedUser = savedSecrets?.includes(SECRET_USER);
  const savedPassword = savedSecrets?.includes(SECRET_PASSWORD);
  let card: CardContent;
  switch (step) {
    case STEP.WELCOME:
      card = {
        title: "Welcome to workspaced",
        body: <><p>A <strong>workspace</strong> is your own computer in the cloud, with its own files, apps and AI agent. It keeps your project separate from other work and lets your website and scheduled jobs run while your browser is closed.</p><p>Just click <strong>Next</strong> as we guide you: connect OpenRouter, choose a machine, apply a WordPress recipe, publish a blog and schedule news. At the end, you try changing its design.</p><p>The VM is free for <strong>4 hours</strong>, then it and its data are deleted. You use your own OpenRouter account for AI usage, which OpenRouter bills separately. No platform top-up or card is needed for this trial.</p></>,
        onNext: () => go({ step: STEP.OPENROUTER }),
      };
      break;
    case STEP.OPENROUTER:
      card = {
        title: "Connect your OpenRouter account",
        body: <><p>Your workspace runs the tools; an AI model decides what to do. <strong>OpenRouter</strong> connects it to models through your own account. AI usage is charged by OpenRouter, even during the free VM trial.</p><p>Click Next, sign in or create an OpenRouter account, and approve access. Keep credit in that account to run the agent. You do not need to copy an API key.</p></>,
        waiting: openRouter.status === "exchanging" ? "Connecting OpenRouter…" : undefined,
        note: openRouter.status === "waiting" ? "Finish in the OpenRouter window. If you closed it, click Next to reopen it." : openRouter.status === "blocked" ? "Allow pop-ups for this site, then click Next." : openRouter.status === "failed" ? "The connection did not complete. Click Next to retry." : undefined,
        onNext: () => openRouter.connect(keyLabel),
      };
      break;
    case STEP.BOX:
      card = {
        title: "Choose your trial machine",
        body: <><p>The machine supplies CPU, memory and disk for WordPress and your agent. We selected the <strong>cheapest available machine</strong>. It is the single choice enabled for this trial; larger sizes are available when creating a paid workspace.</p><fieldset data-tour="trial-machines" className="max-h-[40dvh] space-y-2 overflow-y-auto"><legend className="mb-2 text-xs font-medium">Machine sizes</legend>{sizes?.map((size) => <label key={size.serverType} className={`flex items-start gap-3 rounded-lg border p-3 ${size.serverType === cheapest?.serverType ? "border-amber-400 bg-amber-50 dark:bg-amber-950/30" : "border-zinc-200 opacity-60 dark:border-zinc-700"}`}><input type="radio" name="trial-machine" checked={size.serverType === cheapest?.serverType} disabled={size.serverType !== cheapest?.serverType} readOnly aria-label={size.serverType} className="mt-1 accent-amber-600" /><span className="min-w-0 break-words"><strong>{size.serverType}</strong> · {size.cores} CPUs · {size.memoryGb} GB RAM · {size.diskGb} GB disk<span className="block text-xs">{size.serverType === cheapest?.serverType ? "Selected · free for 4 hours" : !size.available ? "Unavailable from the provider" : "Unavailable in the free trial"}</span></span></label>)}</fieldset><Button label="Refresh machines" variant="secondary" onClick={loadSizes} /><p>{key.current ? 'Your OpenRouter connection is ready.' : 'Reconnect OpenRouter after reloading this page.'} Next creates a fresh workspace named <strong>My WordPress trial</strong>. It will never turn into a paid workspace automatically.</p><label className="flex cursor-pointer items-start gap-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/30"><input type="checkbox" checked={trialConsent} onChange={(event) => setTrialConsent(event.target.checked)} className="mt-1 size-5 shrink-0 accent-amber-600" /><span>I understand this trial workspace and its website, files, sessions, secrets and scheduled tasks will be <strong>permanently deleted after 4 hours</strong>. I will not use it for production or anything I need to keep.</span></label></>,
        waiting: !key.current || cheapest ? undefined : sizes ? "No trial machine is available. Refresh to try again." : "Loading machines…",
        disabled: !!key.current && !trialConsent,
        nextLabel: key.current ? "Create free workspace" : "Reconnect OpenRouter",
        onNext: launch,
      };
      break;
    case STEP.STARTING:
      card = {
        title: "Your workspace is starting",
        target: '[data-tour="demo-banner"]',
        body: <><p>We are preparing your machine and agent. The countdown shows how long you have before the trial and its data are deleted.</p><p>Once ready, you can use Sessions to talk to the agent, Settings for secrets and automation, or Terminal to run commands yourself.</p></>,
        waiting: demo?.status === "READY" ? undefined : "Starting your workspace…",
        onNext: () => go({ step: STEP.RECIPE }),
      };
      break;
    case STEP.RECIPE:
      card = {
        title: "Apply the WordPress recipe",
        target: 'section[aria-label="Steps"]',
        body: <><p>A <strong>recipe</strong> is a reusable set of instructions published in the Store. This WordPress recipe by <strong>Pavel Usanli</strong> installs the blog, publishes it at your machine's IP address, and records how to maintain it.</p><p>Read the highlighted steps. Applying this recipe makes the blog public over HTTPS with a trusted certificate for your machine's IP address. Next collects the admin credentials as secrets, then applies it in a new session.</p></>,
        nextLabel: "Apply recipe",
        onNext: () => go({ step: STEP.SECRETS }),
      };
      break;
    case STEP.SECRETS:
      card = {
        title: "Choose your WordPress admin credentials",
        body: <><p><strong>Secrets</strong> pass private values to your workspace without putting them into chat or recipe text. Choose a username and a unique password; keep them in your password manager.</p><p>We save them as <code>{SECRET_USER}</code> and <code>{SECRET_PASSWORD}</code>. We also generate a separate database password as a secret. Then we restart the workspace so the agent can use them.</p><TextField id="tour-admin-user" label="WordPress admin username" autoComplete="username" value={savedUser ? "Saved as a secret" : username} disabled={working || !!savedUser} maxLength={60} onChange={(event) => setUsername(event.target.value)} /><TextField id="tour-admin-password" label="WordPress admin password" type="password" autoComplete="new-password" value={savedPassword ? "saved-secret" : password} disabled={working || !!savedPassword} maxLength={4096} hint="Use a unique password with at least 12 characters." onChange={(event) => setPassword(event.target.value)} />{savedSecrets === null && <Button label="Check secrets again" variant="secondary" onClick={checkSecrets} />}<p>Your blog and admin sign-in use HTTPS. Use these credentials only for this temporary blog.</p></>,
        waiting: savedSecrets && (savedUser || /^[A-Za-z0-9_.@-]{1,60}$/.test(username.trim())) && (savedPassword || password.length >= 12) ? undefined : "Enter a username and a password of at least 12 characters.",
        nextLabel: "Save secrets and restart",
        onNext: saveSecrets,
      };
      break;
    case STEP.RESTART:
      card = {
        title: "Make the secrets available",
        target: 'section[aria-label="Secrets"]',
        body: <><p>Settings lists secret <strong>names</strong>, never their saved values. The restart loads them into the agent's environment.</p><p>Next starts a new session and hands the recipe to the agent. Your credentials are referenced by name, never pasted into its prompt.</p></>,
        waiting: restarted ? undefined : "Waiting for the workspace to finish restarting…",
        onNext: applyRecipe,
      };
      break;
    case STEP.BUILDING:
      card = {
        title: "Watch the agent build your blog",
        target: 'section[aria-label="Chat"]',
        body: <><p>A <strong>session</strong> is a conversation about work on this machine. Here the agent installs WordPress and its database, sets up HTTPS on port 443, and writes the first post. It can take several minutes.</p><p>It will reply with the blog's public IP address and admin link. If it reports a problem, ask it to fix that in this chat before continuing.</p></>,
        waiting: busy === false ? undefined : "Waiting for the agent's reply… You can use the chat below.",
        onNext: () => go({ step: STEP.VERIFY }),
      };
      break;
    case STEP.VERIFY:
      card = {
        title: "Open your public WordPress blog",
        target: 'section[aria-label="Chat"]',
        body: <><p>Open the <strong>https://machine-ip</strong> link in the agent's reply in a new tab. Anyone with that address can visit the blog while the trial runs. A domain is optional.</p><p>Check the home page and first post. If they do not work, ask the agent to fix them here. Continue once you have checked the site.</p></>,
        nextLabel: "My blog works",
        onNext: () => go({ step: STEP.SCHEDULE }),
      };
      break;
    case STEP.SCHEDULE:
      card = {
        title: "Publish news on a schedule",
        target: 'section[aria-label="Tasks"]',
        body: <><p>A <strong>task</strong> is a saved prompt that starts a new agent session on a schedule. We will add <strong>tech-news</strong>, which checks a current technology news feed and publishes a short summary with a source link.</p><p>For this short trial it runs <strong>every 5 minutes</strong> in {demo?.timeZone ?? "your workspace's time zone"}. Each run uses your OpenRouter credit. It skips stories already covered and stops when the workspace is deleted. You can delete it sooner in Tasks.</p>{typed && <p aria-label="Task being added" className="rounded-lg bg-zinc-100 p-3 font-mono text-xs leading-5 dark:bg-zinc-800">{typed}</p>}</>,
        nextLabel: "Create news task",
        onNext: schedule,
      };
      break;
    case STEP.TASKS:
      card = {
        title: "Your news task is scheduled",
        target: 'nav[aria-label="Tasks"]',
        body: <><p>The task is saved. <code>*/5 * * * *</code> means every five minutes. Each run appears under <strong>Sessions → Scheduled</strong>. Wait for a run, then reload your blog to see a new post if the feed has a fresh story.</p><p>You can change its prompt or schedule here, or delete it to stop further AI usage. Next takes us back to the blog session for your turn.</p></>,
        onNext: () => go({ step: STEP.DESIGN }),
      };
      break;
    case STEP.DESIGN:
      card = {
        title: "Your turn: change the design",
        target: 'textarea[aria-label="Message"]',
        body: <><p>Describe the look you want in plain words. We will type an example into the message box. <strong>Edit it however you like and send it yourself.</strong></p><p>Try your favourite colours, different headings or a new layout. After the agent replies, reload the blog to see your changes.</p></>,
        nextLabel: "Type an example for me",
        onNext: prepareDesign,
      };
      break;
    default:
      card = {
        title: "Edit your request, then press Send",
        target: 'textarea[aria-label="Message"]',
        body: <><p>The example is ready below. Make it yours, send it, and keep exploring. Your blog and scheduled task share the workspace's files, so later sessions can build on this work.</p><p>{until ? <>You have <strong>{timeLeft(until, Date.now())}</strong>, until {clock}.</> : <>The trial lasts four hours.</>} Then the machine, blog, sessions and secrets are deleted. We remind you 30 minutes before. A permanent workspace is created separately from your funded account.</p></>,
        nextLabel: "Finish tutorial",
        onNext: () => setTourProgress(null),
      };
  }

  return <TourCard step={step} card={card} working={working} problem={problem} onSkip={() => setTourProgress(null)} />;
}

interface CardContent {
  readonly title: string;
  readonly body: ReactNode;
  /** A selector for what the step is about; without one the card fills the screen. */
  readonly target?: string;
  /** Set while Next has to wait, saying for what. */
  readonly waiting?: string;
  readonly disabled?: boolean;
  readonly note?: string;
  readonly nextLabel?: string;
  readonly onNext: () => void | Promise<void>;
}

function TourCard({ step, card, working, problem, onSkip }: {
  readonly step: number;
  readonly card: CardContent;
  readonly working: boolean;
  readonly problem: string | null;
  readonly onSkip: () => void;
}) {
  const rect = useTargetRect(card.target);
  const dialog = useRef<HTMLElement>(null);
  useEffect(() => {
    const node = dialog.current;
    node?.focus({ preventScroll: true });
    if (node) node.scrollTop = 0;
  }, [step]);
  const onKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key === "Escape") { event.stopPropagation(); onSkip(); return; }
    if (card.target || event.key !== "Tab" || !dialog.current) return;
    const controls = [...dialog.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), a[href]')];
    const first = controls.at(0);
    const last = controls.at(-1);
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) {
      event.preventDefault(); last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first?.focus();
    }
  };
  const content = (
    <>
      <div className="flex items-center gap-2 text-xs font-medium text-zinc-500 dark:text-zinc-400">
        <Sparkles aria-hidden className="size-3.5 text-amber-500" />
        <span>Guided tour · {step + 1} of {STEPS}</span>
      </div>
      <div className="flex gap-1" role="progressbar" aria-label="Tutorial progress" aria-valuemin={1} aria-valuemax={STEPS} aria-valuenow={step + 1}>
        {Array.from({ length: STEPS }, (_, index) => (
          <span key={index} className={`h-1 flex-1 rounded-full ${index <= step ? "bg-amber-500" : "bg-zinc-200 dark:bg-zinc-700"}`} />
        ))}
      </div>
      <h2 className="text-lg font-semibold">{card.title}</h2>
      <div className="flex flex-col gap-3 text-sm leading-6 text-zinc-700 dark:text-zinc-300">{card.body}</div>
      {card.note && <p className="text-sm text-amber-700 dark:text-amber-400">{card.note}</p>}
      {problem && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{problem}</p>}
      <div className="mt-1 flex flex-col-reverse gap-3 sm:flex-row sm:items-center">
        <button type="button" onClick={onSkip} className="text-xs text-zinc-500 underline-offset-2 hover:underline dark:text-zinc-400">Skip tour</button>
        <span className="flex flex-col gap-3 sm:ml-auto sm:items-end">
          {card.waiting && (
            <span role="status" className="flex items-center gap-2 text-xs text-zinc-500 dark:text-zinc-400">
              <Spinner label={card.waiting} className="size-3.5" />
              {card.waiting}
            </span>
          )}
          <Button label={card.nextLabel ?? "Next"} icon={ArrowRight} busy={working} disabled={card.waiting !== undefined || card.disabled}
            onClick={() => void card.onNext()} />
        </span>
      </div>
    </>
  );

  if (!card.target) {
    return (
      <div className="fixed inset-0 z-[60] flex items-stretch justify-center bg-zinc-950/70 backdrop-blur-sm sm:items-center sm:p-6">
        <section ref={dialog} tabIndex={-1} onKeyDown={onKeyDown} role="dialog" aria-modal="true" aria-label={card.title}
          className="outline-none [&_button]:min-h-11 sm:max-h-[calc(100dvh-3rem)] flex max-h-[100dvh] w-full flex-col gap-4 overflow-y-auto bg-white px-5 pt-[max(1.5rem,env(safe-area-inset-top))] pb-[max(1.5rem,env(safe-area-inset-bottom))] shadow-xl sm:max-w-lg sm:rounded-2xl sm:p-7 dark:bg-zinc-900">
          {content}
        </section>
      </div>
    );
  }

  // The card keeps clear of what it points at: above it when it sits low on the screen.
  const low = rect !== null && rect.top + rect.height / 2 > globalThis.innerHeight / 2;
  return (
    <>
      <div aria-hidden className="pointer-events-none fixed inset-0 z-[60]">
        {rect ? (
          <div className="absolute rounded-xl ring-2 ring-amber-400 transition-all duration-300"
            style={{ top: rect.top - 6, left: rect.left - 6, width: rect.width + 12, height: rect.height + 12, boxShadow: "0 0 0 200vmax rgb(9 9 11 / 0.45)" }} />
        ) : (
          <div className="absolute inset-0 bg-zinc-950/45" />
        )}
      </div>
      <section ref={dialog} tabIndex={-1} onKeyDown={onKeyDown} role="dialog" aria-label={card.title}
        className={`outline-none [&_button]:min-h-11 fixed inset-x-0 z-[61] flex max-h-[60dvh] flex-col gap-3 overflow-y-auto border-zinc-200 bg-white p-5 shadow-2xl sm:inset-x-auto sm:right-6 sm:w-[26rem] sm:rounded-2xl sm:border dark:border-zinc-800 dark:bg-zinc-900 ${
          low
            ? "top-0 rounded-b-2xl pt-[max(1.25rem,env(safe-area-inset-top))] sm:top-20"
            : "bottom-0 rounded-t-2xl pb-[max(1.25rem,env(safe-area-inset-bottom))] sm:bottom-6"
        }`}>
        {content}
      </section>
    </>
  );
}

/** Where the target is on screen, followed as the page lays itself out; null while it is absent. */
function useTargetRect(selector: string | undefined): DOMRect | null {
  const [rect, setRect] = useState<DOMRect | null>(null);
  useEffect(() => {
    setRect(null);
    if (!selector) return undefined;
    let scrolled = false;
    const measure = () => {
      const element = document.querySelector(selector);
      if (element && !scrolled) {
        scrolled = true;
        element.scrollIntoView?.({ block: "center", behavior: "smooth" });
      }
      const next = element?.getBoundingClientRect() ?? null;
      setRect((current) =>
        current && next && current.top === next.top && current.left === next.left && current.width === next.width && current.height === next.height
          ? current
          : next,
      );
    };
    measure();
    const timer = setInterval(measure, 250);
    globalThis.addEventListener("resize", measure);
    return () => {
      clearInterval(timer);
      globalThis.removeEventListener("resize", measure);
    };
  }, [selector]);
  return rect;
}
