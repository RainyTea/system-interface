/**
 * Per-target gameplay state — kill counts, dry streaks, collection log progress.
 *
 * <p>This is the only package that owns mutable state. Everything else (math,
 * loot tables, overlay) reads from it. Persistence lives here too: state is
 * written to {@code .runelite/system-interface/&lt;rsn&gt;.json} via the
 * injected {@code Gson} so it survives client restarts.
 *
 * <p>The state tracker is the single source of truth fed by
 * {@link com.systeminterface.core} events and consumed by
 * {@link com.systeminterface.modules.ui} renderers.
 *
 * <h2>Two state dimensions: current KC vs per-drop KC history</h2>
 *
 * <p>The spec requires both:
 * <ul>
 *   <li><b>Current KC</b> — for "Kill Count: 1,284" and the live probability /
 *       still-dry calculations.</li>
 *   <li><b>Per-drop KC history</b> — for "Current Dry Streak: 1,284 kills"
 *       (needs the KC at the last drop), for "Luckier than 91.4% of players"
 *       (needs the KC at which the drop was received), and for the
 *       Collection Log "✓ at KC 264" annotations.</li>
 * </ul>
 *
 * <p>{@link com.systeminterface.services.state.TargetState} stores both: a single
 * {@code currentKc} and a list of
 * {@link com.systeminterface.services.state.DropOccurrence} records ({@code dropName +
 * kc + timestamp + KcSource}). The dry streak for a drop is then
 * {@code currentKc − maxKc(dropName)} (or {@code currentKc} if never seen).
 *
 * <h2>Where current KC comes from</h2>
 *
 * <p>{@link com.systeminterface.services.state.StateTracker} merges KC values from
 * multiple sources, taking {@code max} since KC is monotonically
 * non-decreasing (slower sources never regress a faster one). Sources, in
 * decreasing real-time-ness:
 *
 * <ol>
 *   <li><b>In-game chat parsing.</b> Bosses print
 *       {@code "Your <NPC> kill count is: <N>"} on every kill. Subscribe to
 *       {@link net.runelite.api.events.ChatMessage} with type
 *       {@code ChatMessageType.GAMEMESSAGE} and parse with a regex. The
 *       server itself is telling us the number — authoritative.</li>
 *
 *   <li><b>RuneLite's bundled {@code chatcommands} plugin config.</b> Already
 *       records boss KCs per RuneScape profile under the {@code "chatcommands"}
 *       config group. Read via
 *       {@code configManager.getRSProfileConfiguration("chatcommands",
 *       "killcount." + boss.toLowerCase(), int.class)} on startup and on
 *       {@code RuneScapeProfileChanged}. Zero-cost baseline.</li>
 *
 *   <li><b>OSRS Hiscores API.</b>
 *       {@code https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player=&lt;RSN&gt;}
 *       returns a CSV with KCs for every hiscored boss. Fetched off the
 *       client thread via {@code OkHttpClient.enqueue()}. Triggered once per
 *       RSN on first sight, or behind a manual "Sync" config button.
 *       <b>First-party Jagex server</b> — no warning required.</li>
 *
 *   <li><b>Our own persisted JSON</b> at
 *       {@code .runelite/system-interface/&lt;rsn&gt;.json}. Loaded on
 *       {@code startUp()}, flushed on every change and on {@code shutDown()}.
 *       Cross-session continuity + per-drop history (the only source that
 *       persists that for us).</li>
 * </ol>
 *
 * <h2>Where per-drop KC history comes from (the "don't start from zero" sources)</h2>
 *
 * <p>To avoid the plugin behaving as if every drop just happened, on first
 * run we backfill from every local source we can find. Inspired by the
 * "Drop History" plugin's import strategy. All reads stay inside
 * {@code .runelite/} per the agent rules.
 *
 * <ol>
 *   <li><b>Loot Tracker logs</b> at {@code ~/.runelite/loots/} — exact KCs
 *       written by the built-in Loot Tracker plugin if the player has ever
 *       enabled it. The most accurate offline source. (Format must be
 *       verified at implementation time; treat as best-effort parsing.)</li>
 *
 *   <li><b>Boss kill screenshots</b> at
 *       {@code ~/.runelite/screenshots/&lt;rsn&gt;/Boss Kills/}, saved by
 *       RuneLite's Screenshot plugin with the pattern
 *       {@code &lt;BossName&gt;(&lt;KC&gt;).png}. Parse with the regex
 *       {@code ^(.+?)\((\d+)\)\.png$}. The file's modification time gives the
 *       date, allowing matching against collection log screenshots.</li>
 *
 *   <li><b>Collection log screenshots</b> at
 *       {@code ~/.runelite/screenshots/&lt;rsn&gt;/Collection Log/}, saved by
 *       the Screenshot plugin when a new collection log entry is unlocked.
 *       Gives item + date. KC is recovered by matching the screenshot's
 *       timestamp against the nearest Boss Kill screenshot for that NPC.</li>
 *
 *   <li><b>Wise Old Man API</b> ({@code https://api.wiseoldman.net}) —
 *       provides KC snapshots over time, allowing estimation of the KC at
 *       any historical date (used to back-fill drops where we only have a
 *       date from a screenshot, not a KC). <b>3rd-party server</b> per
 *       {@code AGENTS.md}: must be {@code @ConfigItem(warning = "This
 *       feature submits your IP address to a 3rd-party server not controlled
 *       or verified by RuneLite developers")} and disabled by default.
 *       Estimates surfaced in the UI with a clear marker (e.g.
 *       {@code "KC ~847 (est.)"}).</li>
 *
 *   <li><b>Going forward:</b>
 *       {@link net.runelite.client.events.NpcLootReceived} + the current KC
 *       at the moment of the event give exact per-drop KC for every future
 *       drop, with no estimation needed.</li>
 * </ol>
 *
 * <p>Backfill runs <em>once per RSN</em> on first sight, retries on every
 * startup until data is found (so installing the plugin before the data
 * exists is harmless), and merges into existing state without overwriting
 * newer/more-accurate records.
 *
 * <h2>Drops captured going forward</h2>
 *
 * <ul>
 *   <li>{@link net.runelite.client.events.NpcLootReceived} — the most reliable
 *       per-NPC drop event; gives the killed NPC and the items received.</li>
 *   <li>{@code ChatMessageType.GAMEMESSAGE} parsing for collection-log
 *       additions ({@code "New item added to your collection log:"}) and
 *       boss-specific drop announcements (e.g. {@code "You have a funny feeling..."}
 *       for pets, special clue/drop notifications).</li>
 * </ul>
 *
 * <h2>Threading rules</h2>
 *
 * <ul>
 *   <li>Hiscores + Wise Old Man fetches: OkHttp threadpool only. Never on the
 *       client thread.</li>
 *   <li>Disk reads/writes (loot logs, screenshots, our own JSON): an injected
 *       {@code ScheduledExecutorService} or the OkHttp dispatcher's executor.
 *       Never on the client thread.</li>
 *   <li>State mutations from background threads must funnel through a
 *       lock-protected method or be marshalled back via
 *       {@code clientThread.invoke()} before reading game-state.</li>
 * </ul>
 */
package com.systeminterface.services.state;

