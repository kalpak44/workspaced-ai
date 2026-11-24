import { ArrowRight, Check, ExternalLink, Heart, Sparkles } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { performTourAction, useTourInteraction } from "../../hooks/useTourInteraction";
import { finishTour, loadTrialMachines, type VmSize } from "../../hooks/useDemoTour";
import { useAiConnectors } from "../../hooks/useAiConnectors";
import { AiProviderNotice } from "../molecules/AiProviderNotice";
import { navigate, useRoute } from "../../hooks/useRoute";
import type { CreateResult, DraftResult, NewWorkspace, Workspace } from "../../hooks/useWorkspaces";
import {
  setTourProgress, subscribeTourActions, TOUR_DESIGN_IDEAS, TOUR_DESIGN_PROMPT, TOUR_PUBLIC_PROMPT, TOUR_RECIPE_ID,
  TOUR_STEP as S, TOUR_WORKSPACE_NAME, type TourProgress, typeInto, useChatBusy, useChatCheckpoint,
  useChatPageLink, useChatPublicQuestion, useTourChatIssue, useTourProgress,
} from "../../lib/tour";
import { Button, Confetti, Spinner } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules/Modal";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly loading: boolean;
  readonly onDraft: (workspace: Omit<NewWorkspace, "apiKey">) => Promise<DraftResult>;
  readonly onLaunch: (id: string, apiKey: string, connectorId?: string) => Promise<CreateResult>;
  readonly onExit?: () => void;
  /** Opens keeping the demo as a workspace of the user's own. */
  readonly onKeep?: (workspaceId: string) => void;
}

// The steps a tour walks through today. The Store's Private, Own and Public steps are skipped, but
// keep their numbers, so a tour paused on one still resumes and recorded steps stay comparable.
const FLOW: readonly number[] = [S.WELCOME, S.NAME, S.OPENROUTER, S.MACHINE, S.STARTING, S.STORE, S.FIND_RECIPE, S.REVIEW, S.APPLY, S.PICK,
  S.BUILD, S.CONFIRM_PUBLIC, S.DEPLOY, S.VERIFY, S.DESIGN, S.DONE];
