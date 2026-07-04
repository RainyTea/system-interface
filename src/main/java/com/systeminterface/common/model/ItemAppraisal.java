package com.systeminterface.common.model;

/**
 * Heuristic "appraisal" of an item: an F–SS rank, a class label, and a short
 * description — judged by <em>progression / use</em> rather than GE value alone.
 *
 * <p>Rationale (per user direction): metal tier means more than coins — rune
 * gear is end-game in F2P even though it's cheap, and a dragon pickaxe is a
 * top-tier <em>skilling</em> tool that shouldn't be marked down for weak combat
 * stats. So the rank is the higher of a name-derived material tier and the raw
 * value tier; skilling tools are ranked on their material grade, not combat.
 *
 * <p>This is a heuristic, not a curated database — it nails the common metal
 * tiers and tool/weapon/armour split, and falls back to value for everything
 * else (high-end uniques, resources, misc).
 */
public final class ItemAppraisal
{
	/** Broad item class, used for the description and to avoid combat-penalising tools. */
	public enum ItemClass
	{
		WEAPON("Weapon"),
		ARMOUR("Armour"),
		TOOL("Skilling tool"),
		AMMUNITION("Ammunition"),
		RUNE("Rune"),
		FOOD("Food"),
		POTION("Potion"),
		HERBLORE("Herblore ingredient"),
		CRAFTING("Crafting material"),
		RESOURCE("Resource"),
		NOTED("Noted item"),
		MISC("Item");

		private final String label;

