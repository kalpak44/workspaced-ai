/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** The commit the bundle was built from; CI sets it, a local build has none. */
  readonly VITE_APP_VERSION?: string;
}
