package com.systeminterface.modules.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

/**
 * Persists per-mob rarity-section collapse state as JSON in the plugin's data
 * directory. Replaces the earlier approach of storing one ConfigManager key per
 * mob, which polluted the profile config namespace.
 *
 * <p>On first construction, migrates any existing collapse keys from
 * ConfigManager and deletes them.
 */
@Slf4j
public final class CollapseStateStore
{
	private static final Path STATE_DIR = RuneLite.RUNELITE_DIR.toPath().resolve("system-interface");
	private static final Path FILE = STATE_DIR.resolve("collapse-state.json");
	private static final Type MAP_TYPE = new TypeToken<Map<String, Set<String>>>() {}.getType();

	private final Gson gson;
	private final Map<String, Set<String>> state = new ConcurrentHashMap<>();

	public CollapseStateStore(Gson gson, ConfigManager configManager, String configGroup)
	{
		this.gson = gson;
		load();
		migrateFromConfig(configManager, configGroup);
	}

	public boolean isSectionCollapsed(String mob, String section)
	{
		if (mob == null)
		{
			return true;
		}
		Set<String> expanded = state.get(sanitizeKey(mob));
		return expanded == null || expanded.stream().noneMatch(s -> s.equalsIgnoreCase(section));
	}

	public void toggleSection(String mob, String section)
	{
		if (mob == null)
		{
			return;
		}
		String key = sanitizeKey(mob);
		Set<String> set = state.computeIfAbsent(key, k -> new LinkedHashSet<>());
		synchronized (set)
		{
			if (!set.removeIf(s -> s.equalsIgnoreCase(section)))
			{
				set.add(section);
			}
		}
		flush();
	}

	public void setSectionExpanded(String mob, String section, boolean expanded)
	{
		if (mob == null || section == null)
		{
			return;
		}
		String key = sanitizeKey(mob);
		Set<String> set = state.computeIfAbsent(key, k -> new LinkedHashSet<>());
		boolean changed;
		synchronized (set)
		{
			if (expanded)
			{
				changed = set.add(section);
			}
			else
			{
				changed = set.removeIf(s -> s.equalsIgnoreCase(section));
			}
		}
		if (changed)
		{
			flush();
		}
	}

	private void load()
	{
		if (!Files.exists(FILE))
		{
			return;
		}
		try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8))
		{
			Map<String, Set<String>> loaded = gson.fromJson(r, MAP_TYPE);
			if (loaded != null)
			{
				state.putAll(loaded);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to load collapse state", e);
		}
	}

	private void flush()
	{
		try
		{
			Files.createDirectories(STATE_DIR);
			Files.write(FILE, gson.toJson(state, MAP_TYPE).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to write collapse state", e);
		}
	}

	private void migrateFromConfig(ConfigManager configManager, String configGroup)
	{
		boolean migrated = false;
		for (String key : configManager.getConfigurationKeys(configGroup + ".collapse_"))
		{
			String shortKey = key.startsWith(configGroup + ".") ? key.substring(configGroup.length() + 1) : key;
			if (!shortKey.startsWith("collapse_"))
			{
				continue;
			}
			String mobKey = shortKey.substring("collapse_".length());
			String csv = configManager.getConfiguration(configGroup, shortKey);
			if (csv != null && !csv.isEmpty())
			{
				Set<String> sections = new LinkedHashSet<>();
				for (String s : csv.split("\\|"))
				{
					if (!s.trim().isEmpty())
					{
						sections.add(s.trim());
					}
				}
				if (!sections.isEmpty())
				{
					state.put(mobKey, sections);
					migrated = true;
				}
			}
			configManager.unsetConfiguration(configGroup, shortKey);
		}
		if (migrated)
		{
			flush();
			log.debug("Migrated collapse state from ConfigManager to JSON");
		}
	}

	private static String sanitizeKey(String mob)
	{
		return mob.replaceAll("[^A-Za-z0-9]", "_");
	}
}