		ItemClass(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	private final ItemRank rank;
	private final ItemClass itemClass;
	private final String description;
	private final String skillUse;
	private final String primaryContext;
	private final ItemRank secondaryRank;
	private final String secondaryContext;

	private ItemAppraisal(ItemRank rank, ItemClass itemClass, String description, String skillUse)
	{
		this(rank, itemClass, description, skillUse, null, null, null);
	}

	private ItemAppraisal(ItemRank rank, ItemClass itemClass, String description, String skillUse,
		String primaryContext, ItemRank secondaryRank, String secondaryContext)
	{
		this.rank = rank;
		this.itemClass = itemClass;
		this.description = description;
		this.skillUse = skillUse;
		this.primaryContext = primaryContext;
		this.secondaryRank = secondaryRank;
		this.secondaryContext = secondaryContext;
	}

	public ItemRank getRank() { return rank; }
	public ItemClass getItemClass() { return itemClass; }
	public String getDescription() { return description; }
	/** The skill associated with a tool, or {@code null} for non-tools. */
	public String getSkillUse() { return skillUse; }

	/**
	 * Label for the primary {@link #getRank() rank} when the item is meaningfully
	 * ranked in two contexts (e.g. {@code "Skill"} for a pickaxe judged as a mining
	 * tool). {@code null} for single-context items, where the rank needs no qualifier.
	 */
	public String getPrimaryContext() { return primaryContext; }

	/**
	 * The secondary, other-context rank — e.g. a rune pickaxe's weak <em>combat</em>
	 * rank alongside its strong skilling-tool rank. {@code null} when the item has only
	 * one meaningful context.
	 */
	public ItemRank getSecondaryRank() { return secondaryRank; }

	/** Label for {@link #getSecondaryRank()} (e.g. {@code "Combat"}), or {@code null}. */
	public String getSecondaryContext() { return secondaryContext; }

	/**
	 * @param name        item name
	 * @param value       GE value (gp)
	 * @param equipable   whether the item can be equipped
	 * @param equipSlot   equipment slot id (3 = weapon, 13 = ammo), or -1 if not equipable
	 */
	public static ItemAppraisal appraise(String name, int value, boolean equipable, int equipSlot)
	{
		return appraise(name, value, equipable, equipSlot, false);
	}

	/**
	 * @param name        item name
	 * @param value       GE value (gp)
	 * @param equipable   whether the item can be equipped
	 * @param equipSlot   equipment slot id (3 = weapon, 13 = ammo), or -1 if not equipable
	 * @param noted       whether the item is in noted form
	 */
	public static ItemAppraisal appraise(String name, int value, boolean equipable, int equipSlot, boolean noted)
	{
		final String lower = name == null ? "" : name.toLowerCase();

		if (noted)
		{
			final ItemRank valueRank = ItemRank.fromValue(value);
			return new ItemAppraisal(valueRank, ItemClass.NOTED,
				"Noted form of " + name + ". Trade or bank to use.", null);
		}

		final ItemClass cls = classify(lower, equipable, equipSlot);
		final String skill = cls == ItemClass.TOOL ? toolSkill(lower) : null;

		final int tier = (cls == ItemClass.WEAPON || cls == ItemClass.ARMOUR || cls == ItemClass.TOOL)
			? materialTier(lower) : 0;

		final ItemRank valueRank = ItemRank.fromValue(value);
		final ItemRank rank = tier > 0 ? higher(tierRank(tier), valueRank) : valueRank;

		// Dual ranking: a skilling tool that can also be wielded (pickaxe, woodcutting
		// axe — equip slot 3) is ranked by its skill grade as the primary rank, with a
		// secondary "combat" rank reflecting how poor it is as an actual weapon. A rune
		// pickaxe is A-grade mining but only D-grade combat. We only surface the dual
		// rank when the two differ; otherwise it's noise.
		if (cls == ItemClass.TOOL && equipable && equipSlot == 3 && rank != valueRank)
		{
			return new ItemAppraisal(rank, cls, describe(cls, rank, tier, lower, skill), skill,
				"Skill", valueRank, "Combat");
		}

		return new ItemAppraisal(rank, cls, describe(cls, rank, tier, lower, skill), skill);
	}

	// ------------------------------------------------------------------

	private static ItemClass classify(String lower, boolean equipable, int equipSlot)
	{
		if (isTool(lower))
		{
			return ItemClass.TOOL;
		}
		if (isSpellRune(lower))
		{
			return ItemClass.RUNE;
		}
		if (isPotion(lower))
		{
			return ItemClass.POTION;
		}
		if (isFood(lower))
		{
			return ItemClass.FOOD;
		}
		if (isHerbloreIngredient(lower))
		{
			return ItemClass.HERBLORE;
		}
		if (isCraftingMaterial(lower))
		{
			return ItemClass.CRAFTING;
		}
		if (equipable)
		{
			if (equipSlot == 3)
			{
				return ItemClass.WEAPON;
			}
			if (equipSlot == 13)
			{
				return ItemClass.AMMUNITION;
			}
			return ItemClass.ARMOUR;
		}
		if (isResource(lower))
		{
			return ItemClass.RESOURCE;
		}
		return ItemClass.MISC;
	}

	private static boolean isTool(String lower)
	{
		if (lower.contains("pickaxe") || lower.contains("harpoon") || lower.contains("fishing rod")
			|| lower.contains("fishing net") || lower.contains("lobster pot") || lower.contains("butterfly net")
			|| lower.contains("bird snare") || lower.contains("box trap") || lower.contains("fishing explosive")
			|| lower.contains("chisel") || lower.contains("needle") || lower.contains("tinderbox")
			|| lower.contains("rake") || lower.contains("spade") || lower.contains("secateurs")
			|| lower.contains("trowel") || lower.contains("saw") || lower.contains("hammer")
			|| lower.contains("knife") || lower.contains("machete") || lower.contains("teasing stick")
			|| lower.contains("watering can") || lower.contains("seed dibber"))
		{
			return true;
		}
		return lower.endsWith(" axe")
			&& !lower.contains("battleaxe") && !lower.contains("greataxe") && !lower.contains("throwing");
	}

	private static String toolSkill(String lower)
	{
		if (lower.contains("pickaxe")) return "Mining";
		if (lower.contains("harpoon") || lower.contains("fishing rod") || lower.contains("fishing net")
			|| lower.contains("lobster pot") || lower.contains("fishing explosive")) return "Fishing";
		if (lower.endsWith(" axe")) return "Woodcutting";
		if (lower.contains("tinderbox")) return "Firemaking";
		if (lower.contains("chisel")) return "Crafting";
		if (lower.contains("needle")) return "Crafting";
		if (lower.contains("hammer")) return "Smithing";
		if (lower.contains("knife")) return "Fletching";
		if (lower.contains("saw")) return "Construction";
		if (lower.contains("rake") || lower.contains("spade") || lower.contains("secateurs")
			|| lower.contains("watering can") || lower.contains("seed dibber")
			|| lower.contains("trowel")) return "Farming";
		if (lower.contains("butterfly net") || lower.contains("bird snare")
			|| lower.contains("box trap") || lower.contains("teasing stick")) return "Hunter";
		if (lower.contains("machete")) return "Woodcutting";
		return null;
	}

	private static boolean isSpellRune(String lower)
	{
		return (lower.endsWith(" rune") || lower.endsWith(" runes")) && !lower.startsWith("rune ");
	}

	private static boolean isPotion(String lower)
	{
		return lower.contains("potion") || lower.contains(" brew") || lower.contains("antidote")
			|| lower.contains("antifire") || lower.contains("antipoison") || lower.contains("antivenom")
			|| lower.contains("restore") || lower.contains("overload") || lower.contains("sanfew");
	}

	private static boolean isFood(String lower)
	{
		if (lower.startsWith("raw ")) return true;
		return lower.contains("lobster") || lower.contains("swordfish") || lower.contains("shark")
			|| lower.contains("monkfish") || lower.contains("manta ray") || lower.contains("anglerfish")
			|| lower.contains("tuna") || lower.contains("trout") || lower.contains("salmon")
			|| lower.contains("bass") || lower.contains("karambwan") || lower.contains("cake")
			|| lower.contains("pie") || lower.contains("pizza") || lower.contains("bread")
			|| lower.contains("meat") || lower.contains("chicken") || lower.contains("shrimps")
			|| lower.contains("anchovies") || lower.contains("sardine") || lower.contains("herring")
			|| lower.contains("mackerel") || lower.contains("cod") || lower.contains("snail")
			|| lower.equals("dark crab") || lower.contains("stew");
	}

	private static boolean isRawFood(String lower)
	{
		return lower.startsWith("raw ");
	}

	private static boolean isHerbloreIngredient(String lower)
	{
		return lower.contains("eye of newt") || lower.contains("unicorn horn") || lower.contains("limpwurt")
			|| lower.contains("red spiders' egg") || lower.contains("white berries")
			|| lower.contains("wine of zamorak") || lower.contains("dragon scale dust")
			|| lower.contains("mort myre fung") || lower.contains("potato cactus")
			|| lower.contains("jangerberries") || lower.contains("snape grass")
			|| lower.contains("crushed nest") || lower.contains("goat horn dust")
			|| lower.contains("bird nest") || lower.contains("torstol")
			|| lower.startsWith("grimy ") || lower.startsWith("clean ")
			|| (lower.endsWith(" potion (unf)"));
	}

	private static boolean isCraftingMaterial(String lower)
	{
		return lower.endsWith(" hide") || lower.endsWith(" leather") || lower.contains("molten glass")
			|| lower.contains("gold bar") || lower.contains("silver bar") || lower.contains("clay")
			|| lower.contains("bowstring") || lower.contains("flax") || lower.contains("wool")
			|| lower.contains("gem") || lower.endsWith(" d'hide");
	}

	private static boolean isResource(String lower)
	{
		return lower.endsWith(" ore") || lower.endsWith(" bar") || lower.endsWith(" log")
			|| lower.endsWith(" logs") || lower.endsWith(" seed")
			|| lower.endsWith(" bones") || lower.endsWith(" essence");
	}

	/** Metal/material progression tier 1–7, or 0 if no recognised material. */
	private static int materialTier(String lower)
	{
		if (lower.contains("dragon")) return 7;
		if (lower.contains("runite") || lower.startsWith("rune ") || lower.contains(" rune ")) return 6;
		if (lower.contains("adamant")) return 5;
		if (lower.contains("mithril")) return 4;
		if (lower.contains("steel") || lower.contains("black ") || lower.contains("white ")) return 3;
		if (lower.contains("iron")) return 2;
		if (lower.contains("bronze")) return 1;
		return 0;
	}

	private static final ItemRank[] TIER_RANK = {
		ItemRank.F, ItemRank.E, ItemRank.D, ItemRank.C, ItemRank.B, ItemRank.A, ItemRank.S
	};

	private static ItemRank tierRank(int tier)
	{
		return TIER_RANK[Math.max(1, Math.min(TIER_RANK.length, tier)) - 1];
	}

	private static ItemRank higher(ItemRank a, ItemRank b)
	{
		return a.ordinal() >= b.ordinal() ? a : b;
	}

	private static String describe(ItemClass cls, ItemRank rank, int tier, String lower, String skill)
	{
		switch (cls)
		{
			case TOOL:
				return "Skilling tool" + (skill != null ? " for " + skill : "")
					+ " — " + grade(rank) + " grade.";
			case WEAPON:
				return grade(rank) + " weapon for its bracket.";
			case ARMOUR:
				return grade(rank) + " armour for its bracket.";
			case AMMUNITION:
				return "Ammunition — " + grade(rank) + " grade.";
			case RUNE:
				return "Spell rune — fuels magic.";
			case FOOD:
				return isRawFood(lower)
					? "Raw ingredient — cook before eating."
					: "Prepared food — restores hitpoints.";
			case POTION:
				return "Consumable potion with combat or skilling effects.";
			case HERBLORE:
				if (lower.startsWith("grimy "))
				{
					return "Grimy herb — clean it to use in Herblore.";
				}
				if (lower.startsWith("clean "))
				{
					return "Cleaned herb — a primary Herblore ingredient.";
				}
				if (lower.endsWith(" potion (unf)"))
				{
					return "Unfinished potion — add a secondary ingredient.";
				}
				return "Herblore secondary — used in potion crafting.";
			case CRAFTING:
				return "Crafting material — used to produce finished goods.";
			case RESOURCE:
				return rank.ordinal() >= ItemRank.C.ordinal()
					? "A valuable skilling resource."
					: "A basic skilling resource.";
			default:
				return rank.ordinal() >= ItemRank.C.ordinal()
					? "A valuable item." : "A common item.";
		}
	}

	private static String grade(ItemRank rank)
	{
		switch (rank)
		{
			case SS: return "Best-in-slot";
			case S:  return "Elite";
			case A:  return "Top";
			case B:  return "Strong";
			case C:  return "Solid";
			case D:  return "Mid";
			case E:  return "Low";
			default: return "Entry";
		}
	}
}
