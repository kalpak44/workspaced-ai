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
      className={`absolute top-full right-0 z-10 overflow-hidden border border-zinc-200 bg-white text-sm shadow-lg dark:border-zinc-700 dark:bg-zinc-900 ${className}`}
    >
      {children}
    </div>
  );
}

interface MenuItemProps {
  readonly onClick: () => void;
  readonly children: ReactNode;
  readonly danger?: boolean;
  readonly className?: string;
}

export function MenuItem({ onClick, children, danger = false, className = "" }: MenuItemProps) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      className={`flex w-full items-center justify-between text-left hover:bg-zinc-100 dark:hover:bg-zinc-800 ${
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
      className={`flex w-full items-center justify-between hover:bg-zinc-100 dark:hover:bg-zinc-800 ${className}`}
    >
      {children}
    </a>
  );
}