const CHAPTERS = [
  { name: "Your workspace", last: S.STARTING },
  { name: "A recipe", last: S.PICK },
  { name: "Your page goes live", last: S.VERIFY },
  { name: "Make it yours", last: S.DONE },
] as const;
/** Where the step sits in today's walk; a skipped Store step counts as the Store. */
const position = (step: number) => Math.max(FLOW.indexOf(step), step < S.FIND_RECIPE ? FLOW.indexOf(S.STORE) : 0);
const isOneOf = (step: number, ...steps: number[]) => steps.includes(step);
const control = <T extends HTMLElement = HTMLElement,>(selector: string): T | null => document.querySelector<T>(selector);
/** The control once a lazily loaded page has drawn it; null if it has not within a few seconds. */
async function appeared<T extends HTMLElement = HTMLElement>(selector: string): Promise<T | null> {
  for (let waited = 0; waited < 5000; waited += 100) {
    const element = control<T>(selector);
    if (element) return element;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return null;
}

/** What the tour just did, so the end of it reads as an achievement rather than a goodbye. */
const ACHIEVEMENTS = ["Switched on your own computer in the cloud", "Gave your assistant a recipe", "Put a web page on the internet", "Changed it just by asking"];

function TourComplete({ workspace, onKeep, onClose }: { readonly workspace: Workspace; readonly onKeep?: () => void; readonly onClose: () => void }) {
  const until = workspace.expiresAt ? new Date(workspace.expiresAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : null;
  return <>
    <Confetti />
    <Modal title="You built a website!" onClose={onClose}>
      <ModalBody>
        <p role="status" className="text-zinc-700 dark:text-zinc-300">In a few minutes, without writing a line of code, you:</p>
        <ul aria-label="What you did" className="space-y-2">
          {ACHIEVEMENTS.map((done) => <li key={done} className="flex items-start gap-2 text-sm"><span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300"><Check aria-hidden className="size-3.5" strokeWidth={3} /></span>{done}</li>)}
        </ul>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Imagine what you could build next: a shop page, a booking form, a blog, a tool for your team. Just ask.
          {onKeep && <> {workspace.name} is free{until ? ` until ${until}` : " for a few hours"}. Keep it, and carry on exactly where you are.</>}
        </p>
        <a href="/store/00000000-0000-4000-8000-00000000b00b" className="text-sm font-medium text-emerald-700 underline underline-offset-4 dark:text-emerald-400" onClick={(event) => {
          if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          onClose();
          navigate({ name: "recipe", recipeId: "00000000-0000-4000-8000-00000000b00b" });
        }}>Next: put it on your own domain</a>
      </ModalBody>
      <ModalFooter>
        <Button label="Keep exploring" variant={onKeep ? "secondary" : "primary"} onClick={onClose} />
        {onKeep && <Button label="Keep this workspace" icon={Heart} onClick={onKeep} />}
      </ModalFooter>
    </Modal>
  </>;
}

export function DemoTour(props: Props) {
  const progress = useTourProgress();
  const [completed, setCompleted] = useState<Workspace | null>(null);
  return <>
    {progress && <Tour {...props} progress={progress} onComplete={() => {
      setCompleted(props.workspaces.find((workspace) => workspace.id === progress.workspaceId) ?? null);
    }} />}
    {completed && <TourComplete workspace={completed} onClose={() => setCompleted(null)}
      onKeep={props.onKeep && completed.demo ? () => { setCompleted(null); props.onKeep?.(completed.id); } : undefined} />}
  </>;
}

function Tour({ workspaces, loading, onDraft, onLaunch, onExit, progress, onComplete }: Props & { readonly progress: TourProgress; readonly onComplete: () => void }) {
  const { step, workspaceId, sessionId } = progress;
  const route = useRoute();
  useTourInteraction(step, workspaceId);
  const demo = workspaces.find((workspace) => workspace.id === workspaceId);
  const busy = useChatBusy(sessionId);
  const checkpoint = useChatCheckpoint(sessionId);
  const publicQuestion = useChatPublicQuestion(sessionId);
  const pageLink = useChatPageLink(sessionId);
  const chatIssue = useTourChatIssue(sessionId);
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
    if (action.type === "public-confirmed" && action.sessionId === sessionId && step === S.CONFIRM_PUBLIC) go(S.DEPLOY);
    if (action.type === "message-sent" && action.sessionId === sessionId) {
      if (step === S.CONFIRM_PUBLIC) go(S.DEPLOY);
      if (step === S.DESIGN) go(S.DONE);
    }
  }), [step, workspaceId, sessionId, progress]);

  // Real navigation also works when the user clicks without the guide's Next button.
  useEffect(() => {
    if (step === S.STORE && route.name === "store") go(S.FIND_RECIPE);
    if (step === S.FIND_RECIPE && route.name === "recipe" && route.recipeId === TOUR_RECIPE_ID) go(S.REVIEW);
    if (step === S.PICK && route.name === "session" && route.workspaceId === workspaceId) go(S.BUILD, { sessionId: route.sessionId });
  }, [route, step, workspaceId, sessionId]);

  // Reopen transient app forms after reload. These controls do not save anything themselves.
  useEffect(() => {
    const restore = () => {
      const pair = step === S.PICK ? ['[data-tour="workspace-picker-confirm"]', '[data-tour="recipe-apply"]'] : null;
      if (pair && !control(pair[0])) performTourAction(() => control<HTMLButtonElement>(pair[1])?.click());
    };
    restore(); const timer = setInterval(restore, 250);
    return () => clearInterval(timer);
  }, [step]);

  // Reloads, Back and direct URLs return to the screen belonging to this lesson.
  useEffect(() => {
    if (workspaceId && isOneOf(step, S.BUILD, S.CONFIRM_PUBLIC, S.DEPLOY, S.VERIFY, S.DESIGN, S.DONE) && sessionId) {
      navigate({ name: "session", workspaceId, sessionId }, { replace: true });
    } else if (isOneOf(step, S.REVIEW, S.APPLY, S.PICK)) {
      // Applying the recipe navigates before its accepted action advances the step.
      if (step === S.PICK && route.name === "session" && route.workspaceId === workspaceId) return;
      navigate({ name: "recipe", recipeId: TOUR_RECIPE_ID }, { replace: true });
    } else if (isOneOf(step, S.PRIVATE, S.OWN, S.PUBLIC, S.FIND_RECIPE)) {
      if (step === S.FIND_RECIPE && route.name === "recipe" && route.recipeId === TOUR_RECIPE_ID) return;
      navigate({ name: "store" }, { replace: true });
    } else if (step === S.STORE && route.name === "store") return;
    else if (workspaceId && isOneOf(step, S.STARTING, S.STORE)) navigate({ name: "workspace", workspaceId }, { replace: true });
    else if (step <= S.MACHINE) navigate({ name: "home" }, { replace: true });
  }, [step, workspaceId, sessionId, route]);

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

  const loadSizes = () => {
    setProblem(null);
    void loadTrialMachines().then(setSizes).catch(() => { setSizes([]); setProblem("Could not load machines. Click Try again to retry."); });
  };
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
    performTourAction(() => element.click()); return true;
  };
  const write = async (field: HTMLTextAreaElement | HTMLInputElement, text: string) => {
    if (!active.current) return false;
    typing.current?.abort();
    const controller = new AbortController();
    typing.current = controller;
    setWorking(true);
    const completed = await typeInto(field, text, field.type === "password" ? 0 : 18, controller.signal);
    if (active.current && typing.current === controller) { setWorking(false); setNote(completed ? "Example entered. Click Next to continue." : "Typing stopped. Click Next to try again."); }
    return completed;
  };
  const fillThenSubmit = async (selector: string, fallback: string, submit: string) => {
    const field = control<HTMLInputElement | HTMLTextAreaElement>(selector);
    if (!field) { setProblem("The form is still loading. Try Next again in a moment."); return; }
    if (field.value !== fallback) { await write(field, fallback); return; }
    click(submit);
  };
  const message = async (example: string, send: boolean) => {
    const field = control<HTMLTextAreaElement>('textarea[aria-label="Message"]');
    if (!field || field.disabled) { setProblem("The conversation is still loading. Try again in a moment."); return; }
    if ((!send && field.value !== example) || (field.value !== example && !(step === S.DESIGN && TOUR_DESIGN_IDEAS.includes(field.value)))) { await write(field, example); return; }
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
  let card: Card;
  switch (step) {
    case S.WELCOME: card = { title: "Let's build your first website together", target: '[data-tour="new-workspace"]',
      body: <>In about five minutes you will have your own computer in the cloud, a web page anyone can visit, and an AI assistant that changes it whenever you ask. No technical skills needed: click <strong>Next</strong> and we do each step with you using guided examples. It is free, AI included.</>, next: () => click('[data-tour="new-workspace"]') }; break;
    case S.NAME: card = { title: "What shall we call it?", target: "#workspace-name",
      body: <>Click <strong>Next</strong> to enter <strong>{TOUR_WORKSPACE_NAME}</strong>, then Next again to continue. You can rename it after the tour.</>, next: () => fillThenSubmit("#workspace-name", TOUR_WORKSPACE_NAME, '[data-tour="workspace-name-form"] button[type="submit"]') }; break;
    case S.OPENROUTER: card = { title: "Your assistant comes with free AI credit",
      body: <>No account to create anywhere: we have set some AI credit aside for you to try things out. Read the short note below and tick the box.</>, disabled: !trial || !accepted || connectors.loading, next: prepareTrial }; break;
    case S.MACHINE: card = { title: "Your free workspace is ready to switch on",
      body: <>We have picked a computer for you. It lives in the cloud, so it keeps working even when your laptop is closed. It is free for four hours. After the tour, you can choose to keep it and carry on building.</>, disabled: !trial || !consent || !cheapest, label: "Switch it on", next: launch }; break;
    case S.STARTING: card = { title: workspaceReady ? "It's on, and your assistant is awake" : "Switching it on", ready: workspaceReady, target: '[data-tour="demo-banner"]', body: workspaceReady ? <>Your own computer is running. Next, let's give your assistant something to make.</> : <>This takes about a minute. You can watch it start here. Keeping the workspace becomes available after you finish or skip the tour.</>, waiting: workspaceReady ? undefined : "Switching on your computer…", next: () => go(S.STORE) }; break;
    case S.STORE: card = { title: "Pick a recipe", target: '[data-tour="open-store"]', body: <>Recipes are ready-made instructions for your assistant: like a recipe in a cookbook, they tell it exactly what to make. Open the Store, or let <strong>Next</strong> do it.</>, next: () => click('[data-tour="open-store"]') }; break;
    case S.PRIVATE: card = { title: "Recipes can belong just to you", target: '[role="tab"][data-tab="private"]', body: <>Private holds recipes only you can use.</>, next: () => go(S.FIND_RECIPE) }; break;
    case S.OWN: card = { title: "You can write your own", target: '[data-tour="new-recipe"]', body: <>New recipe lets you write your own instructions.</>, next: () => go(S.FIND_RECIPE) }; break;
    case S.PUBLIC: card = { title: "Recipes other people shared", target: '[role="tab"][data-tab="public"]', body: <>Public holds the recipes everyone can use.</>, next: () => go(S.FIND_RECIPE) }; break;
    case S.FIND_RECIPE: card = { title: "Find “Simple web page”", target: '[aria-label="Search recipes"]', body: <>This recipe makes a friendly starter website for you to make your own. <strong>Next</strong> finds it.</>, next: async () => { const field = await appeared<HTMLInputElement>('[aria-label="Search recipes"]'); if (field && field.value !== "Simple web page") await write(field, "Simple web page"); else if (control(`[data-recipe="${TOUR_RECIPE_ID}"]`)) click(`[data-recipe="${TOUR_RECIPE_ID}"]`); else setProblem("Simple web page is still appearing. Try again in a moment."); } }; break;
    case S.REVIEW: card = { title: "Here's what it will make", target: '[data-tour="recipe-overview"]', body: <>A small web page, ready for you to change. Your assistant asks before putting it on the internet, then gives you a link to share.</>, next: () => go(S.APPLY) }; break;
    case S.APPLY: card = { title: "Hand it to your assistant", target: '[data-tour="recipe-apply"]', body: <>Click <strong>Apply</strong> to give the recipe to your assistant.</>, next: () => click('[data-tour="recipe-apply"]') }; break;
    case S.PICK: card = { title: "Choose your workspace", target: '[data-tour="workspace-picker-confirm"]', body: <>Pick <strong>{demo?.name}</strong> and click Apply. Your assistant gets to work straight away.</>, next: () => { const radio = control<HTMLInputElement>(`[data-workspace-choice="${workspaceId}"]`); if (radio && !radio.checked) { radio.click(); return; } click('[data-tour="workspace-picker-confirm"]'); } }; break;
    case S.BUILD: card = { title: "Your assistant is on it", target: 'section[aria-label="Chat"]', body: <>Watch it work in the chat. In a moment it asks whether your page may be seen by anyone on the internet.</>, waiting: busy === false && checkpoint === "public" ? undefined : "Your assistant is working…", next: () => go(S.CONFIRM_PUBLIC) }; break;
    case S.CONFIRM_PUBLIC: card = { title: "Say yes", target: publicQuestion ? '[data-tour="tour-public-question"]' : 'textarea[aria-label="Message"]', body: publicQuestion
      ? <>Your assistant asks in a form. The answer is set to Yes. Click <strong>Next</strong> to confirm public access: anyone can view this demo over unencrypted HTTP.</>
      : <>Tell it the page can go public. <strong>Next</strong> writes the answer for you; click Next again to send it.</>, next: () => publicQuestion ? click('[data-tour="tour-public-question"] button[type="submit"]') : message(TOUR_PUBLIC_PROMPT, true) }; break;
    case S.DEPLOY: card = { title: "Putting your page online", target: 'section[aria-label="Chat"]', body: <>Your assistant is setting everything up and checking it works. This usually takes a minute or two.</>, waiting: busy === false && pageLink ? undefined : busy === false ? "No public page address yet. Skip the tour to ask your assistant to retry publishing." : "Publishing your page…", next: () => go(S.VERIFY) }; break;
    case S.VERIFY: card = { title: "Your page is live!", target: 'section[aria-label="Chat"]', celebrate: true, body: pageLink
      ? <span className="flex flex-wrap items-center gap-x-3 gap-y-1"><a href={pageLink} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-1.5 font-semibold text-white hover:bg-emerald-700"><ExternalLink aria-hidden className="size-4" />Open my page</a>Anyone with this link can see it.</span>
      : <>Open the link in your assistant's reply to see it. Anyone with the link can see it.</>, label: "It works", next: () => go(S.DESIGN) }; break;
    case S.DESIGN: card = { title: "Now make it yours", target: 'textarea[aria-label="Message"]', body: <><span className="block">Click Next to enter an example change, or choose an idea. Next again sends it:</span><span className="mt-1.5 flex flex-wrap gap-1.5">{TOUR_DESIGN_IDEAS.map((idea) => <button key={idea} type="button" onClick={() => void message(idea, false)} className="rounded-full border border-amber-300 bg-amber-50 px-2.5 py-1 text-xs font-medium text-amber-900 hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-200">{idea}</button>)}</span></>, next: () => message(TOUR_DESIGN_PROMPT, true) }; break;
    default: card = { title: "Look again: that was you", target: 'section[aria-label="Chat"]', body: <>When your assistant finishes, refresh your page to see the change. You just changed a website by asking for it.</>, waiting: busy === false ? undefined : "Your assistant is making your change…", label: "Finish", next: finish };
  }

  if (chatIssue && step >= S.BUILD) card = { ...card, waiting: undefined, disabled: false, label: "Retry", next: chatIssue.retry };

  const custom = step === S.OPENROUTER || step === S.MACHINE;
  const customContent = step === S.OPENROUTER ? <div className="space-y-5"><h2 className="text-xl font-semibold">Trial AI connector</h2><label className="block text-sm font-medium">AI connector<select aria-label="Trial AI connector" value={trial?.id ?? ""} disabled className="mt-2 block w-full rounded-lg border border-zinc-300 bg-transparent p-3 dark:border-zinc-700"><option value={trial?.id ?? ""}>{trial ? "Trial" : connectors.loading ? "Preparing your Trial connector…" : "Trial connector unavailable"}</option></select></label>{!trial && !connectors.loading && <p role="status" className="text-sm text-zinc-500">Your trial AI key is not available yet. Refresh this page to retry. You can also manage your own connectors in General settings.</p>}{connectors.error && <p role="alert" className="text-sm text-rose-600">{connectors.error}</p>}<AiProviderNotice accepted={accepted} onChange={setAccepted} disabled={!trial || working} /></div> : <div className="space-y-4"><h2 className="text-xl font-semibold">{progress.name ?? TOUR_WORKSPACE_NAME}</h2>{cheapest ? <div className="flex items-center gap-3 rounded-lg border border-emerald-300 bg-emerald-50 p-3 text-sm dark:border-emerald-900 dark:bg-emerald-950/30"><Sparkles aria-hidden className="size-5 shrink-0 text-emerald-600" /><span><strong>Your own cloud computer</strong><span className="block text-xs text-zinc-600 dark:text-zinc-400">{cheapest.plan ?? cheapest.serverType} · free for four hours</span></span></div> : sizes ? <div className="space-y-2"><p role="status" className="text-sm">Every free computer is taken right now. Try again in a moment.</p><Button label="Try again" variant="secondary" onClick={loadSizes} /></div> : <Spinner label="Finding a computer for you" />}<label className="flex cursor-pointer items-start gap-3 rounded-lg border border-zinc-200 p-3 text-sm leading-6 dark:border-zinc-800"><input type="checkbox" checked={consent} onChange={(event) => setConsent(event.target.checked)} className="mt-1 size-5 shrink-0 accent-zinc-900 dark:accent-white" /><span>I understand this free trial lasts four hours, and that it and everything in it is deleted then unless I keep it.</span></label></div>;
  const runNext = async () => {
    if (acting || working) return;
    setActing(true);
    try { await card.next(); }
    catch { if (active.current) setProblem("Could not complete this step. Check the highlighted control and try again."); }
    finally { if (active.current) setActing(false); }
  };
  return <TourFrame step={step} card={{ ...card, next: runNext }} working={working || acting} problem={problem ?? chatIssue?.message ?? null} note={note} onSkip={pause} custom={custom ? customContent : undefined} />;
}

interface Card {
  readonly title: string;
  readonly body: ReactNode;
  readonly target?: string;
  readonly waiting?: string;
  readonly ready?: boolean;
  /** A moment worth celebrating, marked with confetti when the step opens. */
  readonly celebrate?: boolean;
  readonly disabled?: boolean;
  readonly label?: string;
  readonly next: () => unknown | Promise<unknown>;
}
/** The chapters as a row of named milestones, so the tour reads as four parts rather than many steps. */
function Chapters({ step }: { readonly step: number }) {
  const current = CHAPTERS.findIndex((chapter) => step <= chapter.last);
  return <ol aria-label="Tour chapters" className="flex flex-wrap items-center gap-x-3 gap-y-1">
    {CHAPTERS.map((chapter, index) => <li key={chapter.name} aria-current={index === current ? "step" : undefined}
      className={`flex items-center gap-1.5 ${index === current ? "text-zinc-900 dark:text-zinc-100" : index < current ? "text-emerald-700 dark:text-emerald-400" : ""}`}>
      {index < current ? <Check aria-hidden className="size-3.5" strokeWidth={3} /> : <span aria-hidden className={`size-1.5 rounded-full ${index === current ? "bg-amber-500" : "bg-zinc-300 dark:bg-zinc-600"}`} />}{chapter.name}</li>)}
  </ol>;
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
    {card.celebrate && <Confetti key={step} />}
    <header data-tour-surface ref={header} role="region" aria-label="Guided tour" className="fixed inset-x-0 top-0 z-[90] border-b border-zinc-200 bg-white px-4 pt-[max(0.75rem,env(safe-area-inset-top))] pb-3 shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
      <div className="mx-auto max-w-5xl space-y-2"><div className="flex items-center justify-between gap-3 text-xs font-medium text-zinc-500"><span className="flex min-w-0 items-center gap-2"><Sparkles aria-hidden className="size-4 shrink-0 text-amber-500" /><Chapters step={step} /></span></div><h1 ref={heading} tabIndex={-1} className="text-lg font-semibold outline-none sm:text-xl" aria-live="polite">{card.title}</h1><div className="max-h-[min(20dvh,7.5rem)] overflow-y-auto text-sm leading-5 [@media(max-height:650px)]:max-h-16 text-zinc-600 dark:text-zinc-400">{card.body}</div><div role="progressbar" aria-label="Tutorial progress" aria-valuemin={1} aria-valuemax={FLOW.length} aria-valuenow={position(step) + 1} className="h-1 overflow-hidden rounded bg-zinc-100 dark:bg-zinc-800"><div className="h-full rounded bg-amber-500 transition-[width] motion-reduce:transition-none" style={{ width: `${(position(step) + 1) / FLOW.length * 100}%` }} /></div></div>
    </header>
    {custom ? <section data-tour-surface aria-label="Trial setup" className="fixed inset-x-0 z-50 overflow-y-auto bg-zinc-50 p-4 sm:p-6 dark:bg-zinc-950" style={{ top: "var(--tour-header, 160px)", bottom: "var(--tour-footer, 80px)" }}><div className="mx-auto max-w-lg rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">{custom}</div></section> : rect && <div aria-hidden className="pointer-events-none fixed z-[80] rounded-lg border-2 border-amber-400 transition-all motion-reduce:transition-none" style={{ top: Math.max(rect.top - 4, header.current?.getBoundingClientRect().bottom ?? 0), left: Math.max(2, rect.left - 4), width: Math.min(rect.width + 8, globalThis.innerWidth - 4), height: Math.max(0, Math.min(rect.bottom + 4, footer.current?.getBoundingClientRect().top ?? globalThis.innerHeight) - Math.max(rect.top - 4, header.current?.getBoundingClientRect().bottom ?? 0)), boxShadow: "0 0 0 200vmax rgb(9 9 11 / 0.24)" }} />}
    <footer data-tour-surface ref={footer} role="region" aria-label="Guided tour controls" className="fixed inset-x-0 bottom-0 z-[90] border-t border-zinc-200 bg-white px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3"><div className="min-w-0 basis-full text-xs leading-5 sm:min-w-40 sm:flex-1 sm:basis-auto">{problem ? <p role="alert" className="text-rose-600 dark:text-rose-400">{problem}</p> : card.waiting ? <p role="status" className="flex items-center gap-2 text-zinc-500"><Spinner className="size-3.5" />{card.waiting}</p> : card.ready ? <p role="status" className="flex items-center gap-2 font-medium text-amber-700 dark:text-amber-400"><Check aria-hidden className="size-4 shrink-0" />Your workspace is ready. Click Next to continue.</p> : <p className="text-zinc-500">{note ?? (card.target ? "Click Next to follow this step. Tour text is filled in for you." : "Continue when you are ready.")}</p>}</div><button type="button" onClick={onSkip} className="min-h-11 px-2 text-sm text-zinc-500 underline-offset-2 hover:underline">Skip tour</button><div className={`relative flex-1 sm:flex-none [&>button]:min-h-11 [&>button]:w-full ${card.ready ? "tour-ready-next" : ""}`}><Button label={card.label ?? "Next"} icon={step === S.DONE || card.celebrate ? Check : ArrowRight} busy={working} disabled={!!card.waiting || card.disabled} onClick={() => { void card.next(); }} /></div></div>
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
