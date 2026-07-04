/**
 * The System Panel — the MMORPG-style overlay rendered to the game viewport.
 *
 * <p>This package is the only place that talks to {@code Graphics2D}. It
 * consumes pre-computed values from {@link com.systeminterface.state} and
 * {@link com.systeminterface.probability}; it must not do math, I/O, or
 * scene scans inside {@code render()}. Per the agent rules, the render path
 * runs every frame — keep it cheap and pre-compute on state changes.
 *
 * <p>Visual direction (from the handover): semi-transparent dark panel,
 * glowing border, anime/system-UI feel, contextual visibility tied to the
 * currently interacted target.
 *
 * <h2>Overlay base class choice</h2>
 *
 * <p>RuneLite ships several overlay base classes
 * ({@code net.runelite.client.ui.overlay.*}):
 * <ul>
 *   <li>{@code Overlay} — raw, draw whatever you want.</li>
 *   <li>{@code OverlayPanel} — purpose-built for stacked text-line panels
 *       (RuneLite's {@code AttackStylesOverlay} is the canonical example).
 *       Handles the dark translucent background and per-line layout for us.</li>
 *   <li>{@code WidgetItemOverlay} — for drawing on inventory items
 *       (not needed for the System Panel; possibly useful later for
 *       collection-log overlays).</li>
 * </ul>
 *
 * <p>{@link com.systeminterface.modules.ui.SystemPanelOverlay} should extend
 * {@code OverlayPanel}. Pre-compute the {@code LineComponent}s on every
 * state-tracker mutation (not in {@code render()}), then {@code render()}
 * just stamps the prepared list.
 *
 * <h2>Future: PluginPanel (sidebar)</h2>
 *
 * <p>Post-MVP, the "Account-Wide System Dashboard" feature will live as a
 * Swing {@code PluginPanel} pinned to the sidebar (the same family as the
 * built-in Loot Tracker panel). Not in scope yet — flagged here so the
 * overlay package stays focused on the viewport panel.
 */
package com.systeminterface.modules.ui;

