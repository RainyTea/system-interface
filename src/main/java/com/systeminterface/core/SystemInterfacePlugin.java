package com.systeminterface.core;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.core.InteractionListener;
import com.systeminterface.services.lookup.BossAliases;
import com.systeminterface.services.profit.ItemValuer;
import com.systeminterface.services.profit.ProfitTracker;
import com.systeminterface.services.portrait.PortraitService;
import com.systeminterface.modules.ui.AnalyzeOverlay;
import com.systeminterface.modules.ui.ItemHoverOverlay;
import com.systeminterface.modules.ui.SkillingOverlay;
import com.systeminterface.modules.ui.ActiveOverlay;
import com.systeminterface.modules.ui.CollapseStateStore;
import com.systeminterface.modules.ui.SystemInterfacePanel;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.services.state.BackfillService;
import com.systeminterface.services.state.SessionTotals;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.Actor;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.xptracker.XpTrackerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.plugins.xptracker.XpTrackerService;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "System Interface"
)
@PluginDependency(XpTrackerPlugin.class)
public class SystemInterfacePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ActiveOverlay overlay;

	@Inject
	private AnalyzeOverlay analyzeOverlay;

	@Inject
	private ItemHoverOverlay itemHoverOverlay;

	@Inject
	private StateTracker stateTracker;

	@Inject
	private InteractionListener listener;

	@Inject
	private LootTables lootTables;

	@Inject
	private com.systeminterface.services.lookup.ItemMembership itemMembership;

	@Inject
	private com.systeminterface.services.lookup.ItemNameCache itemNameCache;

	@Inject
	private com.systeminterface.services.lookup.HeldItemCache heldItemCache;

	@Inject
	private com.systeminterface.services.lookup.ObjectExamineService objectExamineService;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private okhttp3.OkHttpClient okHttpClient;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ProfitTracker profitTracker;

	@Inject
	private PortraitService portraitService;

	@Inject
	private BackfillService backfillService;

	@Inject
	private SystemInterfaceConfig config;

	@Inject
	private SkillTracker skillTracker;

	@Inject
	private SessionTotals sessionTotals;

	@Inject
	private SkillingOverlay skillingOverlay;

	@Inject
	private ResourceData resourceData;

	@Inject
	private XpTrackerService xpTrackerService;

	@Inject
	private Gson gson;

	private SystemInterfacePanel panel;
	private NavigationButton navButton;

	private String pendingDeathNpc;
	private int pendingDeathTicks;
	private long lastSkillGeneration = -1;

	@Override
	protected void startUp()
	{
		log.debug("System Interface started");
		objectExamineService.loadCurated();
		objectExamineService.loadLocal();
		overlayManager.add(overlay);
		overlayManager.add(analyzeOverlay);
		overlayManager.add(itemHoverOverlay);
		overlayManager.add(skillingOverlay);
		skillTracker.setXpTrackerService(xpTrackerService);
		mouseManager.registerMouseListener(analyzeOverlay.getMouseListener());

		stateTracker.setProfileListener(new StateTracker.ProfileListener()
		{
			@Override
			public void onProfileLoaded(StateTracker.PersistedState state)
			{
				skillTracker.loadFromState(state.skills);
			}

			@Override
			public void onProfileSaving(StateTracker.PersistedState state)
			{
				state.skills = skillTracker.toPersistence();
			}

			@Override
			public void onProfileCleared()
			{
				skillTracker.onProfileChanged();
			}
		});

		CollapseStateStore collapseStateStore = new CollapseStateStore(gson, configManager, SystemInterfaceConfig.GROUP);
		panel = new SystemInterfacePanel(stateTracker, lootTables, itemManager, configManager, portraitService, itemMembership, itemNameCache, collapseStateStore, skillTracker, resourceData, sessionTotals, clientThread, config);
		// Re-render once the members index is ready, so the F2P filter can apply.
		itemMembership.setOnReady(() -> panel.refresh());
		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		}
		catch (Exception e)
		{
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = icon.createGraphics();
			g.setColor(new java.awt.Color(120, 200, 255));
			g.fillRect(1, 1, 14, 14);
			g.setColor(java.awt.Color.WHITE);
			g.drawString("S", 3, 13);
			g.dispose();
		}
		navButton = NavigationButton.builder()
			.tooltip("System Interface")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		chatCommandManager.registerCommand(APPRAISE_COMMAND, this::onAppraiseCommand);
		chatCommandManager.registerCommand(STATUS_COMMAND, this::onStatusCommand);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::tryActivateProfile);
			clientThread.invoke(this::updateWorldType);
		}
	}

	@Override
	protected void shutDown()
	{
		log.debug("System Interface stopped");
		overlayManager.remove(overlay);
		overlayManager.remove(analyzeOverlay);
		overlayManager.remove(itemHoverOverlay);
		overlayManager.remove(skillingOverlay);
		mouseManager.unregisterMouseListener(analyzeOverlay.getMouseListener());
		chatCommandManager.unregisterCommand(APPRAISE_COMMAND);
		chatCommandManager.unregisterCommand(STATUS_COMMAND);
		clientToolbar.removeNavigation(navButton);
		stateTracker.flushSync();
		stateTracker.setActiveProfile(null);
		stateTracker.setProfileListener(null);
	}

	// ---------------------------------------------------------------------
	// Subscriptions — each one delegates to the listener.
	// ---------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::tryActivateProfile);
			// World may have changed (F2P <-> members) on a hop — recompute on the
			// client thread and re-render so members-only filtering reflects it.
			updateWorldType();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			stateTracker.setActiveProfile(null);
			profitTracker.reset();
			if (panel != null)
			{
				panel.setFreeWorld(false);
			}
			// Clear the combat overlay's target so it doesn't reappear with stale state on
			// the next login.
			overlay.setCurrentTarget(null, null, 0);
			overlay.setFreeWorld(false);
		}
		listener.onGameStateChanged();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Re-apply panel layout toggles (e.g. the Loot Log section show/hide) when a
		// System Interface setting changes. Overlays read their own toggles each frame.
		if (SystemInterfaceConfig.GROUP.equals(event.getGroup()) && panel != null)
		{
			panel.applyConfigToggles();
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			skillTracker.onAnimationChanged(client.getLocalPlayer().getAnimation());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		listener.onChatMessage(event);
		skillTracker.onChatMessage(Text.removeTags(event.getMessage()));
		if (event.getType() == ChatMessageType.OBJECT_EXAMINE)
		{
			objectExamineService.onObjectExamineMessage(Text.removeTags(event.getMessage()), client.getTickCount());
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Feeds the skilling provenance gate: a tracked-skill XP gain confirms a gather action,
		// so a coincident inventory increase is real loot (vs a GE collect / bank withdrawal).
		skillTracker.onStatChanged(event);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		listener.onInteractingChanged(event);
		if (panel != null && event.getSource() == client.getLocalPlayer()
			&& event.getTarget() instanceof net.runelite.api.NPC)
		{
			net.runelite.api.NPC npc = (net.runelite.api.NPC) event.getTarget();
			String name = npc.getName();
			// Skilling precedence: fishing spots are NPCs. Skip the combat-select so fishing doesn't
			// pop the Combat section open for a frame before the next tick's skilling clears it.
			if (name != null && !skillTracker.isFishingSpot(npc.getId()))
			{
				panel.setCurrentTarget(name);
			}
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		listener.onNpcLootReceived(event);
		if (event.getNpc() != null && event.getNpc().getName() != null)
		{
			String name = event.getNpc().getName();
			if (name.equals(pendingDeathNpc))
			{
				pendingDeathNpc = null;
			}
			final WorldPoint loc = event.getNpc().getWorldLocation();
			profitTracker.onLoot(name, event.getItems(), loc);
		}
		if (panel != null)
		{
			panel.refresh();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getInteracting() != actor)
		{
			return;
		}
		String name = actor.getName();
		if (name != null)
		{
			pendingDeathNpc = name;
			pendingDeathTicks = 2;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		skillTracker.onGameTick();

		// Reconcile combat loot provenance: a drop whose ground stack despawned this tick alongside a
		// same-item re-pickup restores the original target's kept count.
		final boolean profitChanged = profitTracker.onGameTick(client.getTickCount());
		if (profitChanged && panel != null)
		{
			panel.refreshProfitOnly();
		}

		final long skillGen = skillTracker.getGeneration();
		if (skillGen != lastSkillGeneration)
		{
			lastSkillGeneration = skillGen;
			if (panel != null)
			{
				panel.refreshSkillingOnly();
			}
		}

		// Keep the side-panel skilling rates (XP/hr, time-to-level, resources/hr) live
		// between section rebuilds, without rebuilding the section on a timer.
		if (panel != null)
		{
			panel.updateSkillingLiveStats();
		}

		// Feed the ActivityFocus state machine: the NPC the local player is fighting (excluding
		// fishing-spot NPCs, which are handled as skilling), or null. The coordinator applies
		// skilling precedence and confines all focus mutation to the EDT.
		if (panel != null)
		{
			Player local = client.getLocalPlayer();
			Actor interacting = local != null ? local.getInteracting() : null;
			String liveCombatTarget = null;
			if (interacting instanceof NPC)
			{
				NPC npc = (NPC) interacting;
				if (!skillTracker.isFishingSpot(npc.getId()))
				{
					liveCombatTarget = npc.getName();
				}
			}
			panel.onGameTick(System.currentTimeMillis(), liveCombatTarget);
		}

		if (pendingDeathNpc != null)
		{
			if (--pendingDeathTicks <= 0)
			{
				String name = pendingDeathNpc;
				pendingDeathNpc = null;
				listener.onNoDropKill(name);
				if (panel != null)
				{
					panel.refresh();
				}
			}
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		// Resolves my own dropped resources: a despawn that coincides with a re-pickup restores the
		// kept count; a timeout makes the earlier drop deduction final. The tracker ignores despawns
		// that aren't at one of my drop tiles.
		skillTracker.onItemDespawned(event);
		final net.runelite.api.TileItem despawnedItem = event.getItem();
		if (despawnedItem != null)
		{
			final WorldPoint loc = event.getTile() == null ? null : event.getTile().getWorldLocation();
			profitTracker.onItemDespawned(despawnedItem.getId(), despawnedItem.getQuantity(), loc,
				client.getTickCount());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final int id = event.getContainerId();
		if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			heldItemCache.refresh(
				client.getItemContainer(InventoryID.INV),
				client.getItemContainer(InventoryID.WORN));
		}
		if (id == InventoryID.INV)
		{
			profitTracker.onInventoryChanged(event.getItemContainer());
			skillTracker.onInventoryChanged(event.getItemContainer());
			if (panel != null)
			{
				panel.refreshProfitOnly();
			}
		}
	}

	private static boolean isItemRemovalAction(String option)
	{
		if (option == null)
		{
			return false;
		}
		switch (option)
		{
			case "Drop":
			case "Bury":
			case "Scatter":
			case "Eat":
			case "Drink":
			case "Empty":
				return true;
			default:
				return false;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() == MenuAction.EXAMINE_OBJECT)
		{
			objectExamineService.recordPendingExamine(event.getId(), Text.removeTags(event.getMenuTarget()), client.getTickCount());
		}

		final MenuAction action = event.getMenuAction();
		if (action == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION
			|| action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION)
		{
			final int objectId = event.getId();
			if (!resourceData.forObjectId(objectId).isEmpty())
			{
				skillTracker.setActiveObject(objectId);
			}
		}

		if (action == MenuAction.NPC_FIRST_OPTION
			|| action == MenuAction.NPC_SECOND_OPTION
			|| action == MenuAction.NPC_THIRD_OPTION
			|| action == MenuAction.NPC_FOURTH_OPTION
			|| action == MenuAction.NPC_FIFTH_OPTION)
		{
			final NPC npc = event.getMenuEntry().getNpc();
			if (npc != null)
			{
				final String method = resourceData.resolveFishingMethod(npc.getId(), event.getMenuOption());
				if (method != null)
				{
					skillTracker.setActiveFishingMethod(method);
				}
			}
		}

		if (!isItemRemovalAction(event.getMenuOption()))
		{
			return;
		}
		final ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null)
		{
			return;
		}
		final Item item = inv.getItem(event.getParam0());
		if (item != null && item.getId() >= 0)
		{
			if ("Drop".equals(event.getMenuOption()))
			{
				// A real ground drop: subtract now, but a same-tick despawn/re-pickup can restore it.
				final Player local = client.getLocalPlayer();
				final WorldPoint loc = local == null ? null : local.getWorldLocation();
				profitTracker.onItemDropped(item.getId(), item.getQuantity(), event.getParam0(), loc,
					client.getTickCount());
			}
			else
			{
				// Bury/Eat/Drink/etc.: the loot is gone for good — finalize the kept loss.
				profitTracker.onItemRemovedFinalized(item.getId(), item.getQuantity(), event.getParam0());
			}
			skillTracker.onItemDropped(item.getId(), item.getQuantity());
			if (panel != null)
			{
				panel.refreshProfitOnly();
				panel.refreshSkillingOnly();
			}
		}
	}

	// ---------------------------------------------------------------------
	// Right-click "Analyze" on NPCs
	// ---------------------------------------------------------------------

	private static final String ANALYZE = "Appraise";

	/** Chat command that opens the Appraise window for a named NPC/boss, e.g. {@code !appraise Corp}. */
	private static final String APPRAISE_COMMAND = "!appraise";
	private static final String STATUS_COMMAND = "!status";

	/**
	 * Handles {@code !appraise <name>} typed by the local player. Resolves boss
	 * shorthand via {@link BossAliases}, then title-cases the argument (so
	 * "general graardor" matches the "General Graardor" wiki page), triggers a
	 * drop-table lookup, and opens the in-game Appraise overlay.
	 *
	 * <p>{@link ChatCommandManager} fires for every matching message — including
	 * other players' — so we ignore any message that isn't from us, otherwise a
	 * stranger typing {@code !appraise} would pop open our overlay.
	 */
	private void onAppraiseCommand(ChatMessage chatMessage, String message)
	{
		final Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}
		final String sender = Text.sanitize(Text.removeTags(chatMessage.getName()));
		if (!local.getName().equals(sender))
		{
			return;
		}

		// Strip the command word; whatever follows is the target name.
		final int cmdEnd = message.toLowerCase().indexOf(APPRAISE_COMMAND) + APPRAISE_COMMAND.length();
		final String arg = message.substring(cmdEnd).trim();
		if (arg.isEmpty())
		{
			return;
		}

		// Only appraise recognised entities: a known boss (alias map) or an NPC
		// already in the player's bestiary. This stops "!appraise <anything>"
		// from opening a blank window for a non-existent entity.
		final String canonical = BossAliases.canonicalize(arg);
		final String target;
		if (canonical != null)
		{
			target = canonical;
		}
		else
		{
			final String known = resolveBestiaryName(arg);
			if (known == null)
			{
				return;
			}
			target = known;
		}

		// Kick off the (opt-in) wiki fetch so the table is ready for the next frame,
		// then show the Appraise overlay and prime the side panel.
		lootTables.forTarget(target);
		analyzeOverlay.analyze(target);
		if (panel != null)
		{
			panel.setCurrentTarget(target);
		}
	}

	private void onStatusCommand(ChatMessage chatMessage, String message)
	{
		final Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}
		final String sender = Text.sanitize(Text.removeTags(chatMessage.getName()));
		if (!local.getName().equals(sender))
		{
			return;
		}
		analyzeOverlay.analyzeStatus(local.getName(), local.getCombatLevel());
	}

	/**
	 * Finds an entity in the player's bestiary by case-insensitive name — a
	 * target we've tracked or one we have a loaded drop table for — returning
	 * its canonical stored name, or {@code null} if it isn't known. (Known
	 * bosses are recognised separately via {@link BossAliases}.)
	 */
	private String resolveBestiaryName(String input)
	{
		for (TargetState target : stateTracker.getTrackedTargets())
		{
			if (target.getName().equalsIgnoreCase(input))
			{
				return target.getName();
			}
		}
		for (String name : lootTables.registeredTargets())
		{
			if (name.equalsIgnoreCase(input))
			{
				return name;
			}
		}
		return null;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		final boolean replace = config.replaceExamine();

		// NPC: hook the Examine entry.
		if (event.getType() == MenuAction.EXAMINE_NPC.getId())
		{
			final NPC npc = event.getMenuEntry().getNpc();
			if (npc == null || npc.getName() == null)
			{
				return;
			}

			// Fishing spot? Appraise it as a resource node (lists all fish it offers) rather
			// than as a combat NPC.
			final java.util.List<ResourceData.ResourceEntry> fishes = resourceData.forNpcId(npc.getId());
			if (!fishes.isEmpty())
			{
				final String target = event.getTarget();
				final String cleanName = Text.removeTags(target);
				if (replace)
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeResource(cleanName, fishes));
				}
				else
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(event.getIdentifier())
						.onClick(e -> analyzeOverlay.analyzeResource(cleanName, fishes));
				}
				return;
			}

			if (replace)
			{
				event.getMenuEntry()
					.setOption(ANALYZE)
					.setType(MenuAction.RUNELITE)
					.onClick(e ->
					{
						storeNpcCombatLevel(npc);
						analyzeOverlay.analyze(npc.getName());
					});
			}
			else
			{
				client.getMenu().createMenuEntry(-1)
					.setOption(ANALYZE)
					.setTarget(event.getTarget())
					.setType(MenuAction.RUNELITE)
					.setIdentifier(event.getIdentifier())
					.onClick(e ->
					{
						storeNpcCombatLevel(npc);
						analyzeOverlay.analyze(npc.getName());
					});
			}
			return;
		}

		// Game object (trees, rocks, etc.): hook Examine for resource nodes.
		if (event.getType() == MenuAction.EXAMINE_OBJECT.getId())
		{
			final int objectId = event.getIdentifier();
			final java.util.List<ResourceData.ResourceEntry> entries = resourceData.forObjectId(objectId);
			final String target = event.getTarget();
			final String cleanName = Text.removeTags(target);
			if (!entries.isEmpty())
			{
				if (replace)
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeResource(cleanName, entries));
				}
				else
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(objectId)
						.onClick(e -> analyzeOverlay.analyzeResource(cleanName, entries));
				}
				return;
			}

			// Not a resource node: fall back to the unified object-examine lookup
			// (curated bundle -> locally observed -> wiki, learned over time).
			final String text = objectExamineService.getExamine(objectId);
			if (text != null)
			{
				if (replace)
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeObject(cleanName, text));
				}
				else
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(objectId)
						.onClick(e -> analyzeOverlay.analyzeObject(cleanName, text));
				}
			}
			else
			{
				// Unknown to us — leave the native Examine entry untouched and kick off a
				// best-effort async wiki fetch so it's learned for next time.
				objectExamineService.fetchWiki(objectId, cleanName);
			}
			return;
		}

		// Ground item: hook the Examine entry.
		if (event.getType() == MenuAction.EXAMINE_ITEM_GROUND.getId())
		{
			final int id = event.getIdentifier();
			final String target = event.getTarget();
			if (id >= 0 && target != null)
			{
				final String cleanName = Text.removeTags(target);
				if (replace)
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeItem(cleanName, id));
				}
				else
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(id)
						.onClick(e -> analyzeOverlay.analyzeItem(cleanName, id));
				}
			}
			return;
		}

		// Inventory / equipment / widget items: hook CC_OP_LOW_PRIORITY and CC_OP
		// ("Examine"). CC_OP_LOW_PRIORITY covers inventory items; CC_OP covers
		// equipment tab and equipment viewer items.
		if (event.getType() == MenuAction.CC_OP_LOW_PRIORITY.getId()
			|| event.getType() == MenuAction.CC_OP.getId())
		{
			final int id = event.getMenuEntry().getItemId();
			if (id >= 0 && "Examine".equals(event.getOption()) && !analyzeEntryExists())
			{
				final String target = event.getTarget();
				final String cleanName = Text.removeTags(target);
				if (replace)
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeItem(cleanName, id));
				}
				else
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(event.getIdentifier())
						.onClick(e -> analyzeOverlay.analyzeItem(cleanName, id));
				}
			}
			return;
		}

		// Player: always add as extra entry (players have no native Examine to replace).
		final net.runelite.api.Player player = event.getMenuEntry().getPlayer();
		if (player != null && player.getName() != null && !analyzeEntryExists())
		{
			final String name = player.getName();
			final int combatLevel = player.getCombatLevel();
			client.getMenu().createMenuEntry(-1)
				.setOption(ANALYZE)
				.setTarget(event.getTarget())
				.setType(MenuAction.RUNELITE)
				.setIdentifier(event.getIdentifier())
				.onClick(e -> analyzeOverlay.analyzePlayer(name, combatLevel));
		}
	}

	private void storeNpcCombatLevel(NPC npc)
	{
		String name = npc.getName();
		int combatLevel = npc.getCombatLevel();
		if (name != null && combatLevel > 0)
		{
			stateTracker.setCombatLevel(name, combatLevel);
		}
	}

	/** True if the current menu already contains our Analyze entry (avoids duplicates). */
	private boolean analyzeEntryExists()
	{
		for (MenuEntry entry : client.getMenu().getMenuEntries())
		{
			if (ANALYZE.equals(entry.getOption()))
			{
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------
	// Profile activation — deferred until the local player's name is known.
	// Returns true once the profile is set so clientThread.invokeLater stops retrying.
	// ---------------------------------------------------------------------
	private boolean tryActivateProfile()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return true; // bail; we'll get another LOGGED_IN event later
		}
		Player p = client.getLocalPlayer();
		if (p == null || p.getName() == null)
		{
			return false; // retry next tick
		}
		stateTracker.setActiveProfile(p.getName());
		heldItemCache.refreshNow();
		log.debug("Profile activated for '{}', running backfill", p.getName());
		Runnable refreshPanel = () ->
		{
			if (panel != null)
			{
				SwingUtilities.invokeLater(() -> panel.refresh());
			}
		};
		backfillService.run(stateTracker, configManager, p.getName(), stateTracker.knownTargetNames(), refreshPanel);
		refreshPanel.run();
		return true;
	}

	/**
	 * Recomputes whether the current world is free-to-play and pushes it to the
	 * panel. Must run on the client thread (reads {@link Client#getWorldType()}),
	 * which is why callers route through {@code clientThread} or the event bus.
	 */
	private void updateWorldType()
	{
		if (panel == null)
		{
			return;
		}
		final boolean free = client.getGameState() == GameState.LOGGED_IN
			&& !client.getWorldType().contains(WorldType.MEMBERS);
		log.debug("World type {} -> freeWorld={}", client.getWorldType(), free);
		panel.setFreeWorld(free);
		overlay.setFreeWorld(free);
	}

	@Provides
	SystemInterfaceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SystemInterfaceConfig.class);
	}

	@Provides
	ResourceData provideResourceData(Gson gson)
	{
		return ResourceData.load(gson);
	}

	@Provides
	ItemValuer provideItemValuer(ItemManager itemManager)
	{
		return itemId ->
		{
			if (itemId == ItemID.COINS)
			{
				return 1;
			}
			if (itemId == ItemID.PLATINUM)
			{
				return 1000;
			}
			return Math.max(0, itemManager.getItemPrice(itemId));
		};
	}
}



