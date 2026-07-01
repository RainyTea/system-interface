package com.systeminterface.services.lookup;

import com.systeminterface.common.model.BestiaryRank;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps common boss abbreviations and nicknames to their canonical names so the
 * {@code !analyze} chat command can accept shorthand — {@code !analyze cerb}
 * resolves to "Cerberus", {@code !analyze kq} to "Kalphite Queen", etc.
 *
 * <p>The alias set mirrors RuneLite's own {@code ChatCommandsPlugin} boss
 * aliases (so muscle memory carries over from {@code !kc}). Credit: RuneLite
 * {@code ChatCommandsPlugin}.
 *
 * <p>Two deliberate simplifications:
 * <ul>
 *   <li><b>Awakened DT2 variants</b> fold into their base boss (e.g. "duke
 *       awakened" → "Duke Sucellus"). The OSRS Wiki serves awakened drops as a
 *       tab on the base page, which our MediaWiki wikitext fetch can't target
 *       yet — see HANDOVER's multi-tab limitation — so the base table is the
 *       best available result.</li>
 *   <li><b>Parametric kc forms</b> ("cox [1-24] players", challenge/entry/expert
 *       modes) are omitted — they're scoreboard variants, not distinct
 *       drop-table pages.</li>
 * </ul>
 */
public final class BossAliases
{
	private static final Map<String, String> ALIASES = new HashMap<>();

	private BossAliases()
	{
	}

	/**
	 * @return the canonical boss name for {@code input} (an alias or a full
	 *         name), or {@code null} if it isn't a recognised boss.
	 */
	public static String canonicalize(String input)
	{
		if (input == null)
		{
			return null;
		}
		return ALIASES.get(norm(input));
	}

	/**
	 * Whether the named target should bypass F2P membership filtering.
	 * True for known bosses (alias map) and high-tier NPCs (BestiaryRank A+,
	 * combat level 121+). These monsters cannot be encountered on F2P worlds,
	 * so filtering their drops would remove useful informational content.
	 *
	 * @param name      NPC display name
	 * @param combatLvl NPC combat level (0 if unknown)
	 */
	public static boolean isBossOrHighTier(String name, int combatLvl)
	{
		if (canonicalize(name) != null)
		{
			return true;
		}
		return combatLvl > 0
			&& BestiaryRank.fromCombatLevel(combatLvl).ordinal() >= BestiaryRank.A.ordinal();
	}

	private static String norm(String s)
	{
		return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	/** Registers a canonical name plus its aliases (the canonical name maps to itself too). */
	private static void put(String canonical, String... aliases)
	{
		ALIASES.put(norm(canonical), canonical);
		for (String alias : aliases)
		{
			ALIASES.put(norm(alias), canonical);
		}
	}

	static
	{
		put("Grotesque Guardians", "dusk", "dawn", "gargs", "ggs", "gg");
		put("Abyssal Sire", "sire");
		put("Cerberus", "cerb");
		put("Thermonuclear Smoke Devil", "smoke devil", "thermy");
		put("Alchemical Hydra", "hydra");
		put("Amoxliatl", "amox");
		put("Hueycoatl", "huey", "the hueycoatl");
		put("Deranged Archaeologist", "deranged arch");
		put("Crazy Archaeologist", "crazy arch");
		put("Chaos Elemental", "chaos ele");
		put("Vet'ion", "vetion");
		put("Calvar'ion", "calv", "calvarion");
		put("Venenatis", "vene");
		put("King Black Dragon", "kbd");
		put("Corporeal Beast", "corp");
		put("Kalphite Queen", "kq");
		put("Giant Mole", "mole");
		put("Vorkath", "vork");
		put("Phantom Muspah", "phantom", "muspah", "pm");
		put("Nightmare", "nm", "tnm", "nmare", "the nightmare");
		put("Phosani's Nightmare", "pnm", "phosani", "phosanis", "phosani nm",
			"phosani nightmare", "phosanis nightmare");
		put("Commander Zilyana", "sara", "saradomin", "zilyana", "zily");
		put("K'ril Tsutsaroth", "zammy", "zamorak", "kril", "kril tsutsaroth", "kril trutsaroth");
		put("Kree'arra", "arma", "kree", "kreearra", "armadyl");
		put("General Graardor", "bando", "bandos", "graardor");
		put("Dagannoth Supreme", "supreme");
		put("Dagannoth Rex", "rex");
		put("Dagannoth Prime", "prime");
		// Awakened DT2 variants fold into their base boss (see class javadoc).
		put("Duke Sucellus", "duke", "duke awakened", "duke sucellus awakened");
		put("Leviathan", "levi", "the leviathan", "levi awakened",
			"leviathan awakened", "the leviathan awakened");
		put("Vardorvis", "vard", "vard awakened", "vardorvis awakened");
		put("Whisperer", "wisp", "whisp", "the whisperer", "wisp awakened",
			"whisp awakened", "whisperer awakened");
		put("Barrows Chests", "barrows");
		put("Lunar Chest", "lunar chests", "moons of peril", "perilous moon", "perilous moons");
		put("Gauntlet", "gaunt", "the gauntlet");
		put("Corrupted Gauntlet", "cg", "cgaunt", "cgauntlet", "the corrupted gauntlet");
		put("TzTok-Jad", "jad", "tzhaar fight cave");
		put("TzKal-Zuk", "zuk", "inferno");
		put("Sol Heredit", "sol", "colo", "colosseum", "fortis colosseum");
		put("Chambers of Xeric", "cox", "xeric", "chambers", "olm", "raids");
		put("Theatre of Blood", "tob", "theatre", "verzik", "verzik vitur", "raids 2");
		put("Tombs of Amascut", "toa", "tombs", "amascut", "warden", "wardens", "raids 3");
	}
}
