package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.services.drops.DropTable;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.services.lookup.BossAliases;
import com.systeminterface.services.lookup.ItemExamineService;
import com.systeminterface.services.lookup.ItemMembership;
import com.systeminterface.services.lookup.ObjectExamineService;
import com.systeminterface.services.portrait.PortraitService;
import com.systeminterface.common.model.BestiaryRank;
import com.systeminterface.common.probability.LuckStatus;
import com.systeminterface.services.state.DropOccurrence;
import com.systeminterface.modules.skills.PetDisplay;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.common.model.ItemAppraisal;
import com.systeminterface.common.model.ItemRank;
import com.systeminterface.common.model.PlayerRank;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game "Information" overlay shown when right-clicking Analyze on an NPC or
 * player. Styled to evoke the classic OSRS stone-panel interface.
 */
@Singleton
public class AnalyzeOverlay extends OverlayPanel implements MouseListener
{
	// OSRS-themed palette
	private static final Color OSRS_BG = new Color(45, 40, 31, 235);
	private static final Color OSRS_GOLD = new Color(255, 200, 50);
	private static final Color OSRS_ORANGE = new Color(255, 152, 31);
	private static final Color OSRS_PARCHMENT = new Color(235, 220, 185);
	private static final Color OSRS_LABEL = new Color(200, 185, 150);
	private static final Color DIM = new Color(140, 130, 110);
	private static final Color WARN = new Color(230, 80, 60);
	private static final Color UNIQUE = new Color(190, 110, 240);

	private static final int PANEL_WIDTH = 240;
	/**
	 * Width available to a child component inside the panel: the panel width less
	 * its left/right border (RuneLite's {@code ComponentConstants.STANDARD_BORDER}
	 * is 4 each side). Used to horizontally centre the portrait, since an
	 * {@link ImageComponent} otherwise renders flush against the left border.
	 */
	private static final int CONTENT_WIDTH = PANEL_WIDTH - 8;

	// KC thresholds for progressive stat reveal (NPCs only)
	private static final int REVEAL_BASIC = 1;
	private static final int REVEAL_COMBAT = 3;
	private static final int REVEAL_WEAKNESS = 10;

	/** How many valuable drops to surface. */
	private static final int MAX_VALUABLE_DROPS = 3;
	/** An untradeable drop at least this rare counts as a desirable "unique" chase (pets, jars, etc.). */
	private static final int RARE_UNIQUE_DENOM = 500;

	/**
	 * GE value a tradeable drop must clear to be "worth chasing", scaled to the
	 * player's combat level. A low-level account values cheap useful drops
	 * (hides, low-tier gear) that a high-level account ignores; a main only
	 * cares about expensive uniques. Unknown level (0) is treated as low.
	 */
	private static int chaseThreshold(int combatLevel)
	{
		if (combatLevel <= 30) return 100;
		if (combatLevel <= 60) return 2_000;
		if (combatLevel <= 90) return 20_000;
		if (combatLevel <= 120) return 75_000;
		return 150_000;
	}

	private final Client client;
	private final StateTracker stateTracker;
	private final LootTables lootTables;
	private final ItemManager itemManager;
	private final PortraitService portraitService;
	private final ItemMembership itemMembership;
	private final ItemExamineService itemExamineService;
	private final ObjectExamineService objectExamineService;
	private final SkillTracker skillTracker;
	private final SystemInterfaceConfig config;

	private enum Mode { NPC, PLAYER, ITEM, SELF, RESOURCE, OBJECT }

	private volatile String targetName;
	private volatile Mode mode = Mode.NPC;
	private volatile int playerCombatLevel;
	private volatile int itemId = -1;
	private volatile int objectId = -1;
	private volatile int resourceObjectId = -1;

	// Valuable-loot cache — recomputed only when the target or the player's
	// combat level changes, so the per-frame render path stays cheap (item
	// search/pricing is not free). Combat level is part of the key because the
	// value threshold scales with it.
	private String cachedLootTarget;
	private int cachedLootCombat = -1;
	private boolean cachedLootHideMembers;
	private List<ValuableDrop> cachedValuableLoot;

	private volatile List<ResourceData.ResourceEntry> resourceEntries = java.util.Collections.emptyList();

