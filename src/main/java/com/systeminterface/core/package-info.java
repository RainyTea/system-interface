/**
 * Translates RuneLite event bus signals into {@link com.systeminterface.services.state}
 * mutations.
 *
 * <p>This is the only package allowed to subscribe to RuneLite events
 * ({@code @Subscribe}). It must stay thin: parse the event, resolve the
 * target name, dispatch into {@link com.systeminterface.services.state.StateTracker}.
 * No math, no rendering.
 *
 * <p>Per the agent rules: do not scan the whole scene on tick/frame; track
 * targets reactively from spawn/despawn and interaction events.
 *
 * <h2>Event subscription map</h2>
 *
 * <p>Each handler is a single {@code @Subscribe} method on the listener
 * (RuneLite requires them on the plugin class itself, so the actual
 * {@code @Subscribe} methods live on {@link com.systeminterface.core.SystemInterfacePlugin}
 * and delegate one-liners into the listener for testability).
 *
 * <table>
 *   <caption>RuneLite events consumed by this package</caption>
 *   <tr><th>Event</th><th>Package</th><th>Why</th></tr>
 *
 *   <tr>
 *     <td>{@code GameStateChanged}</td>
 *     <td>{@code net.runelite.api.events}</td>
 *     <td>Detect {@code LOGGED_IN} / {@code LOGIN_SCREEN} transitions to load
 *         the per-RSN JSON, flush on logout, and trigger first-time backfill.</td>
 *   </tr>
 *
 *   <tr>
 *     <td>{@code AccountHashChanged}</td>
 *     <td>{@code net.runelite.api.events}</td>
 *     <td>Jagex multi-account switching — re-keys all state to the new profile.</td>
 *   </tr>
 *
 *   <tr>
 *     <td>{@code ChatMessage}</td>
 *     <td>{@code net.runelite.api.events}</td>
 *     <td>Parse {@code GAMEMESSAGE} text for
 *       <ul>
 *         <li>{@code "Your <NPC> kill count is: <N>"} → authoritative KC update</li>
 *         <li>{@code "New item added to your collection log: <item>"} → collection-log progress</li>
 *         <li>{@code "You have a funny feeling..."} / pet drop announcements</li>
 *         <li>Boss-specific drop / completion lines</li>
 *       </ul>
 *     </td>
 *   </tr>
 *
 *   <tr>
 *     <td>{@code InteractingChanged}</td>
 *     <td>{@code net.runelite.api.events}</td>
 *     <td>When the local player starts/stops interacting with an NPC, this is
 *         what surfaces the System Panel for the right target. Only fire on
 *         the local player's {@code source}.</td>
 *   </tr>
 *
 *   <tr>
 *     <td>{@code ActorDeath}</td>
 *     <td>{@code net.runelite.api.events}</td>
 *     <td>Authoritative confirmation that an NPC actually died — more reliable
 *         than despawn (despawn can fire for non-death reasons).</td>
 *   </tr>
 *
 *   <tr>
 *     <td>{@code NpcLootReceived}</td>
 *     <td>{@code net.runelite.client.events} <em>(note: client, not api)</em></td>
 *     <td>Drop received from an NPC kill — per-item, with quantities. The primary
 *         live source for {@link com.systeminterface.services.state.DropOccurrence}s.</td>
 *   </tr>
 *
 *   <tr>
 *     <td>{@code VarbitChanged}</td>
 *     <td>{@code net.runelite.api.events}</td>
 *     <td>Some bosses store KC in a varbit/varplayer. When the relevant varbit
 *         changes, read its value as another source for {@code observeKillCount}.
 *         Cheap cross-check against chat parsing.</td>
 *   </tr>
 *
 *   <tr>
 *     <td>{@code WidgetLoaded}</td>
 *     <td>{@code net.runelite.api.events}</td>
 *     <td>When the Collection Log interface opens, walk its widgets to recover
 *         the player's collection-log state — gives us "✓ already unlocked"
 *         info for free without scraping screenshots.</td>
 *   </tr>
 * </table>
 *
 * <h2>Events deliberately NOT subscribed to</h2>
 *
 * <ul>
 *   <li>{@code GameTick} / {@code ClientTick} / {@code BeforeRender} — per-tick
 *       and per-frame scans are forbidden by the agent rules. State is updated
 *       reactively from the events above.</li>
 *   <li>{@code MenuOptionClicked} / {@code MenuEntryAdded} — we do not modify
 *       menus or send actions to the server (also forbidden).</li>
 *   <li>{@code HitsplatApplied} — useful for DPS tracking; reserved for a
 *       future post-MVP Combat Expansion, not the contextual panel.</li>
 * </ul>
 */
package com.systeminterface.core;

