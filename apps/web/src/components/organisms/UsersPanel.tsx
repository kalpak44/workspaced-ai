import { UserPlus } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { type CreateResult, type NewUser, type User, USER_SORTS, type UserSort } from "../../hooks/useUsers";
import { Button, Spinner } from "../atoms";
import { ErrorBanner, SettingsSection, UserItem } from "../molecules";
import { CreateUserDialog } from "./CreateUserDialog";
import { DeleteUserDialog } from "./DeleteUserDialog";

interface Props {
  readonly users: readonly User[];
  /** How many there are in all, loaded or not. */
  readonly total: number;
  /** The signed-in user, listed without a delete action. */
  readonly currentUserId: string | undefined;
  readonly sort: UserSort;
  readonly onSortChange: (sort: UserSort) => void;
  readonly loading: boolean;
  readonly loadingMore: boolean;
  readonly hasMore: boolean;
  /** Asked for the next page whenever the end of the list scrolls into view. */
  readonly onLoadMore: () => void;
  readonly removing: ReadonlySet<string>;
  readonly error: string | null;
  readonly onDismissError: () => void;
  readonly onCreate: (user: NewUser) => Promise<CreateResult>;
  readonly onRemove: (user: User) => void;
}

/** Everyone who can sign in, in the chosen order and loaded as it scrolls; adding and deleting each go through a dialog. */
export function UsersPanel({
  users,
  total,
  currentUserId,
  sort,
  onSortChange,
  loading,
  loadingMore,
  hasMore,
  onLoadMore,
  removing,
  error,
  onDismissError,
  onCreate,
  onRemove,
}: Props) {
  const [creating, setCreating] = useState(false);
  const [confirming, setConfirming] = useState<User | null>(null);
  const end = useRef<HTMLDivElement>(null);
  const loadMore = useRef(onLoadMore);
  loadMore.current = onLoadMore;

  // Watched while there is more to load; a page that still leaves the end in view asks for the next.
  useEffect(() => {
    if (!hasMore || loading || loadingMore || !end.current || typeof IntersectionObserver === "undefined") {
      return undefined;
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) loadMore.current();
    }, { rootMargin: "200px" });
    observer.observe(end.current);
    return () => observer.disconnect();
  }, [hasMore, loading, loadingMore, users.length]);

  return (
    <SettingsSection title="Users" description="Everyone who can sign in to this installation.">
      {error && <ErrorBanner message={error} onDismiss={onDismissError} />}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1.5 text-sm">
          <label htmlFor="users-sort" className="font-medium text-zinc-700 dark:text-zinc-300">Sort by</label>
          <select id="users-sort" value={sort} onChange={(event) => onSortChange(event.target.value as UserSort)}
            className="min-w-0 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-500 focus:ring-2 focus:ring-zinc-900/10 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400 dark:focus:ring-white/10">
            {Object.entries(USER_SORTS).map(([id, option]) => <option key={id} value={id}>{option.label}</option>)}
          </select>
        </div>
        <Button label="Add user" icon={UserPlus} onClick={() => setCreating(true)} />
      </div>
      {loading ? (
        <Spinner label="Loading users" className="size-5 text-zinc-400" />
      ) : (
        <div className="flex flex-col gap-2">
          <ul aria-label="Users" className="divide-y divide-zinc-200 rounded-md border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
            {users.map((user) => (
              <UserItem
                key={user.id}
                user={user}
                self={user.id === currentUserId}
                removing={removing.has(user.id)}
                onRemove={() => setConfirming(user)}
              />
            ))}
          </ul>
          <div ref={end} className="flex min-h-5 items-center justify-between text-xs text-zinc-500 dark:text-zinc-400">
            <span>{`Showing ${users.length} of ${total}`}</span>
            {loadingMore && <Spinner label="Loading more users" className="size-4 text-zinc-400" />}
          </div>
        </div>
      )}
      {creating && <CreateUserDialog onCreate={onCreate} onClose={() => setCreating(false)} />}
      {confirming && (
        <DeleteUserDialog
          username={confirming.username}
          onClose={() => setConfirming(null)}
          onConfirm={() => {
            onRemove(confirming);
            setConfirming(null);
          }}
        />
      )}
    </SettingsSection>
  );
}
