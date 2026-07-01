package com.systeminterface.core;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.core.InteractionListener;
import com.systeminterface.services.lookup.BossAliases;
import com.systeminterface.services.lookup.ItemExamineService;
import com.systeminterface.services.lookup.ObjectExamineService;
import com.systeminterface.services.lookup.ObjectWikiExamineService;
import com.systeminterface.services.profit.ProfitTracker;
import com.systeminterface.services.portrait.PortraitService;
import com.systeminterface.modules.ui.AnalyzeOverlay;
import com.systeminterface.modules.ui.ItemHoverOverlay;
import com.systeminterface.modules.ui.SkillingOverlay;
import com.systeminterface.modules.ui.SystemPanelOverlay;
import com.systeminterface.modules.ui.CollapseStateStore;
import com.systeminterface.modules.ui.SystemInterfacePanel;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.services.state.BackfillService;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldType;
import net.runelite.api.Actor;
import net.runelite.api.coords.WorldPoint;
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
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
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
	private SystemPanelOverlay overlay;

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
	private ItemExamineService itemExamineService;

	@Inject
	private ObjectExamineService objectExamineService;

	@Inject
	private ObjectWikiExamineService objectWikiExamineService;

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
	private SkillingOverlay skillingOverlay;

	@Inject
	private ResourceData resourceData;

	@Inject
	private XpTrackerService xpTrackerService;

	@Inject
	private Gson gson;

	private SystemInterfacePanel panel;
	private NavigationButton navButton;

	private static final int NO_DROP_KILL_GRACE_TICKS = 6;

	private String pendingDeathNpc;
	private Actor pendingDeathActor;
	private int pendingDeathTicks;
	private String recentLootNpc;
	private Actor recentLootActor;
	private int recentLootTick = Integer.MIN_VALUE;
	private SkillActionContext recentSkillActionContext;
	private int recentSkillActionTick = Integer.MIN_VALUE;
	private long lastSkillGeneration = -1;
	private PendingObjectExamine pendingObjectExamine;
	private final Map<String, String> rememberedNpcActions = new HashMap<>();
	private final java.util.Set<String> pickpocketCapableSources = new java.util.HashSet<>();

	@Override
	protected void startUp()
	{
		log.debug("System Interface started");
		overlayManager.add(overlay);
		overlayManager.add(analyzeOverlay);
		overlayManager.add(itemHoverOverlay);
		overlayManager.add(skillingOverlay);
		skillTracker.setXpTrackerService(xpTrackerService);
		mouseManager.registerMouseListener(analyzeOverlay.getMouseListener());
		clientThread.invoke(skillTracker::refreshHeldItems);
		itemExamineService.warmUpAsync(config.enableWikiLookup());
		objectExamineService.warmUpAsync();

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
		panel = new SystemInterfacePanel(stateTracker, lootTables, itemManager, configManager, portraitService, itemMembership, collapseStateStore, skillTracker, resourceData, config);
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
			skillTracker.refreshHeldItems();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			stateTracker.setActiveProfile(null);
			profitTracker.reset();
			pendingDeathNpc = null;
			pendingDeathActor = null;
			recentLootNpc = null;
			recentLootActor = null;
			recentLootTick = Integer.MIN_VALUE;
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
		capturePendingObjectExamine(event);
		final String cleanMessage = Text.removeTags(event.getMessage());
		skillTracker.onChatMessage(cleanMessage);
		final String popup = systemPopupTextForMessage(cleanMessage);
		if (popup != null && overlay != null)
		{
			overlay.showSystemMessage(popup);
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
			&& !(event.getTarget() instanceof net.runelite.api.NPC))
		{
			panel.collapseAutoOpenedCombatSource();
		}
		if (panel != null && event.getSource() == client.getLocalPlayer()
			&& event.getTarget() instanceof net.runelite.api.NPC)
		{
			NPC npc = (NPC) event.getTarget();
			ResourceActionContext resourceContext = resourceContextForNpc(npc);
			if (resourceContext != null)
			{
				showSkillResourceContext(resourceContext);
				return;
			}
			String name = npc.getName();
			if (name != null)
			{
				SkillActionContext actionContext = recentSkillActionContext;
				if (actionContext != null && actionContext.matches(name)
					&& client.getTickCount() - recentSkillActionTick <= 3)
				{
					showSkillActionContext(actionContext);
				}
				else
				{
					panel.setCurrentTarget(name);
				}
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
			recentLootNpc = name;
			recentLootActor = event.getNpc();
			recentLootTick = client.getTickCount();
			if (event.getNpc() == pendingDeathActor || (pendingDeathActor == null && name.equals(pendingDeathNpc)))
			{
				pendingDeathNpc = null;
				pendingDeathActor = null;
			}
			final net.runelite.api.coords.WorldPoint loc = event.getNpc().getWorldLocation();
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
			if ((actor == recentLootActor || name.equals(recentLootNpc))
				&& client.getTickCount() - recentLootTick <= NO_DROP_KILL_GRACE_TICKS)
			{
				return;
			}
			pendingDeathNpc = name;
			pendingDeathActor = actor;
			// NpcLootReceived may arrive a few ticks after ActorDeath. Wait long enough that
			// normal loot wins before falling back to a no-drop kill count. If loot arrived
			// before ActorDeath, the recent-loot guard above prevents a duplicate fallback.
			pendingDeathTicks = NO_DROP_KILL_GRACE_TICKS;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		skillTracker.onGameTick();
		if (panel != null && skillTracker.getActiveSkill() == null)
		{
			panel.collapseAutoOpenedSkillingSource();
		}
		final boolean profitChanged = profitTracker.onGameTick(client.getTickCount());

		final long skillGen = skillTracker.getGeneration();
		if (skillGen != lastSkillGeneration)
		{
			lastSkillGeneration = skillGen;
			if (panel != null)
			{
				panel.refreshSkillingOnly();
				panel.refreshProfitOnly();
			}
			if (client.getCanvas() != null)
			{
				client.getCanvas().repaint();
			}
		}

		// Keep the side-panel skilling rates (XP/hr, time-to-level, resources/hr) live
		// between section rebuilds, without rebuilding the section on a timer.
		if (panel != null)
		{
			panel.updateSkillingLiveStats();
			if (profitChanged)
			{
				panel.refreshProfitOnly();
			}
		}

		if (pendingDeathNpc != null)
		{
			if (--pendingDeathTicks <= 0)
			{
				String name = pendingDeathNpc;
				Actor actor = pendingDeathActor;
				pendingDeathNpc = null;
				pendingDeathActor = null;
				listener.onNoDropKill(name, actor);
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
		final net.runelite.api.TileItem item = event.getItem();
		if (item != null)
		{
			final net.runelite.api.coords.WorldPoint loc =
				event.getTile() == null ? null : event.getTile().getWorldLocation();
			profitTracker.onItemDespawned(item.getId(), item.getQuantity(), loc, client.getTickCount());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final boolean heldContainerChanged = event.getContainerId() == InventoryID.INV
			|| event.getContainerId() == InventoryID.WORN;
		if (heldContainerChanged)
		{
			skillTracker.refreshHeldItems();
		}
		if (event.getContainerId() == InventoryID.INV)
		{
			profitTracker.onInventoryChanged(event.getItemContainer());
			skillTracker.onInventoryChanged(event.getItemContainer());
			if (panel != null)
			{
				panel.refreshProfitOnly();
			}
		}
		if (heldContainerChanged && panel != null)
		{
			panel.refreshSkillingOnly();
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
			case "Destroy":
				return true;
			default:
				return false;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final String option = event.getMenuOption();
		rememberNpcActionSelection(event);
		recordPendingObjectExamine(event);
		SkillActionContext actionContext = recordSkillingSourceAction(event);
		if (actionContext != null)
		{
			recentSkillActionContext = actionContext;
			recentSkillActionTick = client.getTickCount();
			showSkillActionContext(actionContext);
		}
		ResourceActionContext resourceContext = recordSkillingResourceAction(event);
		if (resourceContext != null)
		{
			showSkillResourceContext(resourceContext);
		}
		if (!isItemRemovalAction(option))
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
			if ("Drop".equals(option))
			{
				final Player local = client.getLocalPlayer();
				final net.runelite.api.coords.WorldPoint loc =
					local == null ? null : local.getWorldLocation();
				profitTracker.onItemDropped(item.getId(), item.getQuantity(),
					event.getParam0(), loc, client.getTickCount());
				skillTracker.onItemDropped(item.getId(), item.getQuantity());
			}
			else
			{
				profitTracker.onItemRemovedFinalized(item.getId(), item.getQuantity(), event.getParam0());
				skillTracker.onItemRemovedFinalized(item.getId(), item.getQuantity());
			}
			if (panel != null)
			{
				panel.refreshProfitOnly();
				panel.refreshSkillingOnly();
			}
		}
	}

	private void rememberNpcActionSelection(MenuOptionClicked event)
	{
		if (!config.rememberNpcAction() || event == null || !isNpcAction(event.getMenuAction()))
		{
			return;
		}
		final String option = event.getMenuOption();
		final String target = event.getMenuTarget();
		if (option == null || target == null || ANALYZE.equals(option) || isExamineOption(option))
		{
			return;
		}
		final String source = cleanNpcTarget(target);
		rememberNpcAction(rememberedNpcActions, pickpocketCapableSources, source, option);
	}

	private void recordPendingObjectExamine(MenuOptionClicked event)
	{
		if (!shouldTrackObjectExamineClick(event.getMenuAction(), event.getMenuOption(),
			event.getId(), event.getParam0(), event.getParam1()))
		{
			return;
		}
		final String cleanName = cleanObjectTarget(event.getMenuTarget());
		if (cleanName == null)
		{
			pendingObjectExamine = null;
			return;
		}
		pendingObjectExamine = new PendingObjectExamine(
			event.getId(),
			cleanName,
			clickedTileWorldPoint(event),
			client.getTickCount());
		if (config.debugEquipmentMenuEntries())
		{
			log.debug("Observed object examine pending id={} name='{}' action={} p0={} p1={}",
				event.getId(), cleanName, event.getMenuAction(), event.getParam0(), event.getParam1());
		}
	}

	private void capturePendingObjectExamine(ChatMessage event)
	{
		final PendingObjectExamine pending = pendingObjectExamine;
		if (pending == null)
		{
			return;
		}
		final int age = client.getTickCount() - pending.tick;
		if (age > 20)
		{
			if (config.debugEquipmentMenuEntries())
			{
				log.debug("Observed object examine pending expired id={} name='{}' age={} type={} msg='{}'",
					pending.objectId, pending.name, age, event.getType(), Text.removeTags(event.getMessage()));
			}
			pendingObjectExamine = null;
			return;
		}
		final ChatMessageType type = event.getType();
		if (type != ChatMessageType.OBJECT_EXAMINE && type != ChatMessageType.GAMEMESSAGE
			&& type != ChatMessageType.SPAM && type != ChatMessageType.ENGINE)
		{
			if (config.debugEquipmentMenuEntries())
			{
				log.debug("Observed object examine ignored chat type id={} name='{}' type={} msg='{}'",
					pending.objectId, pending.name, type, Text.removeTags(event.getMessage()));
			}
			return;
		}
		final String examine = ObjectExamineService.cleanExamine(Text.removeTags(event.getMessage()));
		pendingObjectExamine = null;
		if (examine == null)
		{
			if (config.debugEquipmentMenuEntries())
			{
				log.debug("Observed object examine ignored empty/broken text id={} name='{}' type={} msg='{}'",
					pending.objectId, pending.name, type, Text.removeTags(event.getMessage()));
			}
			return;
		}
		final ObjectExamineService.MergeResult result = objectExamineService.observe(
			pending.objectId,
			pending.name,
			examine,
			pending.worldPoint,
			client.getRevision());
		log.debug("Observed object examine capture id={} name='{}' result={}",
			pending.objectId, pending.name, result);
	}

	private WorldPoint clickedTileWorldPoint(MenuOptionClicked event)
	{
		final Scene scene = client.getScene();
		if (scene != null)
		{
			final Tile[][][] tiles = scene.getTiles();
			final int plane = client.getPlane();
			final int x = event.getParam0();
			final int y = event.getParam1();
			if (plane >= 0 && plane < tiles.length
				&& x >= 0 && x < tiles[plane].length
				&& y >= 0 && y < tiles[plane][x].length)
			{
				final Tile tile = tiles[plane][x][y];
				if (tile != null)
				{
					return tile.getWorldLocation();
				}
			}
		}
		final Player local = client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	private SkillActionContext recordSkillingSourceAction(MenuOptionClicked event)
	{
		final String option = event.getMenuOption();
		final String target = event.getMenuTarget();
		if (option == null || target == null)
		{
			return null;
		}
		final Skill skill = skillForSourceAction(option);
		if (skill == null)
		{
			return null;
		}
		final NPC npc = event.getMenuEntry().getNpc();
		if (npc != null && !npcHasAction(npc, option))
		{
			return null;
		}
		final String cleanTarget = cleanNpcTarget(target);
		skillTracker.onSourceAction(skill, cleanTarget, option);

		final java.util.List<ResourceData.ResourceEntry> entries =
			resourceData.forSourceAction(skill, cleanTarget, option);
		return entries.isEmpty() ? null : new SkillActionContext(skill, option, cleanTarget, entries);
	}

	private ResourceActionContext recordSkillingResourceAction(MenuOptionClicked event)
	{
		if (isNpcAction(event.getMenuAction()))
		{
			final NPC npc = event.getMenuEntry().getNpc();
			return resourceContextForNpc(npc);
		}

		if (!isObjectLikeAction(event.getMenuAction()))
		{
			return null;
		}
		final String option = event.getMenuOption();
		java.util.List<ResourceData.ResourceEntry> resources = resourceData.forObjectId(event.getId());
		if (resources.isEmpty())
		{
			resources = resourcesForClickedTile(event);
		}
		if (resources.isEmpty())
		{
			resources = resourceData.forObjectName(event.getMenuTarget());
		}
		if (resources.isEmpty())
		{
			if (isResourceAction(option))
			{
				log.debug("Skilling resource click unresolved option='{}' target='{}' id={} p0={} p1={} action={}",
					option, cleanNpcTarget(event.getMenuTarget()), event.getId(), event.getParam0(),
					event.getParam1(), event.getMenuAction());
			}
			return null;
		}
		final ResourceData.ResourceEntry resource = resources.get(0);
		if (!isResourceAction(option) && !isKnownGatheringResource(resource))
		{
			return null;
		}
		log.debug("Skilling resource click option='{}' target='{}' id={} resolved='{}' entries={}",
			option, cleanNpcTarget(event.getMenuTarget()), event.getId(), resource.getName(), resources.size());
		skillTracker.onResourceAction(resource.getSkill());
		final String cleanTarget = cleanNpcTarget(event.getMenuTarget());
		final java.util.List<ResourceData.ResourceEntry> rewards = resourceContextEntries(resource);
		return new ResourceActionContext(cleanTarget, resource, rewards);
	}

	private ResourceActionContext resourceContextForNpc(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}
		final java.util.List<ResourceData.ResourceEntry> resources = resourceData.forNpcId(npc.getId());
		if (resources.isEmpty())
		{
			return null;
		}
		final ResourceData.ResourceEntry resource = resources.get(0);
		skillTracker.onResourceAction(resource.getSkill());
		final String name = npc.getName() == null ? resource.getSkill().getName() : cleanNpcTarget(npc.getName());
		return new ResourceActionContext(name, resource, resources);
	}

	private java.util.List<ResourceData.ResourceEntry> resourceContextEntries(ResourceData.ResourceEntry resource)
	{
		if (resource == null)
		{
			return java.util.Collections.emptyList();
		}
		final java.util.List<ResourceData.ResourceEntry> entries = new java.util.ArrayList<>();
		entries.add(resource);
		for (ResourceData.ResourceEntry reward : resourceData.statisticalRewardsForResource(resource))
		{
			if (reward != resource)
			{
				entries.add(reward);
			}
		}
		return java.util.Collections.unmodifiableList(entries);
	}

	private java.util.List<ResourceData.ResourceEntry> resourcesForClickedTile(MenuOptionClicked event)
	{
		final Scene scene = client.getScene();
		if (scene == null)
		{
			return java.util.Collections.emptyList();
		}
		final Tile[][][] tiles = scene.getTiles();
		final int plane = client.getPlane();
		final int x = event.getParam0();
		final int y = event.getParam1();
		if (plane < 0 || plane >= tiles.length
			|| x < 0 || x >= tiles[plane].length
			|| y < 0 || y >= tiles[plane][x].length)
		{
			return java.util.Collections.emptyList();
		}
		return resourcesForTile(tiles[plane][x][y]);
	}

	private java.util.List<ResourceData.ResourceEntry> resourcesForTile(Tile tile)
	{
		if (tile == null)
		{
			return java.util.Collections.emptyList();
		}
		java.util.List<ResourceData.ResourceEntry> resources = resourcesForTileObject(tile.getGroundObject());
		if (!resources.isEmpty())
		{
			return resources;
		}
		resources = resourcesForTileObject(tile.getWallObject());
		if (!resources.isEmpty())
		{
			return resources;
		}
		resources = resourcesForTileObject(tile.getDecorativeObject());
		if (!resources.isEmpty())
		{
			return resources;
		}
		final net.runelite.api.GameObject[] objects = tile.getGameObjects();
		if (objects != null)
		{
			for (net.runelite.api.GameObject object : objects)
			{
				resources = resourcesForTileObject(object);
				if (!resources.isEmpty())
				{
					return resources;
				}
			}
		}
		return java.util.Collections.emptyList();
	}

	private java.util.List<ResourceData.ResourceEntry> resourcesForTileObject(TileObject object)
	{
		return object == null ? java.util.Collections.emptyList() : resourceData.forObjectId(object.getId());
	}

	private static boolean isGameObjectAction(MenuAction action)
	{
		return action == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION
			|| action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
	}

	private static boolean isWorldEntityAction(MenuAction action)
	{
		return action == MenuAction.WORLD_ENTITY_FIRST_OPTION
			|| action == MenuAction.WORLD_ENTITY_SECOND_OPTION
			|| action == MenuAction.WORLD_ENTITY_THIRD_OPTION
			|| action == MenuAction.WORLD_ENTITY_FOURTH_OPTION
			|| action == MenuAction.WORLD_ENTITY_FIFTH_OPTION;
	}

	private static boolean isObjectLikeAction(MenuAction action)
	{
		return isGameObjectAction(action) || isWorldEntityAction(action);
	}

	static boolean isObjectExamineAction(MenuAction action)
	{
		return action == MenuAction.EXAMINE_OBJECT || action == MenuAction.EXAMINE_WORLD_ENTITY;
	}

	static boolean shouldTrackObjectExamineClick(MenuAction action, String option, int id, int param0, int param1)
	{
		if (!isExamineOption(option) || id < 0)
		{
			return false;
		}
		if (isObjectExamineAction(action))
		{
			return true;
		}
		return action == MenuAction.RUNELITE && param0 > 0 && param1 > 0;
	}

	static boolean shouldReplaceObjectExamine(boolean replaceExamine, boolean knownObjectExamine)
	{
		return replaceExamine && knownObjectExamine;
	}

	private static boolean isNpcAction(MenuAction action)
	{
		return action == MenuAction.NPC_FIRST_OPTION
			|| action == MenuAction.NPC_SECOND_OPTION
			|| action == MenuAction.NPC_THIRD_OPTION
			|| action == MenuAction.NPC_FOURTH_OPTION
			|| action == MenuAction.NPC_FIFTH_OPTION;
	}

	private static boolean isNpcActionId(int action)
	{
		return action == MenuAction.NPC_FIRST_OPTION.getId()
			|| action == MenuAction.NPC_SECOND_OPTION.getId()
			|| action == MenuAction.NPC_THIRD_OPTION.getId()
			|| action == MenuAction.NPC_FOURTH_OPTION.getId()
			|| action == MenuAction.NPC_FIFTH_OPTION.getId();
	}

	private static boolean isKnownGatheringResource(ResourceData.ResourceEntry resource)
	{
		return resource != null
			&& (resource.getSkill() == Skill.WOODCUTTING || resource.getSkill() == Skill.MINING);
	}

	private static boolean isResourceAction(String option)
	{
		if (option == null)
		{
			return false;
		}
		switch (normalizeAction(option).toLowerCase())
		{
			case "chop":
			case "chop-down":
			case "cut-down":
			case "cut":
			case "mine":
			case "net":
			case "small-net":
			case "bait":
			case "lure":
			case "cage":
			case "harpoon":
			case "big-net":
				return true;
			default:
				return false;
		}
	}

	private Skill skillForSourceAction(String option)
	{
		if (option == null)
		{
			return null;
		}
		switch (normalizeAction(option).toLowerCase())
		{
			case "pickpocket":
			case "steal-from":
				return Skill.THIEVING;
			case "catch":
				return Skill.HUNTER;
			default:
				return null;
		}
	}

	private boolean npcHasAction(NPC npc, String option)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		final String[] actions = composition == null ? null : composition.getActions();
		if (actions == null)
		{
			return true;
		}
		final String normalizedOption = normalizeAction(option);
		for (String action : actions)
		{
			if (normalizedOption.equalsIgnoreCase(normalizeAction(action)))
			{
				return true;
			}
		}
		return false;
	}

	private static String cleanNpcTarget(String target)
	{
		if (target == null)
		{
			return "";
		}
		String clean = Text.removeTags(target).replace('\u00A0', ' ').trim();
		return clean.replaceFirst("\\s*\\((level|combat).*", "").trim();
	}

	private static String normalizeAction(String option)
	{
		return option == null ? "" : option.trim().replace(' ', '-');
	}

	private void showSkillActionContext(SkillActionContext actionContext)
	{
		if (skillingOverlay != null && actionContext != null)
		{
			skillingOverlay.setCurrentSkillSourceTarget(actionContext.skill, actionContext.action,
				actionContext.sourceName);
		}
		if (panel != null && actionContext != null)
		{
			panel.setCurrentSkillSourceTarget(actionContext.skill, actionContext.action,
				actionContext.sourceName, actionContext.entries);
		}
		if (overlay != null && actionContext != null)
		{
			if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.TARGET_STATUS_OVERLAY)
			{
				overlay.setCurrentSkillSourceTarget(actionContext.skill, actionContext.action,
					actionContext.sourceName, actionContext.entries);
			}
			else
			{
				overlay.setCurrentTarget(null, null, 0);
			}
		}
	}

	private void showSkillResourceContext(ResourceActionContext resourceContext)
	{
		if (skillingOverlay != null && resourceContext != null)
		{
			skillingOverlay.setCurrentSkillResourceTarget(resourceContext.resourceName,
				resourceContext.resource);
		}
		if (panel != null && resourceContext != null)
		{
			panel.setCurrentSkillResourceTarget(resourceContext.resourceName,
				resourceContext.resource, resourceContext.rewards);
		}
		if (overlay != null && resourceContext != null)
		{
			if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.TARGET_STATUS_OVERLAY)
			{
				overlay.setCurrentSkillResourceTarget(resourceContext.resourceName,
					resourceContext.resource, resourceContext.rewards);
			}
			else
			{
				overlay.setCurrentTarget(null, null, 0);
			}
		}
	}

	private SkillActionContext skillActionContextForNpc(NPC npc)
	{
		if (npc == null || npc.getName() == null)
		{
			return null;
		}
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		final String[] actions = composition == null ? null : composition.getActions();
		if (actions == null)
		{
			return null;
		}
		final String sourceName = cleanNpcTarget(npc.getName());
		for (String action : actions)
		{
			final Skill skill = skillForSourceAction(action);
			if (skill == null)
			{
				continue;
			}
			final java.util.List<ResourceData.ResourceEntry> entries =
				resourceData.forSourceAction(skill, sourceName, action);
			if (!entries.isEmpty())
			{
				return new SkillActionContext(skill, action, sourceName, entries);
			}
		}
		return null;
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
		logEquipmentMenuAudit(event);
		if (config.rememberNpcAction() && isNpcActionId(event.getType()))
		{
			prioritizeRememberedNpcAction(event.getTarget());
		}

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

			final SkillActionContext sourceContext = skillActionContextForNpc(npc);
			if (sourceContext != null && npc.getCombatLevel() <= 0)
			{
				if (replace)
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e ->
						{
							analyzeOverlay.analyze(npc.getName());
							showSkillActionContext(sourceContext);
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
							analyzeOverlay.analyze(npc.getName());
							showSkillActionContext(sourceContext);
						});
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
			if (!entries.isEmpty())
			{
				final String target = event.getTarget();
				final String cleanName = cleanObjectTarget(target);
				if (cleanName == null)
				{
					return;
				}
				objectWikiExamineService.requestResourceObjectExamine(objectId, cleanName, entries,
					config.enableWikiLookup());
				final boolean knownObjectExamine = objectExamineService.hasExamine(objectId, cleanName);
				if (shouldReplaceObjectExamine(replace, knownObjectExamine))
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeResource(cleanName, entries, objectId));
				}
				else if (!analyzeEntryExists())
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(objectId)
						.onClick(e -> analyzeOverlay.analyzeResource(cleanName, entries, objectId));
				}
			}
			else if (event.getTarget() != null)
			{
				final String target = event.getTarget();
				final String cleanName = cleanObjectTarget(target);
				if (cleanName == null)
				{
					return;
				}
				objectWikiExamineService.requestObjectExamine(objectId, cleanName, config.enableWikiLookup());
				final boolean knownObjectExamine = objectExamineService.hasExamine(objectId, cleanName);
				if (shouldReplaceObjectExamine(replace, knownObjectExamine))
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeObject(cleanName, objectId));
				}
				else if (!analyzeEntryExists())
				{
					// Unknown generic objects keep native Examine. If the player uses it,
					// onChatMessage can learn the resulting text for next time.
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(objectId)
						.onClick(e -> analyzeOverlay.analyzeObject(cleanName, objectId));
				}
			}
			return;
		}

		// World entities can surface as their own Examine action. Unknown entries keep
		// native Examine until observed text is available locally.
		if (event.getType() == MenuAction.EXAMINE_WORLD_ENTITY.getId())
		{
			if (event.getTarget() != null)
			{
				final String target = event.getTarget();
				final int objectId = event.getIdentifier();
				final String cleanName = cleanObjectTarget(target);
				if (cleanName == null)
				{
					return;
				}
				objectWikiExamineService.requestObjectExamine(objectId, cleanName, config.enableWikiLookup());
				final boolean knownObjectExamine = objectExamineService.hasExamine(objectId, cleanName);
				if (shouldReplaceObjectExamine(replace, knownObjectExamine))
				{
					event.getMenuEntry()
						.setOption(ANALYZE)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> analyzeOverlay.analyzeObject(cleanName, objectId));
				}
				else if (!analyzeEntryExists())
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(ANALYZE)
						.setTarget(target)
						.setType(MenuAction.RUNELITE)
						.setIdentifier(objectId)
						.onClick(e -> analyzeOverlay.analyzeObject(cleanName, objectId));
				}
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

		// Inventory / equipment / widget items: hook widget-item Examine paths
		// that expose an item id. Equipment surfaces may put the item id on the
		// widget instead of the MenuEntry, so fall back only for known equipment
		// widget groups and keep Appraise RUNELITE/client-side.
		final MenuEntry menuEntry = event.getMenuEntry();
		final Widget widget = menuEntry.getWidget();
		final int widgetId = widgetId(widget);
		final int widgetItemId = widgetItemId(widget);
		int resolvedItemId = resolveMenuItemId(menuEntry.getItemId(), widgetItemId);
		if (resolvedItemId < 0 && isEquipmentWidgetExamineEntry(event.getType(), event.getOption(), widgetId))
		{
			resolvedItemId = resolveWornEquipmentItemId(widgetId, event.getTarget());
		}
		if (isWidgetItemExamineEntry(
			event.getType(),
			event.getOption(),
			menuEntry.getItemId(),
			widgetItemId,
			widgetId,
			resolvedItemId))
		{
			final int id = resolvedItemId;
			if (!analyzeEntryExists())
			{
				final String target = event.getTarget();
				final String cleanName = cleanItemTarget(target, id);
				if (replace)
				{
					menuEntry
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
						.setItemId(id)
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

	private void prioritizeRememberedNpcAction(String target)
	{
		final String source = cleanNpcTarget(target);
		final String remembered = rememberedNpcActions.get(source);
		if (remembered == null)
		{
			return;
		}
		final MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (prioritizeRememberedNpcAction(entries, source, remembered))
		{
			client.getMenu().setMenuEntries(entries);
		}
	}

	static boolean rememberNpcAction(Map<String, String> remembered, java.util.Set<String> pickpocketSources,
		String source, String action)
	{
		if (remembered == null || pickpocketSources == null || source == null || action == null)
		{
			return false;
		}
		final String cleanSource = source.trim();
		final String cleanAction = action.trim();
		if (cleanSource.isEmpty() || cleanAction.isEmpty())
		{
			return false;
		}
		if ("Pickpocket".equalsIgnoreCase(cleanAction))
		{
			pickpocketSources.add(cleanSource);
		}
		if (!pickpocketSources.contains(cleanSource))
		{
			return false;
		}
		remembered.put(cleanSource, cleanAction);
		return true;
	}

	static boolean prioritizeRememberedNpcAction(MenuEntry[] entries, String source, String rememberedAction)
	{
		if (entries == null || source == null || rememberedAction == null)
		{
			return false;
		}
		int rememberedIndex = -1;
		for (int i = 0; i < entries.length; i++)
		{
			final MenuEntry entry = entries[i];
			if (entry == null || !isNpcAction(entry.getType()))
			{
				continue;
			}
			if (rememberedAction.equalsIgnoreCase(entry.getOption())
				&& source.equals(cleanNpcTarget(entry.getTarget())))
			{
				rememberedIndex = i;
			}
		}
		if (rememberedIndex < 0 || rememberedIndex == entries.length - 1)
		{
			return false;
		}
		final MenuEntry remembered = entries[rememberedIndex];
		System.arraycopy(entries, rememberedIndex + 1, entries, rememberedIndex, entries.length - rememberedIndex - 1);
		entries[entries.length - 1] = remembered;
		return true;
	}

	static java.util.List<String> prioritizedOptionsForTest(java.util.List<String> options, String rememberedAction)
	{
		if (options == null || rememberedAction == null || !options.contains(rememberedAction))
		{
			return options;
		}
		final java.util.List<String> result = new java.util.ArrayList<>(options);
		result.remove(rememberedAction);
		result.add(rememberedAction);
		return java.util.Collections.unmodifiableList(result);
	}

	static String systemPopupTextForMessage(String message)
	{
		if (message == null)
		{
			return null;
		}
		final String clean = Text.removeTags(message).trim();
		if (clean.matches("(?i)^Your dodgy necklace protects you\\..*It then crumbles to dust\\.$"))
		{
			return "System: Dodgy necklace crumbled to dust.";
		}
		if (clean.equalsIgnoreCase("Your Shadow Veil has faded away."))
		{
			return "System: Shadow Veil faded.";
		}
		if (clean.equalsIgnoreCase("Your gloves of silence are going to fall apart!"))
		{
			return "System: Gloves of silence degraded.";
		}
		if (clean.equalsIgnoreCase("Your gloves of silence have fallen apart."))
		{
			return "System: Gloves of silence broke.";
		}
		if (clean.equalsIgnoreCase("You need to empty your coin pouches before you can continue pickpocketing."))
		{
			return "System: Coin pouch limit reached.";
		}
		return null;
	}

	private void logEquipmentMenuAudit(MenuEntryAdded event)
	{
		if (!config.debugEquipmentMenuEntries())
		{
			return;
		}

		final MenuEntry entry = event.getMenuEntry();
		final Widget widget = entry.getWidget();
		final int widgetId = widgetId(widget);
		final int widgetItemId = widgetItemId(widget);
		final String option = event.getOption();
		final String target = event.getTarget();
		if (!shouldLogEquipmentMenuEntry(option, target, entry.getItemId(), widgetItemId, widgetId))
		{
			return;
		}

		log.debug(
			"Temporary equipment Appraise menu audit: option={} target={} type={} action={} identifier={} itemId={} "
				+ "param0={} param1={} widgetId={} widgetGroup={} widgetChild={} widgetParent={} widgetType={} "
				+ "widgetItemId={} widgetQty={} widgetIndex={} widgetName={} widgetText={} surface={}",
			option,
			target,
			event.getType(),
			MenuAction.of(event.getType()),
			event.getIdentifier(),
			entry.getItemId(),
			event.getActionParam0(),
			event.getActionParam1(),
			widgetId,
			widgetGroupId(widgetId),
			widgetChildId(widgetId),
			widget == null ? -1 : widget.getParentId(),
			widget == null ? -1 : widget.getType(),
			widgetItemId,
			widget == null ? -1 : widget.getItemQuantity(),
			widget == null ? -1 : widget.getIndex(),
			widget == null ? "" : widget.getName(),
			widget == null ? "" : widget.getText(),
			equipmentSurface(widgetId)
		);
	}

	static boolean shouldLogEquipmentMenuEntry(String option, String target, int itemId, int widgetItemId, int widgetId)
	{
		return isExamineOption(option)
			|| itemId > 0
			|| widgetItemId > 0
			|| isEquipmentWidget(widgetId)
			|| containsEquipmentWord(target);
	}

	static boolean isWidgetItemExamineEntry(int type, String option, int itemId, int widgetItemId, int widgetId)
	{
		return isWidgetItemExamineEntry(type, option, itemId, widgetItemId, widgetId, resolveMenuItemId(itemId, widgetItemId));
	}

	static boolean isWidgetItemExamineEntry(int type, String option, int itemId, int widgetItemId, int widgetId, int resolvedItemId)
	{
		if (!isExamineOption(option) || resolvedItemId < 0)
		{
			return false;
		}
		if (isEquipmentWidget(widgetId))
		{
			return true;
		}
		return itemId >= 0 && isKnownWidgetItemExamineAction(type);
	}

	static boolean isWidgetItemExamineEntry(int type, String option, int itemId)
	{
		return isWidgetItemExamineEntry(type, option, itemId, -1, -1);
	}

	static int resolveMenuItemId(int itemId, int widgetItemId)
	{
		return itemId > 0 ? itemId : (widgetItemId > 0 ? widgetItemId : -1);
	}

	static boolean isEquipmentWidgetExamineEntry(int type, String option, int widgetId)
	{
		return isExamineOption(option)
			&& isEquipmentWidget(widgetId)
			&& MenuAction.of(type) == MenuAction.CC_OP_LOW_PRIORITY;
	}

	private int resolveWornEquipmentItemId(int widgetId, String target)
	{
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return -1;
		}

		final int slot = wornSlotForWidgetId(widgetId);
		final Item[] items = worn.getItems();
		if (slot >= 0 && slot < items.length)
		{
			final Item item = items[slot];
			if (item != null && item.getId() > 0)
			{
				return item.getId();
			}
		}

		final String cleanTarget = target == null ? "" : Text.removeTags(target).trim();
		if (cleanTarget.isEmpty())
		{
			return -1;
		}
		for (Item item : items)
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			final String itemName = itemManager.getItemComposition(item.getId()).getName();
			if (cleanTarget.equalsIgnoreCase(itemName))
			{
				return item.getId();
			}
		}
		return -1;
	}

	static int wornSlotForWidgetId(int widgetId)
	{
		final int group = widgetGroupId(widgetId);
		final int child = widgetChildId(widgetId);
		if (group == InterfaceID.EQUIPMENT)
		{
			return equipmentSlotForChild(child, 10);
		}
		if (group == InterfaceID.WORNITEMS)
		{
			return equipmentSlotForChild(child, 15);
		}
		return -1;
	}

	private static int equipmentSlotForChild(int child, int firstChild)
	{
		switch (child - firstChild)
		{
			case 0:
				return 0;
			case 1:
				return 1;
			case 2:
				return 2;
			case 3:
				return 3;
			case 4:
				return 4;
			case 5:
				return 5;
			case 6:
				return 7;
			case 7:
				return 9;
			case 8:
				return 10;
			case 9:
				return 12;
			case 10:
				return 13;
			default:
				return -1;
		}
	}

	static boolean isEquipmentWidget(int widgetId)
	{
		final int group = widgetGroupId(widgetId);
		return group == InterfaceID.EQUIPMENT
			|| group == InterfaceID.EQUIPMENT_SIDE
			|| group == InterfaceID.WORNITEMS;
	}

	static String equipmentSurface(int widgetId)
	{
		final int group = widgetGroupId(widgetId);
		if (group == InterfaceID.EQUIPMENT)
		{
			return "equipment-tab";
		}
		if (group == InterfaceID.EQUIPMENT_SIDE)
		{
			return "equipment-side-tab";
		}
		if (group == InterfaceID.WORNITEMS)
		{
			return "view-equipment-stats";
		}
		return "unknown";
	}

	static int widgetGroupId(int widgetId)
	{
		return widgetId < 0 ? -1 : widgetId >>> 16;
	}

	static int widgetChildId(int widgetId)
	{
		return widgetId < 0 ? -1 : widgetId & 0xFFFF;
	}

	private static boolean isExamineOption(String option)
	{
		return "Examine".equals(option);
	}

	private static boolean isKnownWidgetItemExamineAction(int type)
	{
		switch (MenuAction.of(type))
		{
			case CC_OP:
			case CC_OP_LOW_PRIORITY:
			case EXAMINE_ITEM:
			case WIDGET_FIRST_OPTION:
			case WIDGET_SECOND_OPTION:
			case WIDGET_THIRD_OPTION:
			case WIDGET_FOURTH_OPTION:
			case WIDGET_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	private static int widgetId(Widget widget)
	{
		return widget == null ? -1 : widget.getId();
	}

	private static int widgetItemId(Widget widget)
	{
		return widget == null ? -1 : widget.getItemId();
	}

	private static boolean containsEquipmentWord(String target)
	{
		if (target == null)
		{
			return false;
		}
		final String lower = target.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("equipment") || lower.contains("worn");
	}

	private String cleanItemTarget(String target, int itemId)
	{
		final String clean = target == null ? "" : Text.removeTags(target).trim();
		if (!clean.isEmpty())
		{
			return clean;
		}
		return itemManager.getItemComposition(itemId).getName();
	}

	private static String cleanObjectTarget(String target)
	{
		final String clean = target == null ? "" : Text.removeTags(target).trim();
		return clean.isEmpty() ? null : clean;
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

	private static final class SkillActionContext
	{
		private final Skill skill;
		private final String action;
		private final String sourceName;
		private final java.util.List<ResourceData.ResourceEntry> entries;

		private SkillActionContext(Skill skill, String action, String sourceName,
			java.util.List<ResourceData.ResourceEntry> entries)
		{
			this.skill = skill;
			this.action = action;
			this.sourceName = sourceName;
			this.entries = entries;
		}

		private boolean matches(String npcName)
		{
			return sourceName.equals(npcName);
		}
	}

	private static final class ResourceActionContext
	{
		private final String resourceName;
		private final ResourceData.ResourceEntry resource;
		private final java.util.List<ResourceData.ResourceEntry> rewards;

		private ResourceActionContext(String resourceName, ResourceData.ResourceEntry resource,
			java.util.List<ResourceData.ResourceEntry> rewards)
		{
			this.resourceName = resourceName;
			this.resource = resource;
			this.rewards = rewards;
		}
	}

	private static final class PendingObjectExamine
	{
		private final int objectId;
		private final String name;
		private final WorldPoint worldPoint;
		private final int tick;

		private PendingObjectExamine(int objectId, String name, WorldPoint worldPoint, int tick)
		{
			this.objectId = objectId;
			this.name = name;
			this.worldPoint = worldPoint;
			this.tick = tick;
		}
	}
}



