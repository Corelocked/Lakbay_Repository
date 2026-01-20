# UI / UX Audit — Scenic Navigation (quick audit)

Date: 2025-12-09

Overview
- Goal: Improve clarity, consistency, and discoverability of the app's main flows: Route planning, Curation, Map interactivity, Settings, and Recommendations.
- Scope: Quick audit focused on highest-impact changes to implement this sprint: Material theming consistency, route input UX polish, POI interaction (preview, clustering, bottom-sheet details), Settings improvements, and accessibility/performance.

Priority roadmap (short-term → medium-term)

1) Visual language and theming (High)
- Apply Material3 token mapping across colors, typography and shapes (already partially applied).
- Ensure Toolbar, Buttons, Cards, Snackbars, and Preference screens use Material tokens (`colorPrimary`, `colorOnPrimary`, `colorSurface`, `colorOnSurface`, typography roles).
- Deliverable: `res/values/themes.xml` finished, `colors.xml` reviewed, small adjustments to `styles.xml` if needed.

2) Route input card & planning flow (High)
- Improve the route input card for clarity: larger CTA, clear primary/secondary actions, concise copy, and inline validation.
- Add POI quick-preview list below the Plan button for immediate feedback after planning.
- Smooth collapse/expand transitions, and anchor Snackbars to the card.
- Deliverable: `fragment_route.xml` polish, `RouteFragment` animations and event handling.

3) POI discovery & interaction (High)
- Cluster markers at low zoom, display aggregated cluster markers with counts.
- Tapping a cluster opens a small list (bottom sheet) showing cluster members for selection.
- Single-POI taps open a bottom-sheet detail card with actions (Navigate, Save). Save persists to a local favorites store.
- Deliverable: cluster algorithm (map-aware), `fragment_poi_detail_bottom_sheet.xml`, `ClusterListBottomSheet`, and `FavoriteStore`.

4) Settings and tuning (Medium)
- Group related preferences, show human-readable summaries, and make Reset clearly destructive and confirmable.
- Sync preference changes live with `RouteViewModel` (already implemented via `SettingsBus`).
- Deliverable: `preferences.xml` refinements and `SettingsFragment` copy polish.

5) Performance & accessibility (Medium)
- Lazy-load POIs and cluster only visible items; reduce Overpass calls by using BBOX batching (already implemented at service level).
- Add `contentDescription`s, accessible labels for inputs, and ensure contrast ratios.
- Deliverable: accessibility checklist and fixes; map overlay performance tests.

6) Motion & feedback (Low → Medium)
- Add subtle motion to the input card collapse/expand, button ripples, and success animations when a route is planned.
- Deliverable: small MotionLayout or view animations in `RouteFragment`.

7) Curation flow polishing (Low → Medium)
- Convert the curation dialog to a full-screen flow if more than two steps are required; add microcopy, progress indicator, and previewing curated itinerary.
- Deliverable: `CurationFragment` or `CurationActivity` with a stepper UI.

Quick wireframes and interaction notes

- Route Screen (Top)
  - AppBar (title + overflow menu)
  - Map fills screen
  - Floating input card at top-left with subtle drop shadow
  - Card header: title + settings icon + collapse chevron
  - Collapsed: show compact start/destination summary + Plan CTA
  - Expanded: full inputs, switches, CTA, preview strip

- POI cluster tap
  - Tap cluster -> open bottom sheet (half-height) listing members with small thumbnails and brief excerpt. Tapping a member recenters map and opens detail sheet.

- POI detail
  - Bottom sheet with title, category, image (if available), short description, scenic score, and actions: Navigate | Save (toggle) | Share (optional)

- Settings
  - Group: Clustering (eps, minPts), Routing bias (oceanic/mountain weight), Model (ML reranker on/off), Data sources (Luzon CSV merge toggle), Reset defaults

Accessibility checklist (must-fix before next user test)
- All interactive controls have contentDescription or text labels
- Ensure 44dp minimum touch targets for buttons
- Color contrast > 4.5:1 for primary text; 3:1 for secondary text where applicable
- Keyboard focus order sensical for inputs and action buttons

Implementation plan for this sprint (2-week cadence)
- Week 1: Finish Material theme tokens, Route input card polish, POI clustering + bottom sheets, basic favorites
- Week 2: Settings polish, accessibility fixes, motion polish, curation flow screen

Notes & open questions
- Do we want cluster icons to show category color or neutral color? (I used the primary color for fast iteration.)
- Favorite persistence: currently planned as local-only (SharedPreferences). If export/sync needed later, add lightweight DB or cloud sync.
- Map bounding/clustering: current simple greedy clustering is fine to start; long-term improvement: use tiling and cluster only visible tiles (or use a 3rd-party clustering lib)

Next actions (I'll implement now)
- Create `ClusterListBottomSheet` and `FavoriteStore` (local SharedPreferences store).
- Wire cluster taps to open the cluster list and single POI taps to open details.
- Ensure preview cards show favorite state and allow quick save.

