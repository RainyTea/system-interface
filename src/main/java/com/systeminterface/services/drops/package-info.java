/**
 * Loot table data model and registry.
 *
 * <p>Pure data — no game-state reads, no I/O at construction time. JSON loot
 * tables shipped as plugin resources and (optionally) user overrides from
 * {@code .runelite/system-interface/} are parsed into immutable structures
 * here, and the rest of the plugin consumes them through {@code LootTables}.
 */
package com.systeminterface.services.drops;

