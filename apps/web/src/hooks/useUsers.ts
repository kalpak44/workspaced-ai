import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import type { Role } from "../api/accountApi";
import { changeUserRole, createUser, deleteUser, listUsers, type NewUser, type User } from "../api/usersApi";

export type { Role } from "../api/accountApi";
export type { NewUser, User } from "../api/usersApi";

/** "conflict" is a username someone already has. */
export type CreateResult = "created" | "conflict" | "failed";

/** The orders the list offers, each as the API's sort parameters. */
export const USER_SORTS = {
  newest: { label: "Newest first", sort: ["createdAt,desc"] },
  oldest: { label: "Oldest first", sort: ["createdAt,asc"] },
  "name-asc": { label: "Name A–Z", sort: ["firstName,asc", "lastName,asc"] },
  "name-desc": { label: "Name Z–A", sort: ["firstName,desc", "lastName,desc"] },
  "username-asc": { label: "Username A–Z", sort: ["username,asc"] },
  "username-desc": { label: "Username Z–A", sort: ["username,desc"] },
} as const;

export type UserSort = keyof typeof USER_SORTS;

const PAGE_SIZE = 20;

/**
 * Everyone who can sign in, a page at a time in the chosen order; `loadMore` appends the next.
 * The page to fetch is worked out from how many are loaded, and repeats are dropped, so a user
 * deleted meanwhile shifts the rest without one being skipped. Like the secrets, the list changes
 * only once the API has accepted the change.
 */
export function useUsers(sort: UserSort) {
  const [users, setUsers] = useState<User[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<ReadonlySet<string>>(new Set());
  // Bumped on every reload, so a page that answers after the order changed is dropped.
  const generation = useRef(0);
  const loaded = useRef<User[]>([]);
  const fetching = useRef(false);

  const show = (list: User[]) => {
    loaded.current = list;
    setUsers(list);
  };

  const reload = useCallback(() => {
    const current = ++generation.current;
    fetching.current = true;
    setLoading(true);
    listUsers(0, PAGE_SIZE, USER_SORTS[sort].sort)
      .then((page) => {
        if (current !== generation.current) return;
        show(page.content);
        setTotal(page.page.totalElements);
      })
      .catch(() => current === generation.current && setError("Could not load the users"))
      .finally(() => {
        if (current !== generation.current) return;
        fetching.current = false;
        setLoading(false);
      });
  }, [sort]);

  useEffect(() => {
    reload();
    return () => {
      generation.current++;
    };
  }, [reload]);

  const hasMore = users.length < total;

  const loadMore = () => {
    if (fetching.current || loaded.current.length >= total) return;
    const current = generation.current;
    fetching.current = true;
    setLoadingMore(true);
    listUsers(Math.floor(loaded.current.length / PAGE_SIZE), PAGE_SIZE, USER_SORTS[sort].sort)
      .then((page) => {
        if (current !== generation.current) return;
        const known = new Set(loaded.current.map((u) => u.id));
        show([...loaded.current, ...page.content.filter((u) => !known.has(u.id))]);
        setTotal(page.page.totalElements);
      })
      .catch(() => current === generation.current && setError("Could not load more users"))
      .finally(() => {
        if (current !== generation.current) return;
        fetching.current = false;
        setLoadingMore(false);
      });
  };

  /** A new user may belong anywhere in the order, so the list starts again from the top. */
  const create = (user: NewUser): Promise<CreateResult> =>
    createUser(user).then(
      (): CreateResult => {
        reload();
        return "created";
      },
      (error_: unknown): CreateResult =>
        error_ instanceof ApiError && error_.status === 409 ? "conflict" : "failed",
    );

  /** Swaps the changed user in place once the API has accepted it. */
  const changeRole = (user: User, role: Role) => {
    changeUserRole(user.id, role).then(
      (changed) => show(loaded.current.map((u) => (u.id === changed.id ? changed : u))),
      () => setError(`Could not change the role of ${user.username}`),
    );
  };

  const remove = (user: User) => {
    const settle = () =>
      setRemoving((current) => {
        const next = new Set(current);
        next.delete(user.id);
        return next;
      });
    setRemoving((current) => new Set(current).add(user.id));
    deleteUser(user.id).then(
      () => {
        show(loaded.current.filter((u) => u.id !== user.id));
        setTotal((current) => current - 1);
        settle();
      },
      () => {
        settle();
        setError(`Could not delete ${user.username}`);
      },
    );
  };

  return {
    users,
    total,
    hasMore,
    loading,
    loadingMore,
    error,
    removing,
    dismissError: () => setError(null),
    loadMore,
    create,
    changeRole,
    remove,
  };
}
