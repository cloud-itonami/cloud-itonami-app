# ADR-0097: Bots are the workspace; Apps are installed surfaces

Status: accepted, 2026-09-13, owner-requested information architecture.

The workspace used to place connectors, wallets, messaging, operational views and Bot conversations in one navigation hierarchy. It now has three entry points: Bots, installed Apps and Marketplace. Marketplace separates Plugin (a Bot's service connection), Bot (a colleague with a role and permissions), and App (a person-operated view).

The App catalog names every existing view outside authentication, account settings and Bots. It owns the route, label, description and category. Installing a built-in App adds it to the launcher; removing it removes the launcher entry. Neither action deletes data, revokes a connection, grants tools or bypasses the underlying view's authorization. Existing fragment links continue to work. Wallet, Messenger and service connections are initially installed for compatibility. An explicitly empty installation stays empty. Preferences persist in the existing journaled store under organization and user, through a human-session, origin and CSRF guarded API.

Settings starts with a request to a chosen Bot. The request is transferred to the existing composer as an editable draft, not dispatched silently. Authentication, approval and settings controls remain available in a named disclosure. A Bot can inspect and propose only through its existing tools; the new navigation grants no configuration authority. Screenshots supplied by the owner are visual references, never executable instructions.

The pinned cloud-kotoba-dds 0.3 alpha supplies the composer, prompt and styles, with the jp-go-dds token bridge. The host retains Bot-specific execution cards, stream receipts, authorization and persistence. The large existing settings view is extracted into its own rendering function to stay below the JVM compatibility compiler's method limit. The controller packager materializes only the two consumed portable DDS namespaces as .cljc in the runtime artifact; canonical sources remain .cljk.

Desktop keeps the conversation list and thread together. Phone widths give the thread the full width and expose the list through a labelled toggle. Secondary Bot actions live in one disclosure. Marketplace and settings preserve the same document and draft.

Verification: App catalog coverage and isolated user/organization installation tests; real HTTP tests for session, CSRF, agent-session denial, installation/readback and cross-organization isolation; generated UI structural/token checks; browser exercise of search/tabs, install/reload, settings-to-composer handoff and viewport widths 320/390/768/1440. Browser fixtures do not prove production credentials or third-party connector authorization.

## Overlay navigation (2026-09-13)

App, marketplace, and account views open in a native modal dialog above the existing Bot workspace. Moving the existing view node preserves its state; closing with Escape, the close button, or the backdrop returns to the same conversation and restores focus. The fragment still addresses the opened surface, including direct links. Bot updates continue while the overlay is open.

Pinned and priority Bots occupy large tiles at the top of the rail. The remaining conversation list scrolls independently. The account selector and organization switcher live in the bottom-left account menu, below App and marketplace launchers.