	@Inject
	public AnalyzeOverlay(Client client, StateTracker stateTracker, LootTables lootTables,
		ItemManager itemManager, PortraitService portraitService, ItemMembership itemMembership,
		ItemExamineService itemExamineService, ObjectExamineService objectExamineService,
		SkillTracker skillTracker, SystemInterfaceConfig config)
	{
		this.client = client;
		this.stateTracker = stateTracker;
		this.lootTables = lootTables;
		this.itemManager = itemManager;
		this.portraitService = portraitService;
		this.itemMembership = itemMembership;
		this.itemExamineService = itemExamineService;
		this.objectExamineService = objectExamineService;
		this.skillTracker = skillTracker;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.HIGH);
	}

	public MouseListener getMouseListener()
	{
		return this;
	}

	public void analyze(String name)
	{
		this.targetName = name;
		this.mode = Mode.NPC;
	}

	public void analyzePlayer(String name, int combatLevel)
	{
		this.targetName = name;
		this.mode = Mode.PLAYER;
		this.playerCombatLevel = combatLevel;
	}

	public void analyzeItem(String name, int itemId)
	{
		this.targetName = name;
		this.mode = Mode.ITEM;
		this.itemId = itemId;
	}

	public void analyzeStatus(String rsn, int combatLevel)
	{
		this.targetName = rsn;
		this.mode = Mode.SELF;
		this.playerCombatLevel = combatLevel;
	}

	public void analyzeResource(String objectName, List<ResourceData.ResourceEntry> entries)
	{
		analyzeResource(objectName, entries, -1);
	}

	public void analyzeResource(String objectName, List<ResourceData.ResourceEntry> entries, int objectId)
	{
		this.targetName = objectName;
		this.mode = Mode.RESOURCE;
		this.resourceEntries = entries != null ? entries : java.util.Collections.emptyList();
		this.resourceObjectId = objectId;
	}

	public void analyzeObject(String objectName)
	{
		analyzeObject(objectName, -1);
	}

	public void analyzeObject(String objectName, int objectId)
	{
		this.targetName = objectName;
		this.mode = Mode.OBJECT;
		this.objectId = objectId;
		this.resourceEntries = java.util.Collections.emptyList();
	}

	public void dismiss()
	{
		this.targetName = null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final String name = targetName;
		if (name == null)
		{
			return null;
		}

		panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
		panelComponent.setBackgroundColor(OSRS_BG);

		switch (mode)
		{
			case PLAYER:
				renderPlayer(name);
				break;
			case ITEM:
				renderItem(name);
				break;
			case SELF:
				renderSelf(name);
				break;
			case RESOURCE:
				renderResource(name);
				break;
			case OBJECT:
				renderObject(name);
				break;
			default:
				renderNpc(name);
				break;
		}

		// Close hint
		panelComponent.getChildren().add(LineComponent.builder()
			.left("")
			.right("right-click to close")
			.rightColor(DIM)
			.build());

		return super.render(graphics);
	}

	// ---------------------------------------------------------------------
	// Player analysis
	// ---------------------------------------------------------------------

	private void renderPlayer(String name)
	{
		final int combat = playerCombatLevel;
		final PlayerRank rank = PlayerRank.fromCombatLevel(combat);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Appraise Window ~")
			.color(OSRS_GOLD)
			.build());

		spacer();

		addRow("Name", name, OSRS_ORANGE);
		addRow("Rank", rank.getLabel(), rank.getColor());
		addRow("Combat", combat > 0 ? String.valueOf(combat) : "??",
			combat > 0 ? OSRS_PARCHMENT : DIM);

		spacer();

		panelComponent.getChildren().add(LineComponent.builder()
			.left(rank.getFlavor())
			.leftColor(DIM)
			.build());

		spacer();
	}

	// ---------------------------------------------------------------------
	// Self / Status analysis
	// ---------------------------------------------------------------------

	private void renderSelf(String rsn)
	{
		final int combat = playerCombatLevel;
		final PlayerRank rank = PlayerRank.fromCombatLevel(combat);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Status ~")
			.color(OSRS_GOLD)
			.build());

		spacer();

		addRow("RSN", rsn, OSRS_ORANGE);
		addRow("Combat", combat > 0 ? String.valueOf(combat) : "??",
			combat > 0 ? OSRS_PARCHMENT : DIM);
		addRow("Rank", rank.getLabel(), rank.getColor());

		final Player local = client.getLocalPlayer();
		if (local != null)
		{
			final int hp = client.getRealSkillLevel(Skill.HITPOINTS);
			addRow("Hitpoints", String.valueOf(hp), OSRS_PARCHMENT);
		}

		// Account Luck
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Account Luck -")
			.color(OSRS_GOLD)
			.build());

		final double accountZ = stateTracker.computeAccountLuck(lootTables);
		if (Double.isNaN(accountZ))
		{
			addRow("Luck", "No data", DIM);
		}
		else
		{
			final LuckStatus luck = LuckStatus.fromZScore(accountZ);
			addRow("Luck", luck.getLabel(), luck.getColor());
			addRow("Z-Score", String.format("%+.2f", accountZ), OSRS_PARCHMENT);
		}

		// Bestiary Progress — total kills per BestiaryRank tier
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Bestiary Progress -")
			.color(OSRS_GOLD)
			.build());

		final java.util.Map<BestiaryRank, Integer> bestiaryCount = new java.util.EnumMap<>(BestiaryRank.class);
		for (BestiaryRank r : BestiaryRank.values())
		{
			bestiaryCount.put(r, 0);
		}
		int totalKills = 0;
		int totalUniqueDrops = 0;
		final java.util.Set<String> seenDropNames = new java.util.HashSet<>();

		for (TargetState t : stateTracker.getTrackedTargets())
		{
			BestiaryRank r = BestiaryRank.fromCombatLevel(t.getCombatLevel());
			bestiaryCount.merge(r, t.getCurrentKc(), Integer::sum);
			totalKills += t.getCurrentKc();
			for (DropOccurrence d : t.getDrops())
			{
				seenDropNames.add(d.getDropName().toLowerCase());
			}
		}
		totalUniqueDrops = seenDropNames.size();

		final StringBuilder bestiaryLine = new StringBuilder();
		for (BestiaryRank r : BestiaryRank.values())
		{
			int count = bestiaryCount.get(r);
			if (count > 0)
			{
				if (bestiaryLine.length() > 0)
				{
					bestiaryLine.append(" | ");
				}
				bestiaryLine.append(r.getLabel()).append(": ").append(count);
			}
		}
		if (bestiaryLine.length() == 0)
		{
			bestiaryLine.append("None");
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(bestiaryLine.toString())
			.leftColor(OSRS_PARCHMENT)
			.build());

		// Combat Summary
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Combat Summary -")
			.color(OSRS_GOLD)
			.build());

		addRow("Lifetime Kills", String.format("%,d", totalKills), OSRS_PARCHMENT);
		addRow("Unique Drops", String.format("%,d", totalUniqueDrops), OSRS_PARCHMENT);

		// Title placeholder for Phase 4
		spacer();
		addRow("Title", "(none)", DIM);

		spacer();
	}

	// ---------------------------------------------------------------------
	// Item analysis
	// ---------------------------------------------------------------------

	private void renderItem(String name)
	{
		final int id = this.itemId;
		// Check noted on the original item BEFORE canonicalizing (which strips noted status).
		final ItemComposition origComp = id >= 0 ? client.getItemDefinition(id) : null;
		final boolean noted = origComp != null && origComp.getNote() != -1;
		final int canonical = id >= 0 ? itemManager.canonicalize(id) : -1;
		final ItemComposition comp = canonical >= 0 ? client.getItemDefinition(canonical) : null;
		final int value = canonical >= 0 ? itemManager.getItemPrice(canonical) : 0;
		final ItemStats stats = canonical >= 0 ? itemManager.getItemStats(canonical) : null;
		final ItemEquipmentStats eq = stats != null ? stats.getEquipment() : null;
		final boolean equipable = stats != null && stats.isEquipable();
		final int slot = eq != null ? eq.getSlot() : -1;
		final String displayName = explicitNotedName(name, noted);

		final ItemAppraisal appraisal = ItemAppraisal.appraise(displayName, value, equipable, slot, noted);
		final ItemAppraisal.ItemClass cls = appraisal.getItemClass();
		final ItemRank rank = appraisal.getRank();

		// --- Header ---
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Appraise Window ~")
			.color(OSRS_GOLD)
			.build());

		spacer();

		addRow("Name", displayName, OSRS_ORANGE);
		addRow("Class", cls.getLabel(), OSRS_PARCHMENT);
		// Dual ranking: a wieldable skilling tool (pickaxe/axe) shows both its skill
		// grade and its weaker combat grade. Single-context items show a plain "Rank".
		if (appraisal.getSecondaryRank() != null)
		{
			addRow("Rank (" + appraisal.getPrimaryContext() + ")", rank.getLabel(), rank.getColor());
			final ItemRank secondary = appraisal.getSecondaryRank();
			addRow("Rank (" + appraisal.getSecondaryContext() + ")", secondary.getLabel(), secondary.getColor());
		}
		else
		{
			addRow("Rank", rank.getLabel(), rank.getColor());
		}
		if (appraisal.getSkillUse() != null)
		{
			addRow("Use", appraisal.getSkillUse(), OSRS_ORANGE);
		}

		// --- Requirements ---
		if (comp != null && (comp.isMembers() || equipable))
		{
			spacer();
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("- Requirements -")
				.color(OSRS_GOLD)
				.build());

			addRow("Members", comp.isMembers() ? "Yes" : "No",
				comp.isMembers() ? OSRS_ORANGE : OSRS_PARCHMENT);
			if (equipable)
			{
				addRow("Equippable", "Yes", OSRS_PARCHMENT);
				addRow("Slot", slotName(slot), OSRS_PARCHMENT);
			}
		}

		// --- Performance (equipment stats for combat gear; speed for weapons) ---
		if (config.showCombatStats() && eq != null && (cls == ItemAppraisal.ItemClass.WEAPON
			|| cls == ItemAppraisal.ItemClass.ARMOUR
			|| cls == ItemAppraisal.ItemClass.AMMUNITION))
		{
			spacer();
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("- Performance -")
				.color(OSRS_GOLD)
				.build());

			if (cls == ItemAppraisal.ItemClass.WEAPON)
			{
				addStatBlock("Accuracy", formatAttack(eq));
				if (eq.getStr() != 0) addRow("Str", plus(eq.getStr()), OSRS_PARCHMENT);
				if (eq.getRstr() != 0) addRow("Rng Str", plus(eq.getRstr()), OSRS_PARCHMENT);
				if (eq.getMdmg() != 0) addRow("Mag Dmg", plusPct(eq.getMdmg()), OSRS_PARCHMENT);
				addRow("Speed", String.valueOf(eq.getAspeed()), OSRS_PARCHMENT);
			}
			else
			{
				addStatBlock("Defence", formatDefence(eq));
				if (eq.getStr() != 0) addRow("Str", plus(eq.getStr()), OSRS_PARCHMENT);
				if (eq.getPrayer() != 0) addRow("Prayer", plus(eq.getPrayer()), OSRS_PARCHMENT);
			}
		}

		// --- Properties ---
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Properties -")
			.color(OSRS_GOLD)
			.build());

		addRow("Value", value > 0
			? QuantityFormatter.quantityToStackSize(value) + " gp"
			: "Untradeable", value > 0 ? OSRS_GOLD : DIM);

		if (comp != null)
		{
			if (value > 0)
			{
				addRow("High Alch", QuantityFormatter.quantityToStackSize(comp.getHaPrice()) + " gp",
					OSRS_PARCHMENT);
			}
			addRow("Tradeable", comp.isTradeable() ? "Yes" : "No", OSRS_PARCHMENT);
			if (noted)
			{
				addRow("Noted", "Yes", OSRS_ORANGE);
			}
			else
			{
				addRow("Stackable", comp.isStackable() ? "Yes" : "No", OSRS_PARCHMENT);
			}
		}

		// --- Refinement (processing paths for resources, from curated skill data) ---
		renderRefinement(canonical);

		// --- Description (wiki examine text + classification summary) ---
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Description -")
			.color(OSRS_GOLD)
			.build());

		final String examineText = canonical >= 0
			? itemExamineService.getExamine(canonical, config.enableWikiLookup()) : null;
		if (examineText != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(examineText)
				.leftColor(OSRS_PARCHMENT)
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(appraisal.getDescription())
			.leftColor(DIM)
			.build());

		spacer();
	}

	private static String slotName(int slot)
	{
		switch (slot)
		{
			case 0: return "Head";
			case 1: return "Cape";
			case 2: return "Neck";
			case 3: return "Weapon";
			case 4: return "Body";
			case 5: return "Shield";
			case 7: return "Legs";
			case 9: return "Gloves";
			case 10: return "Boots";
			case 12: return "Ring";
			case 13: return "Ammo";
			default: return "Slot " + slot;
		}
	}

	private static String formatAttack(ItemEquipmentStats eq)
	{
		return plus(eq.getAstab()) + "/" + plus(eq.getAslash()) + "/"
			+ plus(eq.getAcrush()) + "/" + plus(eq.getAmagic()) + "/"
			+ plus(eq.getArange());
	}

	private static String formatDefence(ItemEquipmentStats eq)
	{
		return plus(eq.getDstab()) + "/" + plus(eq.getDslash()) + "/"
			+ plus(eq.getDcrush()) + "/" + plus(eq.getDmagic()) + "/"
			+ plus(eq.getDrange());
	}

	private static String plus(int v)
	{
		return v >= 0 ? "+" + v : Integer.toString(v);
	}

	private static String plusPct(double v)
	{
		final String s = v == Math.floor(v) ? Long.toString((long) v) : String.format("%.1f", v);
		return (v >= 0 ? "+" + s : s) + "%";
	}

	/** Green when the player already meets a skill requirement, orange when they don't. */
	private static final Color MET = new Color(110, 200, 110);

	/**
	 * Renders the "Refinement" section for an item that maps to curated skill data —
	 * the skills that consume it and the level each requires (e.g. Oak logs →
	 * "Fletching Lvl 20", "Firemaking Lvl 15"). Pulled from the same {@code uses} data
	 * in ResourceData.json. No-op for items with no curated processing path.
	 */
	private void renderRefinement(int canonicalId)
	{
		if (canonicalId < 0)
		{
			return;
		}
		final ResourceData resourceData = skillTracker.getResourceData();
		final ResourceData.ResourceEntry entry = resourceData != null
			? resourceData.forItemId(canonicalId) : null;
		if (entry == null || entry.getUses().isEmpty())
		{
			return;
		}
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Refinement -")
			.color(OSRS_GOLD)
			.build());
		for (String use : entry.getUses())
		{
			addUseRow(use);
		}
	}

	/**
	 * Renders a single {@code "Skill:level"} use as a row. When the player is logged in,
	 * the requirement is coloured green if they meet it and orange if they don't — tying
	 * the curated data to live skill levels.
	 */
	private void addUseRow(String use)
	{
		final String[] parts = use.split(":");
		if (parts.length != 2)
		{
			addRow(use, "", OSRS_PARCHMENT);
			return;
		}
		final String skillName = parts[0].trim();
		final String levelStr = parts[1].trim();
		Color color = OSRS_PARCHMENT;
		try
		{
			final int required = Integer.parseInt(levelStr);
			final Skill skill = Skill.valueOf(skillName.toUpperCase());
			final int current = client.getRealSkillLevel(skill);
			if (current > 0)
			{
				color = current >= required ? MET : OSRS_ORANGE;
			}
		}
		catch (IllegalArgumentException ignored)
		{
			// Unknown skill name or non-numeric level — fall back to a neutral colour.
		}
		addRow(skillName, "Lvl " + levelStr, color);
	}

	// ---------------------------------------------------------------------
	// NPC analysis
	// ---------------------------------------------------------------------

	private void renderNpc(String name)
	{
		final TargetState state = stateTracker.get(name);
		final DropTable table = lootTables.forTarget(name);
		final int combatLvl = state != null ? state.getCombatLevel() : 0;
		final int tableCombat = table != null ? table.getCombatLevel() : 0;
		final boolean attackable = combatLvl > 0 || tableCombat > 0;

		if (attackable)
		{
			renderAttackableNpc(name, state, table, Math.max(combatLvl, tableCombat));
		}
		else
		{
			renderInformationalNpc(name, table);
		}
	}

	/** Full combat layout for attackable NPCs: KC, HP, stats, drops. */
	private void renderAttackableNpc(String name, TargetState state, DropTable table, int combatLvl)
	{
		final int kc = state != null ? state.getCurrentKc() : 0;

		int playerCombat = 0;
		final Player local = client.getLocalPlayer();
		if (local != null)
		{
			playerCombat = local.getCombatLevel();
		}
		final int levelGap = (combatLvl > 0 && playerCombat > 0) ? playerCombat - combatLvl : 0;
		final int mult;
		if (levelGap >= 20) { mult = 0; }
		else if (levelGap <= -40) { mult = 3; }
		else if (levelGap <= -20) { mult = 2; }
		else { mult = 1; }

		final int reqBasic = REVEAL_BASIC * Math.max(1, mult);
		final int reqCombat = REVEAL_COMBAT * Math.max(1, mult);
		final int reqWeakness = REVEAL_WEAKNESS * Math.max(1, mult);
		final int effectiveKc = mult == 0 ? Math.max(kc, reqWeakness) : kc;

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Appraise Window ~")
			.color(OSRS_GOLD)
			.build());

		final boolean knownBoss = BossAliases.canonicalize(name) != null;
		if ((knownBoss || effectiveKc >= reqBasic) && table != null)
		{
			final BufferedImage portrait = portraitService.get(name, table.getImageFile());
			if (portrait != null)
			{
				spacer();
				panelComponent.getChildren().add(new ImageComponent(centerInPanel(portrait)));
			}
		}

		spacer();

		addRow("Name", name, OSRS_ORANGE);
		BestiaryRank rank = BestiaryRank.fromCombatLevel(combatLvl);
		addRow("Rank", rank.getLabel(), rank.getColor());

		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Stats -")
			.color(OSRS_GOLD)
			.build());

		if (effectiveKc < reqBasic)
		{
			addRow("Level", "??", DIM);
			addRow("HP", "??", DIM);
			addRow("Stats", "??", DIM);
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Engage to learn more...")
				.leftColor(DIM)
				.build());
		}
		else
		{
			addRow("Level", String.valueOf(combatLvl), OSRS_PARCHMENT);

			int hp = table != null && table.getMaxHp() > 0 ? table.getMaxHp() : 0;
			addRow("HP", hp > 0 ? String.valueOf(hp) : "??", hp > 0 ? OSRS_PARCHMENT : DIM);

			if (table != null)
			{
				if (effectiveKc >= reqCombat)
				{
					addStatBlock("Max Hit", table.getMaxHit());
					addStatBlock("Style", table.getAttackStyle());
					addRow("Behavior", table.isAggressive() ? "Aggressive" : "Passive/neutral",
						table.isAggressive() ? WARN : OSRS_PARCHMENT);
				}
				else
				{
					addLocked("Max Hit", reqCombat);
					addLocked("Style", reqCombat);
				}

				if (effectiveKc >= reqWeakness)
				{
					if (table.getWeakness() != null)
					{
						addRow("Weakness", table.getWeakness(), OSRS_ORANGE);
					}
					if (table.getSlayerLevel() > 0)
					{
						addRow("Slayer Req", String.valueOf(table.getSlayerLevel()), OSRS_PARCHMENT);
					}
				}
				else
				{
					addLocked("Weakness", reqWeakness);
				}
			}
		}

		// --- Description ---
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Description -")
			.color(OSRS_GOLD)
			.build());

		if (effectiveKc >= reqBasic && table != null && table.getExamine() != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(table.getExamine())
				.leftColor(OSRS_PARCHMENT)
				.build());
		}
		else
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(rank.getFlavor())
				.leftColor(DIM)
				.build());
		}

		spacer();

		if (config.showValuableLoot() && effectiveKc >= reqWeakness && table != null)
		{
			renderValuableLoot(name, table, playerCombat);
		}

		addRow("Slain", kc > 0 ? String.format("%,d", kc) : "Never",
			kc > 0 ? OSRS_ORANGE : DIM);
	}

	/** Simplified layout for non-attackable NPCs: name, examine text, wiki info. */
	private void renderInformationalNpc(String name, DropTable table)
	{
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Appraise Window ~")
			.color(OSRS_GOLD)
			.build());

		if (table != null)
		{
			final BufferedImage portrait = portraitService.get(name, table.getImageFile());
			if (portrait != null)
			{
				spacer();
				panelComponent.getChildren().add(new ImageComponent(centerInPanel(portrait)));
			}
		}

		spacer();

		addRow("Name", name, OSRS_ORANGE);
		addRow("Role", inferNpcRole(name, table != null ? table.getExamine() : null), OSRS_PARCHMENT);
		final String service = inferNpcService(name, table != null ? table.getExamine() : null);
		if (service != null)
		{
			addRow("Service", service, OSRS_ORANGE);
		}

		// --- Description ---
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Description -")
			.color(OSRS_GOLD)
			.build());

		if (table != null && table.getExamine() != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(table.getExamine())
				.leftColor(OSRS_PARCHMENT)
				.build());
		}
		else
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("No additional Appraise data available yet.")
				.leftColor(DIM)
				.build());
		}

		if (table != null && table.isMembers())
		{
			spacer();
			addRow("Members", "Yes", OSRS_ORANGE);
		}

		spacer();
	}

	/**
	 * Renders the "Valuable Loot" section, using the per-target/per-combat cache.
	 * Always shows the section once the target is fully revealed — "None" when
	 * nothing clears the bar for this account level.
	 */
	private void renderValuableLoot(String name, DropTable table, int playerCombat)
	{
		// Bosses and high-tier NPCs always show their full loot analysis even on
		// F2P worlds — these monsters can't be encountered on F2P, so the appraisal
		// is purely informational and shouldn't be filtered.
		final int combatLvl = table != null ? table.getCombatLevel() : 0;
		final boolean hideMembers = isFreeWorld()
			&& !BossAliases.isBossOrHighTier(name, combatLvl);

		if (!name.equals(cachedLootTarget) || playerCombat != cachedLootCombat
			|| hideMembers != cachedLootHideMembers)
		{
			cachedLootTarget = name;
			cachedLootCombat = playerCombat;
			cachedLootHideMembers = hideMembers;
			cachedValuableLoot = computeValuableLoot(table, playerCombat, hideMembers);
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Valuable Loot -")
			.color(OSRS_GOLD)
			.build());

		if (cachedValuableLoot.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("None")
				.leftColor(DIM)
				.build());
		}
		else
		{
			for (ValuableDrop drop : cachedValuableLoot)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(drop.name)
					.leftColor(OSRS_PARCHMENT)
					.right(drop.valueText)
					.rightColor(drop.unique ? UNIQUE : OSRS_GOLD)
					.build());
			}
		}

		spacer();
	}

	/**
	 * The top {@link #MAX_VALUABLE_DROPS} drops worth chasing for this account.
	 * A drop qualifies if it is either:
	 * <ul>
	 *   <li>tradeable and worth at least {@link #chaseThreshold(int)} (scales
	 *       with combat level — cheap useful drops count for low-levels), or</li>
	 *   <li>an untradeable unique (GE price 0) rarer than
	 *       {@link #RARE_UNIQUE_DENOM} — pets, jars, unique gear.</li>
	 * </ul>
	 * Uniques are listed first (rarest first), then tradeables by value.
	 */
	private List<ValuableDrop> computeValuableLoot(DropTable table, int playerCombat, boolean hideMembers)
	{
		final int threshold = chaseThreshold(playerCombat);
		final Map<String, Boolean> seen = new LinkedHashMap<>();
		final List<ValuableDrop> out = new ArrayList<>();
		for (DropTable.Entry entry : table.getDrops())
		{
			final String itemName = entry.getName();
			if (itemName == null || itemName.equalsIgnoreCase("Nothing")
				|| seen.put(itemName, Boolean.TRUE) != null)
			{
				continue;
			}
			final int id = resolveItemId(itemName);
			if (id < 0)
			{
				continue;
			}
			// Skip members-only items on a free world. Uses the shared membership
			// index so untradeables (champion scrolls etc.) are covered too.
			if (hideMembers && itemMembership.isMembers(itemName))
			{
				continue;
			}
			final int price = itemManager.getItemPrice(id);
			final long denom = entry.getRate() > 0 ? Math.round(1.0 / entry.getRate()) : 0;

			final boolean tradeableValuable = price >= threshold;
			final boolean untradeableUnique = price == 0 && denom >= RARE_UNIQUE_DENOM;
			if (untradeableUnique)
			{
				out.add(new ValuableDrop(itemName, "Unique", true, price, denom));
			}
			else if (tradeableValuable)
			{
				out.add(new ValuableDrop(itemName, QuantityFormatter.quantityToRSDecimalStack(price),
					false, price, denom));
			}
		}
		// Uniques first (rarest first), then tradeables by value (highest first).
		out.sort((a, b) ->
		{
			if (a.unique != b.unique)
			{
				return a.unique ? -1 : 1;
			}
			return a.unique ? Long.compare(b.denom, a.denom) : Long.compare(b.price, a.price);
		});
		return out.size() > MAX_VALUABLE_DROPS
			? new ArrayList<>(out.subList(0, MAX_VALUABLE_DROPS))
			: out;
	}

	/** True when the player is logged into a free-to-play (non-members) world. */
	private boolean isFreeWorld()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		final java.util.EnumSet<WorldType> worldType = client.getWorldType();
		return worldType != null && !worldType.contains(WorldType.MEMBERS);
	}

	/** Resolves an item name to its ID via an exact (case-insensitive) name match, or -1. */
	private int resolveItemId(String name)
	{
		return itemManager.search(name).stream()
			.filter(r -> name.equalsIgnoreCase(r.getName()))
			.map(r -> r.getId())
			.findFirst()
			.orElse(-1);
	}

	/** A drop worth chasing for the Valuable Loot section. */
	private static final class ValuableDrop
	{
		private final String name;
		private final String valueText;
		private final boolean unique;
		private final long price;
		private final long denom;

		private ValuableDrop(String name, String valueText, boolean unique, long price, long denom)
		{
			this.name = name;
			this.valueText = valueText;
			this.unique = unique;
			this.price = price;
			this.denom = denom;
		}
	}

	// ---------------------------------------------------------------------
	// Resource node analysis
	// ---------------------------------------------------------------------

	private void renderObject(String objectName)
	{
		final int id = this.objectId;
		final String examine = objectExamineService.getExamine(id, objectName);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Appraise Window ~")
			.color(OSRS_GOLD)
			.build());

		spacer();

		addRow("Object", objectName, OSRS_ORANGE);
		addRow("Role", "World object", OSRS_PARCHMENT);

		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Description -")
			.color(OSRS_GOLD)
			.build());
		if (examine != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(examine)
				.leftColor(OSRS_PARCHMENT)
				.build());
		}
		else
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("No additional Appraise data available yet.")
				.leftColor(DIM)
				.build());
		}

		spacer();
	}

	private void renderResource(String objectName)
	{
		final List<ResourceData.ResourceEntry> entries = resourceEntries;
		if (entries.isEmpty())
		{
			return;
		}
		if (entries.size() == 1)
		{
			renderSingleResource(objectName, entries.get(0));
		}
		else
		{
			renderMultiResource(objectName, entries);
		}
	}

	/** Single-resource node (tree, rock) — yields one resource. */
	private void renderSingleResource(String objectName, ResourceData.ResourceEntry entry)
	{
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Appraise Window ~")
			.color(OSRS_GOLD)
			.build());

		spacer();

		addRow("Node", objectName, OSRS_ORANGE);
		addRow("Skill", capitalize(entry.getSkill().getName()), OSRS_PARCHMENT);

		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Resource -")
			.color(OSRS_GOLD)
			.build());

		addResourceYieldRow("Yields", resourceOutputLabel(entry), "Lv " + entry.getLevelRequired(), OSRS_PARCHMENT);
		addRow("XP", String.format("%.1f", entry.getXpPerAction()), OSRS_PARCHMENT);

		if (!entry.getUses().isEmpty())
		{
			spacer();
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("- Uses -")
				.color(OSRS_GOLD)
				.build());
			for (String use : entry.getUses())
			{
				addUseRow(use);
			}
		}

		// Pet odds at the current level — the rock/tree base rate. We deliberately don't
		// show a dry streak / chance-seen: the plugin can't see your true lifetime actions
		// or pet ownership, so any such figure would be misleading.
		final int level = skillTracker.getCurrentLevel(entry.getSkill());
		if (level > 0)
		{
			final ResourceData resourceData = skillTracker.getResourceData();
			final ResourceData.SkillData skillData = resourceData.getSkillData(entry.getSkill());
			if (skillData != null && skillData.getPetBaseChance() > 0)
			{
				spacer();
				panelComponent.getChildren().add(TitleComponent.builder()
					.text("- Pet (lvl " + level + ") -")
					.color(OSRS_GOLD)
					.build());

				final int petBase = entry.getEffectivePetBaseChance(skillData.getPetBaseChance());
				addRow("Odds", PetDisplay.oddsText(petBase, level), OSRS_ORANGE);
			}
		}

		renderObservedObjectDescription(resourceObjectId, objectName);
		spacer();
	}

	/**
	 * Multi-resource node (e.g. a fishing spot) — lists every resource it can yield, tagging
	 * the one you're currently catching with "(active)". Pet odds use the active resource's
	 * rate where known, else the skill default.
	 */
	private void renderMultiResource(String objectName, List<ResourceData.ResourceEntry> entries)
	{
		final ResourceData.ResourceEntry first = entries.get(0);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("~ Appraise Window ~")
			.color(OSRS_GOLD)
			.build());

		spacer();

		addRow("Node", objectName, OSRS_ORANGE);
		addRow("Skill", capitalize(first.getSkill().getName()), OSRS_PARCHMENT);

		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Catches -")
			.color(OSRS_GOLD)
			.build());

		// "Catchable" = you hold the method's tool AND any required secondary (feathers/bait/etc.).
		// Green = you can catch it right now (e.g. a fly rod + feathers lights up lure fish but not
		// bait fish); dim = you're missing the tool or bait for it.
		final List<ResourceData.ResourceEntry> sorted = new ArrayList<>(entries);
		sorted.sort((a, b) -> Integer.compare(a.getLevelRequired(), b.getLevelRequired()));
		ResourceData.ResourceEntry firstCatchable = null;
		for (ResourceData.ResourceEntry e : sorted)
		{
			final boolean catchable = skillTracker.canCatch(e);
			if (catchable && firstCatchable == null)
			{
				firstCatchable = e;
			}
			addResourceYieldRow(methodLabel(e.getMethod()), resourceOutputLabel(e),
				"Lv " + e.getLevelRequired(), catchable ? MET : DIM);
		}
		final ResourceData.ResourceEntry petSource = firstCatchable;

		// Pet odds at current level — use the first fish you can catch (Heron rate varies by
		// fish), else the skill default. Odds-only, like WC/Mining.
		final int level = skillTracker.getCurrentLevel(first.getSkill());
		if (level > 0)
		{
			final ResourceData.SkillData skillData = skillTracker.getResourceData().getSkillData(first.getSkill());
			if (skillData != null && skillData.getPetBaseChance() > 0)
			{
				spacer();
				panelComponent.getChildren().add(TitleComponent.builder()
					.text("- Pet (lvl " + level + ") -")
					.color(OSRS_GOLD)
					.build());

				final int petBase = petSource != null
					? petSource.getEffectivePetBaseChance(skillData.getPetBaseChance())
					: skillData.getPetBaseChance();
				addRow("Odds", PetDisplay.oddsText(petBase, level), OSRS_ORANGE);
			}
		}

		renderObservedObjectDescription(resourceObjectId, objectName);
		spacer();
	}

	private void renderObservedObjectDescription(int id, String objectName)
	{
		final String examine = objectExamineService.getExamine(id, objectName);
		if (examine == null)
		{
			return;
		}
		spacer();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("- Description -")
			.color(OSRS_GOLD)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(examine)
			.leftColor(OSRS_PARCHMENT)
			.build());
	}

	/** Human-readable prefix for a fishing method, e.g. {@code "Lure · "}. Empty for none. */
	static String methodLabel(String method)
	{
		if (method == null)
		{
			return "";
		}
		switch (method)
		{
			case "net": return "Net";
			case "bignet": return "Big net";
			case "bait": return "Bait";
			case "lure": return "Lure";
			case "cage": return "Cage";
			case "harpoon": return "Harpoon";
			case "vessel": return "Vessel";
			default: return capitalize(method);
		}
	}

	static String resourceOutputLabel(ResourceData.ResourceEntry entry)
	{
		return entry == null ? "" : entry.getName();
	}

	static String explicitNotedName(String name, boolean noted)
	{
		if (!noted || name == null || name.toLowerCase().endsWith(" (noted)"))
		{
			return name;
		}
		return name + " (noted)";
	}

	static String inferNpcRole(String name, String examine)
	{
		final String text = searchableNpcText(name, examine);
		if (containsAny(text, "guide", "advisor", "tutor"))
		{
			return "Guide";
		}
		if (containsAny(text, "bank", "banker"))
		{
			return "Banking NPC";
		}
		if (containsAny(text, "shop", "shopkeeper", "merchant", "trader", "seller"))
		{
			return "Merchant";
		}
		if (containsAny(text, "sailor", "captain", "ferry", "boat", "ship", "transport"))
		{
			return "Transport NPC";
		}
		if (containsAny(text, "quest", "task", "diary"))
		{
			return "Quest-related NPC";
		}
		return "Non-combat NPC";
	}

	static String inferNpcService(String name, String examine)
	{
		final String text = searchableNpcText(name, examine);
		if (containsAny(text, "bank", "banker"))
		{
			return "Banking";
		}
		if (containsAny(text, "shop", "shopkeeper", "merchant", "trader", "seller"))
		{
			return "Trading/shop";
		}
		if (containsAny(text, "sailor", "captain", "ferry", "boat", "ship", "transport"))
		{
			return "Travel";
		}
		if (containsAny(text, "guide", "advisor", "tutor"))
		{
			return "Information";
		}
		return null;
	}

	private static String searchableNpcText(String name, String examine)
	{
		return ((name == null ? "" : name) + " " + (examine == null ? "" : examine)).toLowerCase();
	}

	private static boolean containsAny(String text, String... needles)
	{
		for (String needle : needles)
		{
			if (text.contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}

	// ---------------------------------------------------------------------
	// Row helpers
	// ---------------------------------------------------------------------

	private void addRow(String label, String value, Color valueColor)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(label)
			.leftColor(OSRS_LABEL)
			.right(value)
			.rightColor(valueColor)
			.build());
	}

	private void addResourceYieldRow(String method, String itemName, String level, Color itemColor)
	{
		panelComponent.getChildren().add(new ResourceYieldLine(method, itemName, level, itemColor));
	}

	/**
	 * Renders a stat that may carry several comma-separated values (e.g. a boss's
	 * per-style max hits). Single values render as a normal row; multi-value
	 * stats become a label line with each value on its own indented line, so they
	 * don't wrap mid-word in the fixed-width panel.
	 */
	private void addStatBlock(String label, String value)
	{
		if (value == null || value.isEmpty())
		{
			addRow(label, "??", DIM);
			return;
		}
		final String[] parts = value.split("\\s*,\\s*");
		if (parts.length <= 1)
		{
			addRow(label, value, OSRS_PARCHMENT);
			return;
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left(label)
			.leftColor(OSRS_LABEL)
			.build());
		for (String part : parts)
		{
			if (part.isEmpty())
			{
				continue;
			}
			panelComponent.getChildren().add(LineComponent.builder()
				.left("  " + part)
				.leftColor(OSRS_PARCHMENT)
				.build());
		}
	}

	private void addLocked(String label, int requiredKc)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(label)
			.leftColor(OSRS_LABEL)
			.right("?? (" + requiredKc + " kc)")
			.rightColor(DIM)
			.build());
	}

	private void spacer()
	{
		panelComponent.getChildren().add(LineComponent.builder().left(" ").build());
	}

	/**
	 * Pads a portrait into a panel-content-width transparent canvas with the image
	 * centred, so it sits in the middle of the Appraise window rather than flush
	 * left. Images already at least as wide as the content area are returned as-is.
	 */
	private static BufferedImage centerInPanel(BufferedImage img)
	{
		final int w = Math.max(CONTENT_WIDTH, img.getWidth());
		if (w == img.getWidth())
		{
			return img;
		}
		final BufferedImage canvas = new BufferedImage(w, img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas.createGraphics();
		g.drawImage(img, (w - img.getWidth()) / 2, 0, null);
		g.dispose();
		return canvas;
	}

	private static final class ResourceYieldLine implements LayoutableRenderableEntity
	{
		private static final int COLUMN_GAP = 8;

		private final String method;
		private final String itemName;
		private final String level;
		private final Color itemColor;
		private final Rectangle bounds = new Rectangle();
		private Point preferredLocation = new Point();
		private Dimension preferredSize = new Dimension(CONTENT_WIDTH, 0);

		private ResourceYieldLine(String method, String itemName, String level, Color itemColor)
		{
			this.method = method == null ? "" : method;
			this.itemName = itemName == null ? "" : itemName;
			this.level = level == null ? "" : level;
			this.itemColor = itemColor == null ? OSRS_PARCHMENT : itemColor;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}

		@Override
		public void setPreferredLocation(Point position)
		{
			this.preferredLocation = position == null ? new Point() : position;
		}

		@Override
		public void setPreferredSize(Dimension dimension)
		{
			this.preferredSize = dimension == null ? new Dimension(CONTENT_WIDTH, 0) : dimension;
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			final FontMetrics metrics = graphics.getFontMetrics();
			final int width = preferredSize.width;
			final int height = metrics.getHeight();
			final int baseline = preferredLocation.y + height;
			final TextComponent text = new TextComponent();

			draw(text, graphics, method, OSRS_LABEL, preferredLocation.x, baseline);

			final int methodWidth = metrics.stringWidth(method);
			final int levelWidth = metrics.stringWidth(level);
			final int levelX = preferredLocation.x + width - levelWidth;
			draw(text, graphics, level, itemColor, levelX, baseline);

			final int centerMin = preferredLocation.x + methodWidth + COLUMN_GAP;
			final int centerMaxWidth = Math.max(0, levelX - COLUMN_GAP - centerMin);
			final String visibleItem = truncateToWidth(itemName, centerMaxWidth, metrics);
			final int itemWidth = metrics.stringWidth(visibleItem);
			final int centeredX = preferredLocation.x + (width - itemWidth) / 2;
			final int itemX = Math.max(centerMin, Math.min(centeredX, levelX - COLUMN_GAP - itemWidth));
			draw(text, graphics, visibleItem, itemColor, itemX, baseline);

			final Dimension rendered = new Dimension(width, height);
			bounds.setLocation(preferredLocation);
			bounds.setSize(rendered);
			return rendered;
		}

		private static void draw(TextComponent text, Graphics2D graphics, String value, Color color, int x, int y)
		{
			if (value == null || value.isEmpty())
			{
				return;
			}
			text.setText(value);
			text.setColor(color);
			text.setPosition(x, y);
			text.render(graphics);
		}

		private static String truncateToWidth(String value, int maxWidth, FontMetrics metrics)
		{
			if (value == null || value.isEmpty() || metrics.stringWidth(value) <= maxWidth)
			{
				return value == null ? "" : value;
			}
			final String suffix = "...";
			final int suffixWidth = metrics.stringWidth(suffix);
			if (maxWidth <= suffixWidth)
			{
				return "";
			}
			String trimmed = value;
			while (!trimmed.isEmpty() && metrics.stringWidth(trimmed + suffix) > maxWidth)
			{
				trimmed = trimmed.substring(0, trimmed.length() - 1);
			}
			return trimmed + suffix;
		}
	}

	// --- MouseListener: right-click on overlay to close, consume menu ---

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (targetName != null && javax.swing.SwingUtilities.isRightMouseButton(e)
			&& getBounds() != null && getBounds().contains(e.getPoint()))
		{
			dismiss();
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseReleased(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseEntered(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseExited(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseDragged(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseMoved(MouseEvent e) { return e; }
}
