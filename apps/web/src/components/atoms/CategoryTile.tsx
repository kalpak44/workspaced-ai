import { AppWindow, Database, Globe, type LucideIcon, Package, Server, Sparkles, Workflow } from "lucide-react";

/** Every category the store knows, in the order its filters show them. */
export const CATEGORIES: ReadonlyArray<{ readonly value: string; readonly label: string; readonly icon: LucideIcon }> = [
  { value: "apps", label: "Apps", icon: AppWindow },
  { value: "websites", label: "Websites", icon: Globe },
  { value: "automation", label: "Automation", icon: Workflow },
  { value: "data", label: "Data", icon: Database },
  { value: "ai", label: "AI", icon: Sparkles },
  { value: "devops", label: "DevOps", icon: Server },
  { value: "other", label: "Other", icon: Package },
];

export function categoryLabel(value: string): string {
  return CATEGORIES.find((category) => category.value === value)?.label ?? "Other";
}

/** The category's icon in a square, as a card's thumbnail. */
export function CategoryTile({ category, large = false }: { readonly category: string; readonly large?: boolean }) {
  const Icon = CATEGORIES.find((candidate) => candidate.value === category)?.icon ?? Package;
  return (
    <span className={`flex shrink-0 items-center justify-center rounded-lg bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200 ${large ? "size-12" : "size-9"}`}>
      <Icon aria-hidden className={large ? "size-6" : "size-4"} />
    </span>
  );
}
