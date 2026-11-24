import { ArrowRight, Check, Sparkles } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { finishTour, loadTrialMachines, type VmSize } from "../../hooks/useDemoTour";
import { useAiConnectors } from "../../hooks/useAiConnectors";
import { AiProviderNotice } from "../molecules/AiProviderNotice";
import { navigate, useRoute } from "../../hooks/useRoute";
import type { CreateResult, DraftResult, NewWorkspace, Workspace } from "../../hooks/useWorkspaces";
import {
  setTourProgress, subscribeTourActions, TOUR_DESIGN_PROMPT, TOUR_PUBLIC_PROMPT, TOUR_RECIPE_ID,
  TOUR_DOCS_URL, TOUR_STEP as S, TOUR_STEP_COUNT,
  TOUR_WORKSPACE_NAME, type TourProgress, typeInto, useChatBusy, useChatCheckpoint, useTourProgress,
} from "../../lib/tour";
import { Button, Spinner } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules/Modal";
import { timeLeft } from "../molecules/DemoBanner";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly loading: boolean;
  readonly onDraft: (workspace: Omit<NewWorkspace, "apiKey">) => Promise<DraftResult>;
  readonly onLaunch: (id: string, apiKey: string, connectorId?: string) => Promise<CreateResult>;
  readonly onExit?: () => void;
}
const isOneOf = (step: number, ...steps: number[]) => steps.includes(step);
const control = <T extends HTMLElement = HTMLElement,>(selector: string): T | null => document.querySelector<T>(selector);

export function DemoTour(props: Props) {
  const progress = useTourProgress();
  const [completed, setCompleted] = useState<Workspace | null>(null);
  return <>
    {progress && <Tour {...props} progress={progress} onComplete={() => {
      setCompleted(props.workspaces.find((workspace) => workspace.id === progress.workspaceId) ?? null);
    }} />}
    {completed && <Modal title="Tutorial completed" onClose={() => setCompleted(null)}>
      <ModalBody>
        <p role="status">You have finished the tour! You created a public page from a recipe and changed it through chat. Keep asking the assistant for what you want to build.</p>
        <p>A domain gives your website a memorable address instead of a machine IP. If you own a domain at GoDaddy, try the recipe below to connect it and set up HTTPS. It guides the assistant through DNS, the records that point your domain to this workspace, and checks that your site works.</p>
        <a href="/store/00000000-0000-4000-8000-00000000b00b" className="text-left font-medium text-emerald-700 underline underline-offset-4 dark:text-emerald-400" onClick={(event) => {
          if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          setCompleted(null);
          navigate({ name: "recipe", recipeId: "00000000-0000-4000-8000-00000000b00b" });
        }}>GoDaddy domains for your workspace</a>
        <a href={TOUR_DOCS_URL} target="_blank" rel="noreferrer" className="font-medium underline underline-offset-4">Read the Docs: workspaces, recipes, secrets, variables and MCP</a>
        <p className="text-sm text-zinc-500">This trial and its website, files and tasks are permanently deleted{completed.expiresAt ? ` on ${new Date(completed.expiresAt).toLocaleString()}` : " after four hours"}. For a domain you want to keep using, create a paid workspace and set it up there.</p>
      </ModalBody>
      <ModalFooter><Button label="Keep exploring" onClick={() => setCompleted(null)} /></ModalFooter>
    </Modal>}
  </>;
}

