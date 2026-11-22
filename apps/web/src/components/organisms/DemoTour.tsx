import { ArrowRight, Check, Sparkles } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { finishTour, isTrialReady, loadTrialMachines, loadTrialSecretNames, trialPortIsOpen, type VmSize } from "../../hooks/useDemoTour";
import { useAiConnectors } from "../../hooks/useAiConnectors";
import { AiProviderNotice } from "../molecules/AiProviderNotice";
import { navigate, useRoute } from "../../hooks/useRoute";
import type { CreateResult, DraftResult, NewWorkspace, Workspace } from "../../hooks/useWorkspaces";
import {
  setTourProgress, subscribeTourActions, TOUR_DESIGN_PROMPT, TOUR_PORT_CONTINUE, TOUR_RECIPE_ID,
  TOUR_SECRET_CONTINUE, TOUR_STEP as S, TOUR_STEP_COUNT, TOUR_STYLE, TOUR_STYLE_NAME, TOUR_TASK,
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
const SECRET_USER = "WORDPRESS_ADMIN_USER";
const SECRET_PASSWORD = "WORDPRESS_ADMIN_PASSWORD";
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
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
        <p role="status">You have finished the tour! You now know how to use sessions, recipes, secrets, public ports, scheduled tasks and shared instructions. You can ask the assistant to help with any of them in chat.</p>
        <p>A domain gives your website a memorable address instead of a machine IP. If you own a domain at GoDaddy, try the recipe below to connect it and set up HTTPS. It guides the assistant through DNS, the records that point your domain to this workspace, and checks that your site works.</p>
        <a href="/store/00000000-0000-4000-8000-00000000b00b" className="text-left font-medium text-emerald-700 underline underline-offset-4 dark:text-emerald-400" onClick={(event) => {
          if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          setCompleted(null);
          navigate({ name: "recipe", recipeId: "00000000-0000-4000-8000-00000000b00b" });
        }}>GoDaddy domains for your workspace</a>
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
  const [ready, setReady] = useState(false);
  const [checking, setChecking] = useState(false);
  const connectors = useAiConnectors();
  const trial = connectors.connectors.find(connector => connector.trial);
  const [accepted, setAccepted] = useState(false);
  const draft = useRef(progress.draftId);
  const active = useRef(true);
  const typing = useRef<AbortController | null>(null);
  const cheapest = sizes?.filter((size) => size.available).toSorted((a, b) => a.monthlyGross - b.monthlyGross || a.serverType.localeCompare(b.serverType))[0];
  const exit = () => { typing.current?.abort(); onExit?.(); setTourProgress(null); };
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
    if (action.type === "task-form-opened" && step === S.NEW_TASK) go(S.TASK_NAME);
    if (action.type === "instruction-form-opened" && step === S.NEW_INSTRUCTION) go(S.INSTRUCTION_NAME);
    if (action.type === "secret-saved") {
      if (step === S.ADMIN_USER && action.name === SECRET_USER) go(S.ADMIN_PASSWORD);
      if (step === S.ADMIN_PASSWORD && action.name === SECRET_PASSWORD) go(S.RESTART);
    }
    if (action.type === "restart-dialog-opened" && step === S.RESTART) go(S.CONFIRM_RESTART);
    if (action.type === "restart-cancelled" && step === S.CONFIRM_RESTART) go(S.RESTART);
    if (action.type === "restart-confirmed" && step === S.CONFIRM_RESTART) go(S.RESTARTING);
    if (action.type === "message-sent" && action.sessionId === sessionId) {
      if (step === S.RESUME_SECRETS) go(S.BUILD);
      if (step === S.RESUME_PORTS) go(S.DEPLOY);
    }
    if (action.type === "port-opened" && workspaceId && action.value === "tcp"
        && ((step === S.HTTP && action.name === "80") || (step === S.HTTPS && action.name === "443"))) {
      void trialPortIsOpen(workspaceId, action.name).then((open) => {
        if (open) go(step === S.HTTP ? S.HTTPS : S.PORT_REVIEW);
        else setProblem("This demo needs the port reachable from Anyone. Check the rule's From field.");
      }).catch(() => setProblem("Could not verify the saved firewall rule. Check the rules and retry."));
    }
    if (action.type === "task-created" && step === S.TASK_NAME) go(S.TASK_CONFIG, { taskName: action.name });
    if (action.type === "task-saved" && step === S.TASK_CONFIG && action.name === progress.taskName) go(S.TASK_SAVED);
    if (action.type === "instruction-created" && step === S.INSTRUCTION_NAME) go(S.INSTRUCTION_EDIT, { instructionName: action.name });
    if (action.type === "instruction-saved" && step === S.INSTRUCTION_EDIT && action.name === progress.instructionName) go(S.INSTRUCTION_SAVED);
  }), [step, workspaceId, sessionId, progress]);

  // Real navigation also works when the user clicks without the guide's Next button.
  useEffect(() => {
    const settings = route.name === "settings" && route.workspaceId === workspaceId;
    const sessions = route.name === "workspace" && route.workspaceId === workspaceId;
    const chat = route.name === "session" && route.workspaceId === workspaceId && route.sessionId === sessionId;
    if (step === S.STORE && route.name === "store") go(S.PRIVATE);
    if (step === S.FIND_RECIPE && route.name === "recipe" && route.recipeId === TOUR_RECIPE_ID) go(S.REVIEW);
    if (step === S.PICK && route.name === "session" && route.workspaceId === workspaceId) go(S.REQUEST_SECRETS, { sessionId: route.sessionId });
    if (settings && isOneOf(step, S.SETTINGS, S.PORT_SETTINGS, S.TASK_SETTINGS)) go(step === S.SETTINGS ? S.SECRETS : step === S.PORT_SETTINGS ? S.FIREWALL : S.TASKS);
    if (settings && step === S.SECRETS && route.section === "secrets") go(S.ADMIN_USER);
    if (settings && step === S.FIREWALL && route.section === "firewall") go(S.HTTP);
    if (settings && step === S.TASKS && route.section === "tasks") go(S.NEW_TASK);
    if (settings && step === S.INSTRUCTIONS && route.section === "instructions") go(S.BLOG_GUIDE);
    if (sessions && isOneOf(step, S.SESSIONS, S.PORT_SESSIONS, S.DESIGN_SESSIONS)) go(step + 1);
    if (chat && isOneOf(step, S.SESSION, S.PORT_SESSION, S.DESIGN_SESSION)) go(step + 1);
  }, [route, step, workspaceId, sessionId]);

  // Reopen transient app forms after reload. These controls do not save anything themselves.
  useEffect(() => {
    const restore = () => {
      const pair = step === S.PICK ? ['[data-tour="workspace-picker-confirm"]', '[data-tour="recipe-apply"]']
        : step === S.TASK_NAME ? ['#task-name', '[data-tour="new-task"]']
        : step === S.INSTRUCTION_NAME ? ['#instructions-name', '[data-tour="new-instruction"]']
        : step === S.CONFIRM_RESTART ? ['[data-tour="confirm-restart"]', '[data-tour="restart-workspace"]'] : null;
      if (pair && !control(pair[0])) control<HTMLButtonElement>(pair[1])?.click();
    };
    restore(); const timer = setInterval(restore, 250);
    return () => clearInterval(timer);
  }, [step]);

  // Restore the relevant screen on reload without replacing normal user navigation on each render.
  useEffect(() => {
    if (workspaceId && isOneOf(step, S.REQUEST_SECRETS, S.RESUME_SECRETS, S.BUILD, S.RESUME_PORTS, S.DEPLOY, S.VERIFY, S.DESIGN, S.DONE) && sessionId) {
      navigate({ name: "session", workspaceId, sessionId });
    } else if (workspaceId && step === S.STARTING) navigate({ name: "workspace", workspaceId });
  }, []);

  useEffect(() => {
    if (loading || !workspaceId || demo) return;
    const timer = setTimeout(() => exit(), 3000);
    return () => clearTimeout(timer);
  }, [loading, workspaceId, demo]);

  useEffect(() => {
    if (!workspaceId || step !== S.RESTARTING) return;
    let live = true;
    const check = () => void isTrialReady(workspaceId).then((value) => { if (live) setReady(value); }).catch(() => {});
    check(); const timer = setInterval(check, 2500);
    return () => { live = false; clearInterval(timer); };
  }, [step, workspaceId]);

  // Reload recovery reads the server, including a mutation accepted just before a browser reload.
  useEffect(() => {
    if (!workspaceId) return;
    let live = true;
    const secret = step === S.ADMIN_USER ? SECRET_USER : step === S.ADMIN_PASSWORD ? SECRET_PASSWORD : null;
    if (secret) {
      setChecking(true);
      void loadTrialSecretNames(workspaceId).then((names) => { if (live && names.includes(secret)) go(step + 1); })
        .catch(() => { if (live) setProblem("Could not check saved secrets. Check the Secrets list before adding one."); })
        .finally(() => { if (live) setChecking(false); });
    }
    if (step === S.HTTP || step === S.HTTPS) {
      void trialPortIsOpen(workspaceId, step === S.HTTP ? "80" : "443").then((open) => { if (live && open) go(step === S.HTTP ? S.HTTPS : S.PORT_REVIEW); }).catch(() => {});
    }
    return () => { live = false; };
  }, [step, workspaceId]);

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
  const secret = async (name: string, example?: string) => {
    const nameField = control<HTMLInputElement>("#secret-name");
    const valueField = control<HTMLInputElement>("#secret-value");
    if (!nameField || !valueField) { setProblem("Open Settings → Secrets to continue."); return; }
    if (!nameField.value) { await write(nameField, name); }
    if (nameField.value !== name) { setProblem(`Use ${name} in Name so the recipe can find it. Your Value is kept.`); return; }
    if (!valueField.value) {
      if (example) await write(valueField, example);
      else { valueField.focus(); setNote("Enter your own unique WordPress admin password in Value. It is never put in chat or tutorial storage."); }
      return;
    }
    click('[data-tour="add-secret"]');
  };
  const message = async (example: string, send: boolean) => {
    const field = control<HTMLTextAreaElement>('textarea[aria-label="Message"]');
    if (!field || field.disabled) { setProblem("The conversation is still loading. Try again in a moment."); return; }
    if (!field.value.trim()) { await write(field, example); return; }
    if (send) click('button[aria-label="Send"]');
    else go(S.DONE);
  };
  const rawEditor = async (label: string): Promise<HTMLTextAreaElement | null> => {
    let field = control<HTMLTextAreaElement>(`textarea[aria-label="${label} Markdown"]`);
    if (field) return field;
    const modes = control(`[aria-label="${label} editing mode"]`);
    const raw = [...(modes?.querySelectorAll<HTMLButtonElement>("button") ?? [])].find((button) => button.textContent === "Raw Markdown");
    raw?.click();
    await sleep(60);
    field = control<HTMLTextAreaElement>(`textarea[aria-label="${label} Markdown"]`);
    return field;
  };
  const configureTask = async () => {
    const chosen = control<HTMLButtonElement>(`[data-task="${(globalThis.CSS?.escape?.(progress.taskName ?? TOUR_TASK.name) ?? progress.taskName ?? TOUR_TASK.name)}"]`);
    if (chosen && chosen.getAttribute("aria-current") !== "page") { chosen.click(); await sleep(60); }
    const cron = control<HTMLInputElement>("#task-cron");
    if (!cron) { setProblem("Select the new task to continue."); return; }
    const field = await rawEditor("Task prompt");
    if (!field) { setProblem("The task editor is still loading. Try again in a moment."); return; }
    let demonstrated = false;
    if (cron.value === "0 9 * * 1-5" || !cron.value.trim()) { await write(cron, TOUR_TASK.cron); demonstrated = true; }
    if (!field.value.trim()) { await write(field, TOUR_TASK.prompt); demonstrated = true; }
    if (!demonstrated) click('[data-tour="save-task"]');
  };
  const configureInstruction = async () => {
    const chosen = control<HTMLButtonElement>(`[data-instruction="${(globalThis.CSS?.escape?.(progress.instructionName ?? TOUR_STYLE_NAME) ?? progress.instructionName ?? TOUR_STYLE_NAME)}"]`);
    if (chosen && chosen.getAttribute("aria-current") !== "page") { chosen.click(); await sleep(60); }
    const field = await rawEditor("AI instructions");
    if (!field) { setProblem("Select your new instruction and try again."); return; }
    if (!field.value.trim()) { await write(field, TOUR_STYLE); return; }
    click('[data-tour="save-instruction"]');
  };
  const finish = async () => {
    if (!workspaceId) return;
    setWorking(true); const saved = await finishTour(workspaceId);
    if (!active.current) return;
    setWorking(false);
    if (saved) { onComplete(); exit(); } else setProblem("Could not save completion. Click Finish tutorial to retry.");
  };

  const settingsTarget = '[data-tour="session-settings"]';
  const sessionsTarget = '[data-workspace-view="sessions"]';
  const sessionTarget = `[data-session="${sessionId ?? ""}"]`;
  const section = (name: string) => `[data-section="${name}"]`;
  const port = step === S.HTTP ? "80" : "443";
  const until = demo?.expiresAt ? Date.parse(demo.expiresAt) : null;
  let card: Card;
  switch (step) {
    case S.WELCOME: card = { title: "Your own workspace, step by step", target: '[data-tour="new-workspace"]',
      body: <>A workspace is your own computer in the cloud. It keeps running your apps and scheduled tasks when you close the browser. Your AI assistant can install software, change files and fix problems there: describe what you want in chat, without needing to know the commands. Just click <strong>Next</strong> to demonstrate each step, or use the highlighted controls yourself. We will build a WordPress blog on a free four-hour machine; your personal Trial connector provides up to $1 of AI usage, with no automatic refill.</>, next: () => click('[data-tour="new-workspace"]') }; break;
    case S.NAME: card = { title: "Give your workspace a name", target: "#workspace-name",
      body: <>Type your own name for the project, or click Next and watch us enter <strong>{TOUR_WORKSPACE_NAME}</strong>. You can edit the example before continuing with the real form's Next button.</>, next: () => fillThenSubmit("#workspace-name", TOUR_WORKSPACE_NAME, '[data-tour="workspace-name-form"] button[type="submit"]') }; break;
    case S.OPENROUTER: card = { title: "Your trial AI token",
      body: <>Your account has its own Trial connector with a $1 lifetime AI budget. It is your protected default and cannot be deleted. Review the AI-provider notice before continuing.</>, disabled: !trial || !accepted || connectors.loading, next: prepareTrial }; break;
    case S.MACHINE: card = { title: "Choose the machine for this trial",
      body: <>Your workspace runs on a computer in the cloud, called a virtual machine (VM). You use it from your browser, and it can keep working when your own computer is off. Its size determines how much it can do: CPU provides processing power, memory (RAM) gives your apps room to run together, and disk is the space for your files. For this free trial, we have selected the lowest-cost available size. The other sizes are shown for comparison. To continue after the trial, create a separate paid workspace.</>, disabled: !trial || !consent || !cheapest, label: "Create free workspace", next: launch }; break;
    case S.STARTING: card = { title: "Your workspace is starting", target: '[data-tour="demo-banner"]', body: <>We are switching on your cloud computer and getting your assistant ready. This usually takes 30–60 seconds, sometimes a little longer. There is nothing you need to do while you wait. Next becomes available when your workspace is ready. The banner shows exactly when your free trial ends and the workspace will be deleted.</>, waiting: demo?.status === "READY" ? undefined : "Preparing the workspace…", next: () => go(S.STORE) }; break;
    case S.STORE: card = { title: "Open the real recipe Store", target: '[data-tour="open-store"]', body: <>A recipe is a reusable set of instructions for the assistant, such as installing a blog or connecting a domain. It saves you explaining the same setup from scratch. Recipes can contain any instructions, including running software, changing files or connecting accounts. Read the full recipe and check who published it before applying. Only apply it if you understand and trust what it asks the assistant to do. Open Store in the top bar; Next clicks that same link.</>, next: () => click('[data-tour="open-store"]') }; break;
    case S.PRIVATE: card = { title: "Recipes can belong just to you", target: '[role="tab"][data-tab="private"]', body: <>Select Private to see recipes only you can use. You can keep your own setup instructions here and reuse them in another workspace.</>, next: () => click('[role="tab"][data-tab="private"]') }; break;
    case S.OWN: card = { title: "You can write or import your own", target: '[data-tour="new-recipe"]', body: <>New recipe lets you write instructions or import a Markdown file. Choose private for your own use or public to share it. We will use a published recipe today.</>, next: () => go(S.PUBLIC) }; break;
    case S.PUBLIC: card = { title: "See what people have shared", target: '[role="tab"][data-tab="public"]', body: <>Public is the community recipe collection: reusable setups shared by other people. You can browse, apply and rate them. Next opens it.</>, next: () => click('[role="tab"][data-tab="public"]') }; break;
    case S.FIND_RECIPE: card = { title: "Find WordPress blog", target: control(`[data-recipe="${TOUR_RECIPE_ID}"]`) ? `[data-recipe="${TOUR_RECIPE_ID}"]` : '[aria-label="Search recipes"]', body: <>Here is WordPress blog by Pavel Usanli: a community recipe for building your own custom blog. Open it, or use Search recipes if it is not visible.</>, next: async () => { if (control(`[data-recipe="${TOUR_RECIPE_ID}"]`)) click(`[data-recipe="${TOUR_RECIPE_ID}"]`); else { const field = control<HTMLInputElement>('[aria-label="Search recipes"]'); if (field && !field.value) await write(field, "WordPress blog"); else setProblem("Search for WordPress blog, then open its card."); } } }; break;
    case S.REVIEW: card = { title: "Read what the recipe will do", target: 'section[aria-label="Steps"]', body: <>WordPress manages your blog and articles. This recipe installs it and publishes it with HTTPS, which encrypts the connection between visitors and your site. It also saves CLI (command-line) instructions so the assistant can publish articles later. Apply it to start a real session; the agent will explain what it needs as it works.</>, next: () => go(S.APPLY) }; break;
    case S.APPLY: card = { title: "Use the actual Apply button", target: '[data-tour="recipe-apply"]', body: <>Apply asks which workspace should run the recipe. It starts a normal conversation; nothing is deployed merely by reading the recipe.</>, next: () => click('[data-tour="recipe-apply"]') }; break;
    case S.PICK: card = { title: "Apply it in your trial workspace", target: '[data-tour="workspace-picker-confirm"]', body: <>Select <strong>{demo?.name}</strong>, then click the highlighted Apply button. Next selects that workspace if needed and uses the same button. This really applies the recipe and opens its session. No credentials are entered here.</>, next: () => { const radio = control<HTMLInputElement>(`[data-workspace-choice="${workspaceId}"]`); if (radio && !radio.checked) { radio.click(); return; } click('[data-tour="workspace-picker-confirm"]'); } }; break;
    case S.REQUEST_SECRETS: card = { title: "Watch the recipe start in chat", target: 'section[aria-label="Chat"]', body: <>The recipe is now running in this real session. The agent checks what it needs and asks you to add missing credentials through Settings → Secrets. Read its reply here. If it reports a problem, ask it to fix that before continuing. Next shows where those secrets live.</>, waiting: busy === false && checkpoint === "secrets" ? undefined : "Waiting for the agent to request the WordPress secrets in Settings…", next: () => go(S.SETTINGS) }; break;
    case S.SETTINGS: case S.PORT_SETTINGS: case S.TASK_SETTINGS: card = { title: "Open this workspace's Settings", target: settingsTarget, body: <>Use the settings button beside the workspace name. Settings belongs to this machine and holds its secrets, firewall, instructions and tasks.</>, next: () => click(settingsTarget) }; break;
    case S.SECRETS: card = { title: "Find Secrets", target: section("secrets"), body: <>Secrets hold passwords and API keys that apps need to sign in to other services. Save them here instead of in chat or a recipe. The assistant can use them through environment variables, named values available to its tools; the app shows only their names after saving.</>, next: () => click(section("secrets")) }; break;
    case S.ADMIN_USER: card = { title: "Add the admin username as a secret", target: '[data-tour="secret-form"]', body: <>The required name <code>{SECRET_USER}</code> is filled and locked to prevent typos. Choose your admin username in Value, or Next types <strong>blog-admin</strong>. Review it, then use Add or Next again. Your own value is kept.</>, disabled: checking, next: () => secret(SECRET_USER, "blog-admin") }; break;
    case S.ADMIN_PASSWORD: card = { title: "Add your own admin password", target: '[data-tour="secret-form"]', body: <>The required name <code>{SECRET_PASSWORD}</code> is filled and locked. Enter your own unique password in Value and save with Add. Passwords stay out of chat and tutorial storage.</>, disabled: checking, next: () => secret(SECRET_PASSWORD) }; break;
    case S.RESTART: card = { title: "Apply the secrets with a restart", target: '[data-tour="restart-workspace"]', body: <>Saved secrets reach the running agent after a restart. Files and conversations are kept. Use the actual Restart workspace button to see its confirmation.</>, next: () => click('[data-tour="restart-workspace"]') }; break;
    case S.CONFIRM_RESTART: card = { title: "Confirm the restart", target: '[data-tour="confirm-restart"]', body: <>This is the same confirmation used elsewhere in the app. It briefly stops the agent and reloads the saved secrets when the workspace starts again.</>, next: () => click('[data-tour="confirm-restart"]') }; break;
    case S.RESTARTING: card = { title: "Wait for the workspace to return", target: '[data-tour="demo-banner"]', body: <>Your assistant is restarting to pick up the secrets you saved. Your files and conversations are kept. This usually takes 30–60 seconds; there is nothing you need to do while you wait. Next becomes available when it is ready. Your trial countdown continues during the restart.</>, waiting: ready ? undefined : "Restarting…", next: () => go(S.SESSIONS) }; break;
    case S.SESSIONS: case S.PORT_SESSIONS: case S.DESIGN_SESSIONS: card = { title: "Return through Sessions", target: sessionsTarget, body: <>A session is a conversation with the assistant about a task. Sessions have separate chat histories but share this workspace's files, apps and instructions. Open Sessions, then return to the recipe's conversation.</>, next: () => click(sessionsTarget) }; break;
    case S.SESSION: case S.PORT_SESSION: case S.DESIGN_SESSION: card = { title: "Reopen the recipe conversation", target: sessionTarget, body: <>A conversation keeps its history. Reopening this one lets the agent continue where it paused, using the same workspace files.</>, next: () => click(sessionTarget) }; break;
    case S.RESUME_SECRETS: card = { title: "Tell the agent the secrets are ready", target: 'textarea[aria-label="Message"]', body: <>Write your own update, or Next types an example. Read or edit it, then Send or Next again. The recipe continues to its port-opening checkpoint.</>, next: () => message(TOUR_SECRET_CONTINUE, true) }; break;
    case S.BUILD: card = { title: "Watch the installation", target: 'section[aria-label="Chat"]', body: <>The assistant uses Docker Compose to run WordPress and its database in containers, separate environments for each service. Starting the website alone does not make it reachable from the internet: its public ports must also be opened. It will pause and ask you to open ports 80 and 443 in Settings. Read that reply before continuing.</>, waiting: busy === false && checkpoint === "ports" ? undefined : "Waiting for the agent to request ports 80 and 443 in Settings…", next: () => go(S.PORT_SETTINGS) }; break;
    case S.FIREWALL: card = { title: "Find the Firewall controls", target: section("firewall"), body: <>Starting a website does not automatically make it accessible from the internet. A port is a numbered doorway; the firewall keeps it closed until you allow visitors in. We will open the website ports so anyone on the internet can reach the blog. You can also ask in chat, “Make my website public with HTTPS.” The assistant can configure it for you when “Let the agent open and close ports” is enabled here.</>, next: () => click(section("firewall")) }; break;
    case S.HTTP: case S.HTTPS: card = { title: step === S.HTTP ? "Open TCP port 80 for certificate checks" : "Open TCP port 443 for HTTPS", target: 'form[aria-label="Open a port"]', body: <>Keep Protocol as TCP and From empty for Anyone. {step === S.HTTP ? "Port 80 handles Let's Encrypt validation and redirects visitors to HTTPS." : "Port 443 carries encrypted website and admin traffic."} Type <strong>{port}</strong>, or Next types it. Use Open port or Next again to save the real rule.</>, next: () => fillThenSubmit("#firewall-port", port, '[data-tour="open-port"]') }; break;
    case S.PORT_REVIEW: card = { title: "See what is now public", target: '[aria-label="Firewall rules"]', body: <>These rules allow anyone on the internet to reach the services on TCP ports 80 and 443. The website must also be running and listening on those ports. HTTPS encrypts the connection; it does not restrict who can visit. Keep private tools behind a login. You can close ports here or ask the assistant to close them when its firewall access is allowed.</>, next: () => go(S.PORT_SESSIONS) }; break;
    case S.RESUME_PORTS: card = { title: "Let the recipe finish publishing", target: 'textarea[aria-label="Message"]', body: <>Tell the agent the ports are open. Next types an example; review it and send with the real Send button or Next again. It verifies the rules and sets up HTTPS for the machine IP.</>, next: () => message(TOUR_PORT_CONTINUE, true) }; break;
    case S.DEPLOY: card = { title: "Watch HTTPS and the first article appear", target: 'section[aria-label="Chat"]', body: <>The agent obtains a trusted Let's Encrypt certificate, installs WordPress, publishes a welcome article and saves CLI instructions. If any check fails, ask it to fix that here.</>, waiting: busy === false ? undefined : "Waiting for the agent to finish…", next: () => go(S.VERIFY) }; break;
    case S.VERIFY: card = { title: "Check your real website", target: 'section[aria-label="Chat"]', body: <>Open the <strong>https://machine-ip</strong> link in the reply in a new tab. Check the home page, welcome article and trusted HTTPS connection. Continue once it works; if it does not, ask the agent to fix it first.</>, label: "My blog works", next: () => go(S.TASK_SETTINGS) }; break;
    case S.TASKS: card = { title: "Find scheduled Tasks", target: section("tasks"), body: <>A scheduled task asks the assistant to do work later, even with your browser closed while the workspace is running. You can ask in session chat: “In 30 minutes, check whether my blog is online” or “Every weekday at 9 am, publish a technology news summary.” The assistant can create the schedule for you. We will also show the editor so you know where to review it.</>, next: () => click(section("tasks")) }; break;
    case S.NEW_TASK: card = { title: "Create a news task", target: '[data-tour="new-task"]', body: <>New task opens the name form. This task will look for a fresh technology story and publish a sourced summary. Every run uses your OpenRouter credit.</>, next: () => click('[data-tour="new-task"]') }; break;
    case S.TASK_NAME: card = { title: "Name the task", target: "#task-name", body: <>Choose your own file name or let Next type <strong>{TOUR_TASK.name}</strong>. Then use Create or Next again. Its prompt and schedule are edited next.</>, next: () => fillThenSubmit("#task-name", TOUR_TASK.name, 'form[aria-label="New task"] button[type="submit"]') }; break;
    case S.TASK_CONFIG: card = { title: "Write what should run, and when", target: 'section[aria-label="Tasks"]', body: <>The prompt says what to do; the cron expression is a five-field shorthand for when to repeat it. Next fills */5 * * * *, meaning every five minutes, and a news prompt. Check the time zone for schedules at a particular hour. You can ask the assistant to set the timing instead of learning cron. Review the source-checking and duplicate-checking rules, then Save or Next again. Each run spends OpenRouter credit.</>, next: configureTask }; break;
    case S.TASK_SAVED: card = { title: "Your automation is saved", target: 'nav[aria-label="Tasks"]', body: <>Each run gets its own conversation under Sessions → Scheduled, where you can read what happened or check a failure. Ask in chat to change the timing, stop a task or schedule another one. Reload your blog after a run to see a new post if there is a fresh story. You can change or delete the task here; it stops when the trial is deleted.</>, next: () => go(S.INSTRUCTIONS) }; break;
    case S.INSTRUCTIONS: card = { title: "See the workspace's shared Instructions", target: section("instructions"), body: <>Instructions are lasting rules and working notes shared across this workspace's sessions. They save you repeating preferences and let future tasks use what the assistant has already learned. The recipe saved how to operate your blog. We will inspect that, then add your own writing rule.</>, next: () => click(section("instructions")) }; break;
    case S.BLOG_GUIDE: card = { title: "The recipe left a CLI publishing guide", target: '[data-instruction="blog"]', body: <>CLI means command-line interface: commands the assistant can run to operate your blog. You do not need to learn or type them. Open blog to see the tested commands it saved for listing articles and publishing new ones. The news task reads this same guide. Next opens it; continue after looking through it.</>, next: () => { const file = control<HTMLButtonElement>('[data-instruction="blog"]'); if (file && file.getAttribute("aria-current") !== "page") { file.click(); return; } go(S.NEW_INSTRUCTION); } }; break;
    case S.NEW_INSTRUCTION: card = { title: "Add a rule of your own", target: '[data-tour="new-instruction"]', body: <>Use New instructions to create a shared writing-style rule. It can guide future replies and scheduled articles without repeating it in every prompt.</>, next: () => click('[data-tour="new-instruction"]') }; break;
    case S.INSTRUCTION_NAME: card = { title: "Name your instruction", target: "#instructions-name", body: <>Choose a short file name, or Next types <strong>{TOUR_STYLE_NAME}</strong>. Create it using the real form; then we will write its contents.</>, next: () => fillThenSubmit("#instructions-name", TOUR_STYLE_NAME, 'form[aria-label="New instructions"] button[type="submit"]') }; break;
    case S.INSTRUCTION_EDIT: card = { title: "Choose how the agent should write", target: 'section[aria-label="Instructions"]', body: <>Write your own rule or let Next demonstrate short, friendly sentences, plain explanations and sourced news. It types into the real editor. Edit the example, then Save or Next again.</>, next: configureInstruction }; break;
    case S.INSTRUCTION_SAVED: card = { title: "Your writing rule is now shared", target: `nav[aria-label="Instruction files"]`, body: <>Your saved rule appears alongside blog. The agent uses saved instructions from its next message; later sessions and scheduled tasks receive them too. You can edit or remove the rule here.</>, next: () => go(S.DESIGN_SESSIONS) }; break;
    case S.DESIGN: card = { title: "Your turn: request a new design", target: 'textarea[aria-label="Message"]', body: <>You can ask for an outcome in plain language, such as changing colours, adding a page or fixing an error. The assistant handles the technical steps and can explain them if you ask. Describe the look you want. Next types an example if the message box is empty; your own text is kept. Edit it however you like. You will send this final request yourself.</>, next: async () => { const field = control<HTMLTextAreaElement>('textarea[aria-label="Message"]'); if (!field) { setProblem("The conversation is still loading."); return; } if (!field.value.trim()) await write(field, TOUR_DESIGN_PROMPT); if (active.current) go(S.DONE); } }; break;
    default: card = { title: "Make it yours, then Send", target: 'textarea[aria-label="Message"]', body: <>Edit the request below and send it yourself. Reload your blog after the assistant replies. You have practised the tools; next time you can ask the assistant to manage tasks, instructions and public ports from chat. Finish to see a suggested next step: connecting your own domain. {until ? <>You have <strong>{timeLeft(until, Date.now())}</strong> left.</> : "The trial lasts four hours."} Then the workspace and its data are permanently deleted. Finishing sends an in-app notification with the exact deadline; you are also notified before deletion and after it succeeds. No email is sent for these alerts.</>, label: "Finish tutorial", next: finish };
  }

  const custom = step === S.OPENROUTER || step === S.MACHINE;
  const customContent = step === S.OPENROUTER ? <div className="space-y-5"><h2 className="text-xl font-semibold">Trial AI connector</h2><label className="block text-sm font-medium">AI connector<select aria-label="Trial AI connector" value={trial?.id ?? ""} disabled className="mt-2 block w-full rounded-lg border border-zinc-300 bg-transparent p-3 dark:border-zinc-700"><option value={trial?.id ?? ""}>{trial ? "Trial — $1 lifetime" : connectors.loading ? "Preparing your Trial connector…" : "Trial connector unavailable"}</option></select></label>{!trial && !connectors.loading && <p role="status" className="text-sm text-zinc-500">Your trial AI key is not available yet. Refresh this page to retry. You can also manage your own connectors in General settings.</p>}{connectors.error && <p role="alert" className="text-sm text-rose-600">{connectors.error}</p>}<AiProviderNotice accepted={accepted} onChange={setAccepted} disabled={!trial || working} /></div> : <div className="space-y-4"><h2 className="text-xl font-semibold">{progress.name ?? TOUR_WORKSPACE_NAME}</h2><fieldset className="space-y-2"><legend className="mb-2 text-sm font-medium">Trial machine</legend>{sizes?.map((size) => <label key={size.serverType} className={`flex items-start gap-3 rounded-lg border p-3 ${size.serverType === cheapest?.serverType ? "border-amber-400 bg-amber-50 dark:bg-amber-950/30" : "border-zinc-200 opacity-60 dark:border-zinc-700"}`}><input type="radio" name="trial-machine" aria-label={size.serverType} checked={size.serverType === cheapest?.serverType} disabled={size.serverType !== cheapest?.serverType} readOnly className="mt-1 accent-amber-600" /><span className="min-w-0 break-words text-sm"><strong>{size.serverType}</strong> · {size.cores} CPUs · {size.memoryGb} GB RAM · {size.diskGb} GB disk<span className="block text-xs">{size.serverType === cheapest?.serverType ? "Selected · free for four hours" : size.available ? "Unavailable in this free trial" : "Unavailable from the provider"}</span></span></label>)}</fieldset><Button label="Refresh machines" variant="secondary" onClick={loadSizes} /><label className="flex cursor-pointer items-start gap-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm leading-6 dark:border-amber-800 dark:bg-amber-950/30"><input type="checkbox" checked={consent} onChange={(event) => setConsent(event.target.checked)} className="mt-1 size-5 shrink-0 accent-amber-600" /><span>I understand this workspace, its website, files, sessions, secrets and scheduled tasks will be <strong>permanently deleted after four hours</strong>. I will not use it for production or anything I need to keep.</span></label>{!sizes && <Spinner label="Loading machines" />}{sizes && !cheapest && <p role="status">No trial machine is available. Refresh to check again.</p>}</div>;
  const runNext = async () => {
    if (acting || working) return;
    setActing(true);
    try { await card.next(); }
    catch { if (active.current) setProblem("Could not complete this step. Check the highlighted control and try again."); }
    finally { if (active.current) setActing(false); }
  };
  return <TourFrame step={step} card={{ ...card, next: runNext }} working={working || acting} problem={problem} note={note} onSkip={exit} custom={custom ? customContent : undefined} />;
}

interface Card {
  readonly title: string;
  readonly body: ReactNode;
  readonly target?: string;
  readonly waiting?: string;
  readonly disabled?: boolean;
  readonly label?: string;
  readonly next: () => unknown | Promise<unknown>;
}
function chapter(step: number): string {
  return step <= S.STARTING ? "Your workspace" : step <= S.PICK ? "The recipe Store" : step <= S.RESUME_SECRETS ? "Secrets" : step <= S.VERIFY ? "Publish with HTTPS" : step <= S.TASK_SAVED ? "Scheduled tasks" : step <= S.INSTRUCTION_SAVED ? "Shared instructions" : "Your turn";
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
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3"><div className="min-w-0 basis-full text-xs leading-5 sm:min-w-40 sm:flex-1 sm:basis-auto">{problem ? <p role="alert" className="text-rose-600 dark:text-rose-400">{problem}</p> : card.waiting ? <p role="status" className="flex items-center gap-2 text-zinc-500"><Spinner className="size-3.5" />{card.waiting}</p> : <p className="text-zinc-500">{note ?? (card.target ? "Use the highlighted control, or let Next demonstrate it." : "Continue when you are ready.")}</p>}</div><button type="button" onClick={onSkip} className="min-h-11 px-2 text-sm text-zinc-500 underline-offset-2 hover:underline">Skip tour</button><div className="flex-1 sm:flex-none [&>button]:min-h-11 [&>button]:w-full"><Button label={card.label ?? "Next"} icon={step === S.DONE ? Check : ArrowRight} busy={working} disabled={!!card.waiting || card.disabled} onClick={() => { void card.next(); }} /></div></div>
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
