# Design System Strategy: The Scenic Editorial
 
## 1. Overview & Creative North Star
This design system is built upon the Creative North Star of **"The Digital Concierge."** It moves away from the clinical, utility-first appearance of traditional navigation apps toward a high-end editorial experience. The goal is to make the journey feel as premium as the destination.
 
Rather than a rigid, boxy grid, this system utilizes **Intentional Asymmetry** and **Tonal Layering**. We use overlapping elements—such as a destination card partially breaking the boundary of a hero image—to create a sense of motion and discovery. By prioritizing "white space as a feature," we ensure the vibrant brand energy never feels cluttered, maintaining a sophisticated, travel-journal aesthetic.
 
## 2. Colors: Tonal Depth & The "No-Line" Rule
The palette is rooted in the energy of a sunset and the warmth of a paper map. We use Material Design tokens to create a logic-based hierarchy.
 
*   **Primary (#b81217):** Our "Vibrant Pulse." Used for high-priority actions and wayfinding.
*   **Secondary (#655e49 / #ece2c6):** The "Sandstone" foundation. These cream and beige tones provide a soft, organic contrast to the high-energy primary red.
*   **Surface Hierarchy:** We utilize `surface-container` tiers to define priority.
    *   **The "No-Line" Rule:** 1px solid borders are strictly prohibited for sectioning. Boundaries must be defined solely through background shifts. For example, a `surface-container-lowest` card (pure white) should sit on a `surface-container-low` background to define its edges naturally.
*   **The "Glass & Gradient" Rule:** Floating elements (like navigation bars) should utilize **Glassmorphism**. Use `surface` colors at 80% opacity with a 20px backdrop blur. 
*   **Signature Textures:** For Hero sections, use a subtle linear gradient from `primary` (#b81217) to `primary_container` (#dc312d) at a 135-degree angle to add "soul" and depth to the brand's signature orange-red.
 
## 3. Typography: Editorial Authority
We use a dual-font pairing to balance personality with extreme legibility.
 
*   **Display & Headlines (Plus Jakarta Sans):** Chosen for its modern, geometric flair. 
    *   `display-lg` (3.5rem) and `headline-md` (1.75rem) should use **Bold** weights. Use tight letter-spacing (-0.02em) for large titles to create a high-fashion, editorial look.
*   **Body & Labels (Manrope):** A highly functional sans-serif with excellent kerning.
    *   `body-lg` (1rem) is the workhorse for trip descriptions. 
    *   `label-md` (0.75rem) in **Bold** uppercase is used for category tags (e.g., "SCENIC ROUTE") sitting on `secondary_container` backgrounds.
 
## 4. Elevation & Depth: Tonal Layering
Depth in this system is organic, not artificial. We avoid the "floating card on grey" cliché.
 
*   **The Layering Principle:** Stack surfaces to create focus.
    *   Level 0: `surface` (The base canvas)
    *   Level 1: `surface-container-low` (Content groupings)
    *   Level 2: `surface-container-lowest` (Interactive cards/modals)
*   **Ambient Shadows:** When a float is required (e.g., a "Start Navigation" button), use a diffused shadow: `y-12, blur-24, spread-0`. The shadow color must be a 6% opacity tint of `primary` (#b81217) rather than grey, creating a "glow" rather than a shadow.
*   **The "Ghost Border" Fallback:** If a layout requires a boundary for accessibility, use the `outline_variant` token at 15% opacity. It should be felt, not seen.
 
## 5. Components
 
### Buttons & Interaction
*   **Primary Button:** Uses `primary` background with `on_primary` (white) text. Roundedness is set to `full` (pill-shaped) to contrast with the `md` (1.5rem) corners of the cards.
*   **Secondary/Tag Button:** Uses `secondary_container` (#ece2c6). These should feel like soft "pills" integrated into the UI.
 
### Cards & Scenic Lists
*   **Standard Card:** `surface-container-lowest` background with a `md` (1.5rem) corner radius. 
*   **The Content Rule:** Forbid the use of divider lines. Separate list items using 16px of vertical white space or by alternating background tones (`surface` to `surface-container-low`).
*   **Imagery:** All imagery must use a `lg` (2rem) corner radius to feel soft and inviting.
 
### Navigation specific components
*   **The Scenic Drawer:** A bottom sheet using `surface_bright` with a heavy backdrop blur. It should feel like a pane of frosted glass sliding over the map.
*   **Floating Action Markers:** Map markers should use the `primary` color with a white `outline` (Ghost Border) to ensure they pop against varied map textures.
 
## 6. Do's and Don'ts
 
### Do:
*   **Do** use asymmetrical margins (e.g., 24px left, 32px right) for editorial headers to create visual interest.
*   **Do** use `secondary_fixed` (cream) for tag backgrounds to keep the interface feeling "warm."
*   **Do** lean into the 1.5rem+ corner radius. Sharp corners are the enemy of this brand's "friendly explorer" persona.
 
### Don't:
*   **Don't** use black (#000000) for text. Always use `on_surface` (#1b1c1c) for a softer, more premium contrast.
*   **Don't** use standard Material shadows. If it looks like a default "drop shadow," it's too heavy. 
*   **Don't** use dividers. If you feel the need for a line, use white space instead. If that fails, use a subtle background color shift.