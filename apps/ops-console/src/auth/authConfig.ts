import { WebStorageStateStore } from "oidc-client-ts";
import type { AuthProviderProps } from "react-oidc-context";
import { config } from "../config";

// Tokens live in sessionStorage, never localStorage, so they are cleared when the tab
// closes. A pure in-memory store would mean rebuilding the silent-renew handshake of
// oidc-client-ts, with no real security gain: script running in the page can read
// either store.
export const authProviderProps: AuthProviderProps = {
  authority: config.keycloakAuthority,
  client_id: config.keycloakClientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  scope: "openid profile sagaharbor-api",
  response_type: "code",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, "/");
  },
};
