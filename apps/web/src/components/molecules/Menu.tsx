import type { ReactNode } from "react";

interface MenuProps {
  /** Width and offset; the menu always opens below and right-aligned to its anchor. */
  readonly className: string;
  readonly children: ReactNode;
}

export function Menu({ className, children }: MenuProps) {
  return (
    <div
      role="menu"
      className={`absolute top-full right-0 z-30 origin-top-right animate-pop-in overflow-hidden border border-zinc-200 bg-white text-sm shadow-lg ring-1 ring-zinc-900/5 dark:border-zinc-800 dark:bg-zinc-900 dark:ring-white/5 ${className}`}
    >
      {children}
    </div>
  );
}

interface MenuItemProps {
  readonly onClick: () => void;
  readonly children: ReactNode;
  readonly danger?: boolean;
  readonly disabled?: boolean;
  readonly className?: string;
}

export function MenuItem({ onClick, children, danger = false, disabled = false, className = "" }: MenuItemProps) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      disabled={disabled}
      className={`flex w-full items-center justify-between text-left transition-colors hover:bg-zinc-100 focus-visible:bg-zinc-100 focus-visible:outline-none dark:focus-visible:bg-zinc-800 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent dark:hover:bg-zinc-800 dark:disabled:hover:bg-transparent ${
        danger ? "text-rose-600 dark:text-rose-400" : ""
      } ${className}`}
    >
      {children}
    </button>
  );
}

interface MenuLinkProps {
  readonly href: string;
  readonly children: ReactNode;
  readonly className?: string;
}

/** A menu entry that navigates; opens in a new tab so the app keeps its state. */
export function MenuLink({ href, children, className = "" }: MenuLinkProps) {
  return (
    <a
      role="menuitem"
      href={href}
      target="_blank"
      rel="noreferrer"
      className={`flex w-full items-center justify-between transition-colors hover:bg-zinc-100 focus-visible:bg-zinc-100 focus-visible:outline-none dark:hover:bg-zinc-800 dark:focus-visible:bg-zinc-800 ${className}`}
    >
      {children}
    </a>
  );
}