function Tour({ workspaces, loading, onDraft, onLaunch, onExit, progress, onComplete }: Props & { readonly progress: TourProgress; readonly onComplete: () => void }) {
  const { step, workspaceId, sessionId } = progress;
  const route = useRoute();
  const demo = workspaces.find((workspace) => workspace.id === workspaceId);
  const busy = useChatBusy(sessionId);
  const checkpoint = useChatCheckpoint(sessionId);
  const [working, setWorking] = useState(false);
  const [acting, setActing] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [sizes, setSizes] = useState<readonly VmSize[] | null>(null);
  const [consent, setConsent] = useState(false);
  const connectors = useAiConnectors();
  const trial = connectors.connectors.find(connector => connector.trial);
  const [accepted, setAccepted] = useState(false);
  const draft = useRef(progress.draftId);
  const active = useRef(true);
  const typing = useRef<AbortController | null>(null);
  const cheapest = sizes?.filter((size) => size.available).toSorted((a, b) => a.monthlyGross - b.monthlyGross || a.serverType.localeCompare(b.serverType))[0];
  const exit = () => { typing.current?.abort(); onExit?.(); setTourProgress(null); };
  // Skipping keeps the place, so the tour resumes there rather than with a second demo.
  const pause = () => { typing.current?.abort(); onExit?.(); setTourProgress({ ...progress, paused: true }); };
  const go = (next: number, extra: Partial<TourProgress> = {}) => {
    if (!active.current) return;
    setProblem(null); setNote(null);
    setTourProgress({ ...progress, step: next, ...extra });
  };
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; typing.current?.abort(); };
  }, []);
  useEffect(() => { setNote(null); setProblem(null); }, [step]);

  // Accepted mutations advance the guide; clicking a button or a failed request does not.
  useEffect(() => subscribeTourActions((action) => {
    if (action.workspaceId && workspaceId && action.workspaceId !== workspaceId) return;
    if (action.type === "workspace-form-opened" && step === S.WELCOME) go(S.NAME);
    if (action.type === "workspace-form-closed" && step === S.NAME) go(S.WELCOME);
    if (action.type === "workspace-named" && step === S.NAME) go(S.OPENROUTER, { name: action.name });
    if (action.type === "store-scope" && step === S.PRIVATE && action.value === "private") go(S.OWN);
    if (action.type === "store-scope" && step === S.PUBLIC && action.value === "public") go(S.FIND_RECIPE);
    if (action.type === "recipe-apply-opened" && (step === S.REVIEW || step === S.APPLY)) go(S.PICK);
    if (action.type === "message-sent" && action.sessionId === sessionId) {
      if (step === S.BUILD || step === S.CONFIRM_PUBLIC) go(S.DEPLOY);
      if (step === S.DESIGN) go(S.DONE);
    }
  }), [step, workspaceId, sessionId, progress]);

  // Real navigation also works when the user clicks without the guide's Next button.
  useEffect(() => {
    if (step === S.STORE && route.name === "store") go(S.PRIVATE);
    if (step === S.FIND_RECIPE && route.name === "recipe" && route.recipeId === TOUR_RECIPE_ID) go(S.REVIEW);
    if (step === S.PICK && route.name === "session" && route.workspaceId === workspaceId) go(S.BUILD, { sessionId: route.sessionId });
  }, [route, step, workspaceId, sessionId]);

  // Reopen transient app forms after reload. These controls do not save anything themselves.
  useEffect(() => {
    const restore = () => {
      const pair = step === S.PICK ? ['[data-tour="workspace-picker-confirm"]', '[data-tour="recipe-apply"]'] : null;
      if (pair && !control(pair[0])) control<HTMLButtonElement>(pair[1])?.click();
    };
    restore(); const timer = setInterval(restore, 250);
    return () => clearInterval(timer);
  }, [step]);

  // Restore the relevant screen on reload without replacing normal user navigation on each render.
  useEffect(() => {
    if (workspaceId && isOneOf(step, S.BUILD, S.CONFIRM_PUBLIC, S.DEPLOY, S.VERIFY, S.DESIGN, S.DONE) && sessionId) {
      navigate({ name: "session", workspaceId, sessionId });
    } else if (workspaceId && step === S.STARTING) navigate({ name: "workspace", workspaceId });
  }, []);

  useEffect(() => {
    if (loading || !workspaceId || demo) return;
    const timer = setTimeout(() => exit(), 3000);
    return () => clearTimeout(timer);
  }, [loading, workspaceId, demo]);

  const prepareTrial = async () => {
    if (!trial || !accepted) return;
    setWorking(true);
    try {
      await connectors.acknowledge(trial.id);
      if (!draft.current) {
        const result = await onDraft({ name: progress.name ?? TOUR_WORKSPACE_NAME, serverType: "", modelProvider: "openrouter", demo: true });
        if (typeof result !== "object") { setProblem("The free trial could not be prepared. It may be unavailable or already used by this account."); return; }
        draft.current = result.id;
      }
      go(S.MACHINE, { draftId: draft.current });
    } catch { setProblem("Could not prepare your trial connector. Please try again."); }
    finally { setWorking(false); }
  };

  const loadSizes = () => void loadTrialMachines().then(setSizes).catch(() => setProblem("Could not load machines. Click Refresh machines to retry."));
  useEffect(() => { if (step === S.MACHINE) loadSizes(); }, [step]);
  const launch = async () => {
    if (!trial || !draft.current) { go(S.OPENROUTER); return; }
    if (!consent || !cheapest) return;
    setWorking(true);
    try {
      const result = await onLaunch(draft.current, "", trial.id);
      if (result === "created") { go(S.STARTING, { workspaceId: draft.current }); navigate({ name: "workspace", workspaceId: draft.current }); }
      else if (result === "rejected") { go(S.OPENROUTER); setProblem("The trial key is unavailable. Check your Trial connector in General settings."); }
      else { loadSizes(); setProblem("The trial could not start. Refresh machines and try again. No paid workspace is created."); }
    } catch { setProblem("Could not start the trial. Try again."); }
    finally { setWorking(false); }
  };

  const click = (selector: string) => {
    const element = control<HTMLButtonElement>(selector);
    if (!element || element.closest("[hidden]")) { setProblem("The highlighted control is still loading, or you moved to another page. Return to this step and try again."); return false; }
    if (element.disabled) { setProblem("Complete the highlighted form, then use its button or Next."); return false; }
    element.click(); return true;
  };
  const write = async (field: HTMLTextAreaElement | HTMLInputElement, text: string) => {
    if (!active.current) return false;
    typing.current?.abort(); typing.current = new AbortController();
    setWorking(true);
    const completed = await typeInto(field, text, field.type === "password" ? 0 : 18, typing.current.signal);
    if (active.current) { setWorking(false); setNote(completed ? "Example entered. Edit it if you like, then use the highlighted button or Next." : "Typing stopped so you can keep your own changes."); }
    return completed;
  };
  const fillThenSubmit = async (selector: string, fallback: string, submit: string) => {
    const field = control<HTMLInputElement | HTMLTextAreaElement>(selector);
    if (!field) { setProblem("The form is still loading. Try Next again in a moment."); return; }
    if (!field.value.trim()) { await write(field, fallback); return; }
    click(submit);
  };
  const message = async (example: string, send: boolean) => {
    const field = control<HTMLTextAreaElement>('textarea[aria-label="Message"]');
    if (!field || field.disabled) { setProblem("The conversation is still loading. Try again in a moment."); return; }
    if (!field.value.trim()) { await write(field, example); return; }
    if (send) click('button[aria-label="Send"]');

  };
  const finish = async () => {
    if (!workspaceId) return;
    setWorking(true); const saved = await finishTour(workspaceId);
    if (!active.current) return;
    setWorking(false);
    if (saved) { onComplete(); exit(); } else setProblem("Could not save completion. Click Finish tutorial to retry.");
  };

  const workspaceReady = demo?.status === "READY";
  const until = demo?.expiresAt ? Date.parse(demo.expiresAt) : null;
  let card: Card;
  switch (step) {
    case S.WELCOME: card = { title: "Your own workspace, step by step", target: '[data-tour="new-workspace"]',
      body: <>A workspace is your own computer in the cloud. It keeps running your apps and scheduled tasks when you close the browser. Your AI assistant can install software, change files and fix problems there: describe what you want in chat, without needing to know the commands. Just click <strong>Next</strong> to demonstrate each step, or use the highlighted controls yourself. We will build a simple public web page on a free four-hour machine; your personal Trial connector covers the AI usage to get started, with no automatic refill.</>, next: () => click('[data-tour="new-workspace"]') }; break;
    case S.NAME: card = { title: "Give your workspace a name", target: "#workspace-name",
      body: <>Type your own name for the project, or click Next and watch us enter <strong>{TOUR_WORKSPACE_NAME}</strong>. You can edit the example before continuing with the real form's Next button.</>, next: () => fillThenSubmit("#workspace-name", TOUR_WORKSPACE_NAME, '[data-tour="workspace-name-form"] button[type="submit"]') }; break;
    case S.OPENROUTER: card = { title: "Your trial AI token",
      body: <>Your account has its own Trial connector with a small one-time AI budget. It is your protected default and cannot be deleted. Review the AI-provider notice before continuing.</>, disabled: !trial || !accepted || connectors.loading, next: prepareTrial }; break;
    case S.MACHINE: card = { title: "Choose the machine for this trial",
      body: <>Your workspace runs on a computer in the cloud, called a virtual machine (VM). You use it from your browser, and it can keep working when your own computer is off. Its size determines how much it can do: CPU provides processing power, memory (RAM) gives your apps room to run together, and disk is the space for your files. For this free trial, we have selected the lowest-cost available size. The other sizes are shown for comparison. To continue after the trial, create a separate paid workspace.</>, disabled: !trial || !consent || !cheapest, label: "Create free workspace", next: launch }; break;
    case S.STARTING: card = { title: workspaceReady ? "Your workspace is ready" : "Your workspace is starting", ready: workspaceReady, target: '[data-tour="demo-banner"]', body: workspaceReady ? <>Your cloud computer is running and your assistant is ready. Click <strong>Next</strong> to open the recipe Store and start building your first page. The banner shows when your free trial ends and the workspace will be deleted.</> : <>We are switching on your cloud computer and getting your assistant ready. This usually takes 30–60 seconds, sometimes a little longer. There is nothing you need to do while you wait. Next becomes available when your workspace is ready. The banner shows exactly when your free trial ends and the workspace will be deleted.</>, waiting: workspaceReady ? undefined : "Preparing the workspace…", next: () => go(S.STORE) }; break;
    case S.STORE: card = { title: "Open the real recipe Store", target: '[data-tour="open-store"]', body: <>A recipe is a reusable set of instructions for the assistant, such as installing a blog or connecting a domain. It saves you explaining the same setup from scratch. Recipes can contain any instructions, including running software, changing files or connecting accounts. Read the full recipe and check who published it before applying. Only apply it if you understand and trust what it asks the assistant to do. Open Store in the top bar; Next clicks that same link.</>, next: () => click('[data-tour="open-store"]') }; break;
    case S.PRIVATE: card = { title: "Recipes can belong just to you", target: '[role="tab"][data-tab="private"]', body: <>Select Private to see recipes only you can use. You can keep your own setup instructions here and reuse them in another workspace.</>, next: () => click('[role="tab"][data-tab="private"]') }; break;
    case S.OWN: card = { title: "You can write or import your own", target: '[data-tour="new-recipe"]', body: <>New recipe lets you write instructions or import a Markdown file. Choose private for your own use or public to share it. We will use a published recipe today.</>, next: () => go(S.PUBLIC) }; break;
    case S.PUBLIC: card = { title: "See what people have shared", target: '[role="tab"][data-tab="public"]', body: <>Public is the community recipe collection: reusable setups shared by other people. You can browse, apply and rate them. Next opens it.</>, next: () => click('[role="tab"][data-tab="public"]') }; break;
    case S.FIND_RECIPE: card = { title: "Find Simple web page", target: '[aria-label="Search recipes"]', body: <>Search for <strong>Simple web page</strong> by workspaced. This welcoming demo gives everyone the same starting page. Next enters the search, then opens its card.</>, next: async () => { const field = control<HTMLInputElement>('[aria-label="Search recipes"]'); if (field && field.value !== "Simple web page") await write(field, "Simple web page"); else if (control(`[data-recipe="${TOUR_RECIPE_ID}"]`)) click(`[data-recipe="${TOUR_RECIPE_ID}"]`); else setProblem("Waiting for Simple web page to appear. Try again in a moment."); } }; break;
    case S.REVIEW: card = { title: "Read what the recipe will do", target: '[data-tour="recipe-overview"]', body: <>This recipe builds “Demo page from workspaced” with a ready-made design you can make your own. It asks you in chat before making the page <strong>PUBLIC</strong>, so people can open it from the internet. It then gives you an HTTP link using your machine’s IP address. Read its overview below before applying.</>, next: () => go(S.APPLY) }; break;
    case S.APPLY: card = { title: "Use the actual Apply button", target: '[data-tour="recipe-apply"]', body: <>Apply asks which workspace should run the recipe. It starts a normal conversation; nothing is deployed merely by reading the recipe.</>, next: () => click('[data-tour="recipe-apply"]') }; break;
    case S.PICK: card = { title: "Apply it in your trial workspace", target: '[data-tour="workspace-picker-confirm"]', body: <>Select <strong>{demo?.name}</strong>, then click the highlighted Apply button. Next selects that workspace if needed and uses the same button. This really applies the recipe and opens its session. No credentials are entered here.</>, next: () => { const radio = control<HTMLInputElement>(`[data-workspace-choice="${workspaceId}"]`); if (radio && !radio.checked) { radio.click(); return; } click('[data-tour="workspace-picker-confirm"]'); } }; break;
    case S.BUILD: card = { title: "Watch the recipe start in chat", target: 'section[aria-label="Chat"]', body: <>The assistant prepares your page and asks whether to make it PUBLIC. This means anyone on the internet can open it. You can give permission right here in chat.</>, waiting: busy === false && checkpoint === "public" ? undefined : "Waiting for the assistant to ask about public access…", next: () => go(S.CONFIRM_PUBLIC) }; break;
    case S.CONFIRM_PUBLIC: card = { title: "Ask the assistant to make it public", target: 'textarea[aria-label="Message"]', body: <>Say “Yes, make this page public so anyone can open it from the internet.” The assistant handles publishing and gives you a link. Next types an example; review it, then Send or Next again.</>, next: () => message(TOUR_PUBLIC_PROMPT, true) }; break;
    case S.DEPLOY: card = { title: "Watch your page go live", target: 'section[aria-label="Chat"]', body: <>The assistant gets the page running and checks its public address. It will put the clickable HTTP address in its reply. If anything fails, ask it to fix the problem here.</>, waiting: busy === false ? undefined : "Waiting for the assistant to finish…", next: () => go(S.VERIFY) }; break;
    case S.VERIFY: card = { title: "Open your public page", target: 'section[aria-label="Chat"]', body: <>Open the <strong>http://machine-ip</strong> link in the assistant’s reply in a new tab. You should see “Demo page from workspaced”. Anyone with the link can open it; HTTP is unencrypted. If it does not load, ask the assistant to fix it before continuing.</>, label: "My page works", next: () => go(S.DESIGN) }; break;
    case S.DESIGN: card = { title: "Your turn: ask for any change", target: 'textarea[aria-label="Message"]', body: <>What would you like to change on this page? Ask for new text, different colours, another section or anything you want to try. Type your own request and Send. Next can enter an example for you to edit, then send it on the next click.</>, next: () => message(TOUR_DESIGN_PROMPT, true) }; break;
    default: card = { title: "See your change, then keep exploring", target: 'section[aria-label="Chat"]', body: <>Wait for the assistant to finish, then reload your public page to see the change. You can keep improving it through chat. Finish for the optional GoDaddy domain recipe and a link to the Docs. {until ? <>Your trial has <strong>{timeLeft(until, Date.now())}</strong> left.</> : "The trial lasts four hours."} Then the workspace and its data are permanently deleted.</>, waiting: busy === false ? undefined : "Waiting for the assistant to finish your change…", label: "Finish tutorial", next: finish };

  }

  const custom = step === S.OPENROUTER || step === S.MACHINE;
  const customContent = step === S.OPENROUTER ? <div className="space-y-5"><h2 className="text-xl font-semibold">Trial AI connector</h2><label className="block text-sm font-medium">AI connector<select aria-label="Trial AI connector" value={trial?.id ?? ""} disabled className="mt-2 block w-full rounded-lg border border-zinc-300 bg-transparent p-3 dark:border-zinc-700"><option value={trial?.id ?? ""}>{trial ? "Trial" : connectors.loading ? "Preparing your Trial connector…" : "Trial connector unavailable"}</option></select></label>{!trial && !connectors.loading && <p role="status" className="text-sm text-zinc-500">Your trial AI key is not available yet. Refresh this page to retry. You can also manage your own connectors in General settings.</p>}{connectors.error && <p role="alert" className="text-sm text-rose-600">{connectors.error}</p>}<AiProviderNotice accepted={accepted} onChange={setAccepted} disabled={!trial || working} /></div> : <div className="space-y-4"><h2 className="text-xl font-semibold">{progress.name ?? TOUR_WORKSPACE_NAME}</h2><fieldset className="space-y-2"><legend className="mb-2 text-sm font-medium">Trial machine</legend>{sizes?.map((size) => <label key={size.serverType} className={`flex items-start gap-3 rounded-lg border p-3 ${size.serverType === cheapest?.serverType ? "border-amber-400 bg-amber-50 dark:bg-amber-950/30" : "border-zinc-200 opacity-60 dark:border-zinc-700"}`}><input type="radio" name="trial-machine" aria-label={size.serverType} checked={size.serverType === cheapest?.serverType} disabled={size.serverType !== cheapest?.serverType} readOnly className="mt-1 accent-amber-600" /><span className="min-w-0 break-words text-sm"><strong>{size.plan}</strong> · {size.cores} CPUs · {size.memoryGb} GB RAM · {size.diskGb} GB disk · {size.serverType}<span className="block text-xs">{size.serverType === cheapest?.serverType ? "Selected · free for four hours" : size.available ? "Unavailable in this free trial" : "Unavailable from the provider"}</span></span></label>)}</fieldset><Button label="Refresh machines" variant="secondary" onClick={loadSizes} /><label className="flex cursor-pointer items-start gap-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm leading-6 dark:border-amber-800 dark:bg-amber-950/30"><input type="checkbox" checked={consent} onChange={(event) => setConsent(event.target.checked)} className="mt-1 size-5 shrink-0 accent-amber-600" /><span>I understand this workspace, its website, files, sessions, secrets and scheduled tasks will be <strong>permanently deleted after four hours</strong>. I will not use it for production or anything I need to keep.</span></label>{!sizes && <Spinner label="Loading machines" />}{sizes && !cheapest && <p role="status">No trial machine is available. Refresh to check again.</p>}</div>;
  const runNext = async () => {
    if (acting || working) return;
    setActing(true);
    try { await card.next(); }
    catch { if (active.current) setProblem("Could not complete this step. Check the highlighted control and try again."); }
    finally { if (active.current) setActing(false); }
  };
  return <TourFrame step={step} card={{ ...card, next: runNext }} working={working || acting} problem={problem} note={note} onSkip={pause} custom={custom ? customContent : undefined} />;
}

interface Card {
  readonly title: string;
  readonly body: ReactNode;
  readonly target?: string;
  readonly waiting?: string;
  readonly ready?: boolean;
  readonly disabled?: boolean;
  readonly label?: string;
  readonly next: () => unknown | Promise<unknown>;
}
function chapter(step: number): string {
  return step <= S.STARTING ? "Your workspace" : step <= S.PICK ? "The recipe Store" : step <= S.VERIFY ? "Your public page" : "Your turn";
}
function TourFrame({ step, card, working, problem, note, onSkip, custom }: {
  readonly step: number; readonly card: Card; readonly working: boolean; readonly problem: string | null;
  readonly note: string | null; readonly onSkip: () => void; readonly custom?: ReactNode;
}) {
  const header = useRef<HTMLElement>(null);
  const footer = useRef<HTMLElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const rect = useTargetRect(card.target);
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.guidedTour = "true";
    const measure = () => {
      root.style.setProperty("--tour-header", `${header.current?.getBoundingClientRect().height || 160}px`);
      root.style.setProperty("--tour-footer", `${footer.current?.getBoundingClientRect().height || 80}px`);
    };
    measure();
    const observer = typeof ResizeObserver !== "undefined" ? new ResizeObserver(measure) : null;
    if (header.current) observer?.observe(header.current);
    if (footer.current) observer?.observe(footer.current);
    globalThis.addEventListener("resize", measure);
    return () => { observer?.disconnect(); globalThis.removeEventListener("resize", measure); root.style.removeProperty("--tour-header"); root.style.removeProperty("--tour-footer"); delete root.dataset.guidedTour; };
  }, []);
  useEffect(() => {
    const focused = document.activeElement;
    if (focused === document.body || !focused?.isConnected) heading.current?.focus({ preventScroll: true });
  }, [step]);
  return <>
    <header ref={header} role="region" aria-label="Guided tour" className="fixed inset-x-0 top-0 z-[90] border-b border-zinc-200 bg-white px-4 pt-[max(0.75rem,env(safe-area-inset-top))] pb-3 shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
      <div className="mx-auto max-w-5xl space-y-2"><div className="flex items-center justify-between gap-3 text-xs font-medium text-zinc-500"><span className="flex items-center gap-2"><Sparkles aria-hidden className="size-4 text-amber-500" />Guided tour · {chapter(step)}</span><span className="tabular-nums">{step + 1}/{TOUR_STEP_COUNT}</span></div><h1 ref={heading} tabIndex={-1} className="text-lg font-semibold outline-none sm:text-xl" aria-live="polite">{card.title}</h1><div className="max-h-[min(20dvh,7.5rem)] overflow-y-auto text-sm leading-5 [@media(max-height:650px)]:max-h-16 text-zinc-600 dark:text-zinc-400">{card.body}</div><div role="progressbar" aria-label="Tutorial progress" aria-valuemin={1} aria-valuemax={TOUR_STEP_COUNT} aria-valuenow={step + 1} className="h-1 overflow-hidden rounded bg-zinc-100 dark:bg-zinc-800"><div className="h-full rounded bg-amber-500 transition-[width] motion-reduce:transition-none" style={{ width: `${(step + 1) / TOUR_STEP_COUNT * 100}%` }} /></div></div>
    </header>
    {custom ? <section aria-label="Trial setup" className="fixed inset-x-0 z-50 overflow-y-auto bg-zinc-50 p-4 sm:p-6 dark:bg-zinc-950" style={{ top: "var(--tour-header, 160px)", bottom: "var(--tour-footer, 80px)" }}><div className="mx-auto max-w-lg rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">{custom}</div></section> : rect && <div aria-hidden className="pointer-events-none fixed z-[80] rounded-lg border-2 border-amber-400 transition-all motion-reduce:transition-none" style={{ top: Math.max(rect.top - 4, header.current?.getBoundingClientRect().bottom ?? 0), left: Math.max(2, rect.left - 4), width: Math.min(rect.width + 8, globalThis.innerWidth - 4), height: Math.max(0, Math.min(rect.bottom + 4, footer.current?.getBoundingClientRect().top ?? globalThis.innerHeight) - Math.max(rect.top - 4, header.current?.getBoundingClientRect().bottom ?? 0)), boxShadow: "0 0 0 200vmax rgb(9 9 11 / 0.24)" }} />}
    <footer ref={footer} role="region" aria-label="Guided tour controls" className="fixed inset-x-0 bottom-0 z-[90] border-t border-zinc-200 bg-white px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3"><div className="min-w-0 basis-full text-xs leading-5 sm:min-w-40 sm:flex-1 sm:basis-auto">{problem ? <p role="alert" className="text-rose-600 dark:text-rose-400">{problem}</p> : card.waiting ? <p role="status" className="flex items-center gap-2 text-zinc-500"><Spinner className="size-3.5" />{card.waiting}</p> : card.ready ? <p role="status" className="flex items-center gap-2 font-medium text-amber-700 dark:text-amber-400"><Check aria-hidden className="size-4 shrink-0" />Your workspace is ready. Click Next to continue.</p> : <p className="text-zinc-500">{note ?? (card.target ? "Use the highlighted control, or let Next demonstrate it." : "Continue when you are ready.")}</p>}</div><button type="button" onClick={onSkip} className="min-h-11 px-2 text-sm text-zinc-500 underline-offset-2 hover:underline">Skip tour</button><div className={`relative flex-1 sm:flex-none [&>button]:min-h-11 [&>button]:w-full ${card.ready ? "tour-ready-next" : ""}`}><Button label={card.label ?? "Next"} icon={step === S.DONE ? Check : ArrowRight} busy={working} disabled={!!card.waiting || card.disabled} onClick={() => { void card.next(); }} /></div></div>
    </footer>
  </>;
}
function useTargetRect(selector: string | undefined): DOMRect | null {
  const [rect, setRect] = useState<DOMRect | null>(null);
  useEffect(() => {
    setRect(null);
    if (!selector) return;
    let scrolled = false;
    const measure = () => {
      const element = control(selector);
      if (element && !element.closest("[hidden]") && !scrolled) { scrolled = true; element.scrollIntoView?.({ block: "center", inline: "nearest", behavior: "instant" }); }
      const next = element?.getBoundingClientRect() ?? null;
      setRect((current) => current && next && current.top === next.top && current.left === next.left && current.width === next.width && current.height === next.height ? current : next);
    };
    measure(); const timer = setInterval(measure, 200);
    globalThis.addEventListener("resize", measure);
    return () => { clearInterval(timer); globalThis.removeEventListener("resize", measure); };
  }, [selector]);
  return rect;
}
