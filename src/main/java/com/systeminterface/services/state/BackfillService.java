package com.systeminterface.services.state;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Pulls KC data from local RuneLite sources (Loot Tracker, Chatcommands,
 * screenshots) and the OSRS hiscores, feeding "take-max" updates into
 * {@link StateTracker#observeKillCount}. Runs once per profile activation
 * so the player doesn't "start from zero" on plugin install.
 */
@Slf4j
@Singleton
public final class BackfillService
{
	private static final String[] COMMON_NPCS = {
		"Goblin", "Man", "Woman", "Cow", "Chicken", "Rat",
		"Guard", "Dark wizard", "Moss giant", "Hill Giant"
	};

	private static final String[] CHATCOMMAND_BOSSES = {
		"vorkath", "zulrah", "cerberus", "kraken",
		"general graardor", "commander zilyana", "k'ril tsutsaroth", "kree'arra",
		"grotesque guardians", "alchemical hydra", "the gauntlet", "the corrupted gauntlet",
		"theatre of blood", "chambers of xeric", "tombs of amascut",
		"phantom muspah", "duke sucellus", "the whisperer", "vardorvis", "the leviathan",
		"barrows", "giant mole", "dagannoth rex", "dagannoth prime", "dagannoth supreme",
		"kalphite queen", "king black dragon", "corporeal beast",
		"nex", "nightmare", "tempoross", "wintertodt", "zalcano",
		"hespori", "mimic", "obor", "bryophyta", "skotizo", "sarachnis",
		"chaos fanatic", "crazy archaeologist", "deranged archaeologist",
		"scorpia", "chaos elemental", "vet'ion", "venenatis", "callisto"
	};

	private static final String[] HISCORE_BOSSES = {
		"Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Artio",
		"Barrows Chests", "Brutus", "Bryophyta", "Callisto", "Cal'varion", "Cerberus",
		"Chambers of Xeric", "Chambers of Xeric: Challenge Mode",
		"Chaos Elemental", "Chaos Fanatic", "Commander Zilyana",
		"Corporeal Beast", "Crazy Archaeologist", "Dagannoth Prime",
		"Dagannoth Rex", "Dagannoth Supreme", "Deranged Archaeologist",
		"Doom of Mokhaiotl", "Duke Sucellus", "General Graardor", "Giant Mole",
		"Grotesque Guardians", "Hespori", "Kalphite Queen",
		"King Black Dragon", "Kraken", "Kree'arra", "K'ril Tsutsaroth",
		"Lunar Chests", "Mimic", "Nex", "Nightmare", "Phosani's Nightmare",
		"Obor", "Phantom Muspah", "Sarachnis", "Scorpia", "Scurrius",
		"Shellbane Gryphon", "Skotizo", "Sol Heredit", "Spindel", "Tempoross",
		"The Gauntlet", "The Corrupted Gauntlet", "The Hueycoatl",
		"The Leviathan", "The Royal Titans", "The Whisperer",
		"Theatre of Blood", "Theatre of Blood: Hard Mode",
		"Thermonuclear Smoke Devil",
		"Tombs of Amascut", "Tombs of Amascut: Expert Mode",
		"TzKal-Zuk", "TzTok-Jad", "Vardorvis", "Venenatis",
		"Vet'ion", "Vorkath", "Wintertodt", "Yama", "Zalcano", "Zulrah"
	};
	private static final int HISCORE_BOSS_START_LINE = 45;

	private static final Pattern SCREENSHOT_PATTERN =
		Pattern.compile("^(.+?)\\((\\d[\\d,]*)\\)\\.png$");

	private final Gson gson;
	private final OkHttpClient okHttpClient;

	@Inject
	public BackfillService(Gson gson, OkHttpClient okHttpClient)
	{
		this.gson = gson;
		this.okHttpClient = okHttpClient;
	}

	/**
	 * Pull KC from local sources and kick off async hiscores fetch.
	 *
	 * @param stateTracker   the tracker to feed updates into
	 * @param configManager  RuneLite config (for loot tracker + chatcommands)
	 * @param rsn            active player name
	 * @param knownTargets   target names already in the tracker (used to broaden the loot-tracker scan)
	 */
	public void run(StateTracker stateTracker, ConfigManager configManager,
		String rsn, Set<String> knownTargets)
	{
		run(stateTracker, configManager, rsn, knownTargets, null);
	}

	public void run(StateTracker stateTracker, ConfigManager configManager,
		String rsn, Set<String> knownTargets, Runnable onHiscoresComplete)
	{
		if (rsn == null)
		{
			return;
		}
		int found = 0;
		found += backfillFromLootTracker(stateTracker, configManager, knownTargets);
		found += backfillFromChatcommands(stateTracker, configManager);
		found += backfillFromScreenshots(stateTracker, rsn);
		if (found > 0)
		{
			log.debug("Backfill (local) found {} KC update(s)", found);
		}
		backfillFromHiscores(stateTracker, rsn, onHiscoresComplete);
	}

	private int backfillFromLootTracker(StateTracker stateTracker,
		ConfigManager configManager, Set<String> knownTargets)
	{
		int count = 0;
		Set<String> candidates = new HashSet<>(knownTargets);
		Collections.addAll(candidates, COMMON_NPCS);
		Collections.addAll(candidates, HISCORE_BOSSES);

		for (String npcName : candidates)
		{
			String configKey = "drops_NPC_" + npcName;
			String json = configManager.getRSProfileConfiguration("loottracker", configKey);
			if (json == null || json.isEmpty())
			{
				continue;
			}
			int kills = parseKillsFromJson(json);
			if (kills <= 0)
			{
				continue;
			}
			TargetState existing = stateTracker.get(npcName);
			int current = existing != null ? existing.getCurrentKc() : 0;
			if (kills > current)
			{
				stateTracker.observeKillCount(npcName, kills, StateTracker.KcSource.LOOT_TRACKER_LOG);
				count++;
				log.debug("Backfill LootTracker: '{}' {} -> {}", npcName, current, kills);
			}
		}
		log.debug("Backfill: LootTracker found {} update(s)", count);
		return count;
	}

	private int backfillFromChatcommands(StateTracker stateTracker, ConfigManager configManager)
	{
		int count = 0;
		for (String boss : CHATCOMMAND_BOSSES)
		{
			Integer kc = configManager.getRSProfileConfiguration("killcount", boss, int.class);
			if (kc == null || kc <= 0)
			{
				continue;
			}
			String bossName = capitalizeName(boss);
			TargetState existing = stateTracker.get(bossName);
			int current = existing != null ? existing.getCurrentKc() : 0;
			if (kc > current)
			{
				stateTracker.observeKillCount(bossName, kc, StateTracker.KcSource.CHATCOMMANDS_CONFIG);
				count++;
				log.debug("Backfill Chatcommands: '{}' {} -> {}", bossName, current, kc);
			}
		}
		log.debug("Backfill: Chatcommands found {} update(s)", count);
		return count;
	}

	private int backfillFromScreenshots(StateTracker stateTracker, String rsn)
	{
		int count = 0;
		Path screenshotDir = RuneLite.RUNELITE_DIR.toPath()
			.resolve("screenshots").resolve(rsn).resolve("Boss Kills");

		if (!Files.isDirectory(screenshotDir))
		{
			log.debug("Backfill: no Boss Kills screenshot dir for '{}'", rsn);
			return 0;
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(screenshotDir, "*.png"))
		{
			for (Path file : stream)
			{
				Matcher m = SCREENSHOT_PATTERN.matcher(file.getFileName().toString());
				if (!m.matches())
				{
					continue;
				}
				String boss = m.group(1).trim();
				int kc;
				try
				{
					kc = Integer.parseInt(m.group(2).replace(",", ""));
				}
				catch (NumberFormatException e)
				{
					continue;
				}
				TargetState existing = stateTracker.get(boss);
				int current = existing != null ? existing.getCurrentKc() : 0;
				if (kc > current)
				{
					stateTracker.observeKillCount(boss, kc, StateTracker.KcSource.BOSS_KILL_SCREENSHOT);
					count++;
					log.debug("Backfill Screenshot: '{}' {} -> {}", boss, current, kc);
				}
			}
		}
		catch (IOException e)
		{
			log.debug("Backfill: failed to scan screenshots", e);
		}
		log.debug("Backfill: Screenshots found {} update(s)", count);
		return count;
	}

	private void backfillFromHiscores(StateTracker stateTracker, String rsn, Runnable onComplete)
	{
		String url = "https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player="
			+ URLEncoder.encode(rsn, StandardCharsets.UTF_8);

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", "system-interface-plugin (RuneLite)")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Hiscores fetch failed for '{}'", rsn, e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						log.debug("Hiscores fetch unsuccessful for '{}': {}", rsn, response.code());
						return;
					}
					parseHiscores(stateTracker, body.string());
					if (onComplete != null)
					{
						onComplete.run();
					}
				}
				catch (IOException e)
				{
					log.debug("Hiscores I/O error for '{}'", rsn, e);
				}
			}
		});
	}

	private void parseHiscores(StateTracker stateTracker, String csv)
	{
		String[] lines = csv.split("\n");
		int count = 0;
		for (int i = 0; i < HISCORE_BOSSES.length; i++)
		{
			int lineIdx = HISCORE_BOSS_START_LINE + i;
			if (lineIdx >= lines.length)
			{
				break;
			}
			String[] parts = lines[lineIdx].split(",");
			if (parts.length < 2)
			{
				continue;
			}
			int kc;
			try
			{
				kc = Integer.parseInt(parts[1].trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			if (kc <= 0)
			{
				continue;
			}
			String boss = HISCORE_BOSSES[i];
			TargetState existing = stateTracker.get(boss);
			int current = existing != null ? existing.getCurrentKc() : 0;
			if (kc > current)
			{
				stateTracker.observeKillCount(boss, kc, StateTracker.KcSource.HISCORES);
				count++;
				log.debug("Backfill Hiscores: '{}' {} -> {}", boss, current, kc);
			}
		}
		if (count > 0)
		{
			log.debug("Backfill: Hiscores found {} update(s)", count);
		}
	}

	private int parseKillsFromJson(String json)
	{
		try
		{
			JsonObject obj = gson.fromJson(json, JsonObject.class);
			if (obj != null && obj.has("kills"))
			{
				return obj.get("kills").getAsInt();
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to parse loot tracker JSON", e);
		}
		return 0;
	}

	static String capitalizeName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return name;
		}
		StringBuilder sb = new StringBuilder(name.length());
		boolean cap = true;
		for (char c : name.toCharArray())
		{
			if (c == ' ' || c == '-' || c == '\'')
			{
				sb.append(c);
				cap = true;
			}
			else if (cap)
			{
				sb.append(Character.toUpperCase(c));
				cap = false;
			}
			else
			{
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
