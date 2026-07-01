package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.modules.skills.PetDisplay;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

@Singleton
public class SkillingOverlay extends OverlayPanel
{
	private static final Color OSRS_BG = new Color(45, 40, 31, 235);
	private static final Color OSRS_GOLD = new Color(255, 200, 50);
	private static final Color ACCENT = new Color(120, 200, 255);
	private static final Color DIM = new Color(170, 170, 180);
	private static final int PANEL_WIDTH = 220;
	private static final int PANEL_WIDTH_COMPACT = 170;

	private final Client client;
	private final SkillTracker skillTracker;
	private final SystemInterfaceConfig config;

	private long cachedGeneration = -1;
	private volatile Skill currentSourceSkill;
	private volatile String currentSourceAction;
	private volatile String currentSourceName;
	private volatile Skill currentResourceSkill;
	private volatile String currentResourceName;
	private volatile ResourceData.ResourceEntry currentResourceEntry;

	@Inject
	public SkillingOverlay(Client client, SkillTracker skillTracker, SystemInterfaceConfig config)
	{
		this.client = client;
		this.skillTracker = skillTracker;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_LEFT);
	}

	public void setCurrentSkillSourceTarget(Skill skill, String action, String sourceName)
	{
		this.currentSourceSkill = skill;
		this.currentSourceAction = action;
		this.currentSourceName = sourceName;
		clearResourceTarget();
	}

	public void setCurrentSkillResourceTarget(String resourceName, ResourceData.ResourceEntry resource)
	{
		this.currentResourceName = resourceName;
		this.currentResourceEntry = resource;
		this.currentResourceSkill = resource == null ? null : resource.getSkill();
		clearSourceTarget();
	}

	public void clearSkillingTarget()
	{
		clearSourceTarget();
		clearResourceTarget();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showSkillingOverlay())
		{
			return null;
		}

		Skill active = skillTracker.getActiveSkill();
		if (active == null)
		{
			// Not currently gathering: keep the overlay up for the configured auto-hide
			// window after the last action (mirrors the System Panel). 0 = stay until the
			// tracked skill changes. Crucially, only linger if there has been activity
			// THIS session — otherwise a freshly-loaded profile (which surfaces the last
			// skill for the panel log) would make the overlay pop up on login.
			final long sinceActivity = skillTracker.getMillisSinceActivity();
			if (sinceActivity != Long.MAX_VALUE)
			{
				final int hideSeconds = config.hideAfterSeconds();
				if (hideSeconds == 0 || sinceActivity <= hideSeconds * 1000L)
				{
					active = skillTracker.getDisplaySkill();
				}
			}
		}
		if (active == null)
		{
			return null;
		}

		final boolean compact = config.compactOverlay();
		final int width = compact ? PANEL_WIDTH_COMPACT : PANEL_WIDTH;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.setBackgroundColor(OSRS_BG);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Active")
			.color(OSRS_GOLD)
			.build());

		final int level = client.getRealSkillLevel(active);

		// Pet odds at the current level, sitting directly under the skill (the fuller
		// pet breakdown — dry streak, chance seen, obtained — lives in the side panel).
		final ResourceData.SkillData skillData = skillTracker.getResourceData().getSkillData(active);
		final ResourceData.ResourceEntry resource = contextResource(active);
		final SkillTracker.SourceState source = contextSource(active);
		final SkillTracker.SkillState state = skillTracker.getSkillState(active);
		final int petBase = activePetBase(skillData, resource);
		final long actions = activeActions(state, source);
		final long xpGained = activeXpGained(state, source);
		final long failed = source == null ? 0L : source.getFailedActions();
		final int roguePieces = isPickpocketContext(active, source) ? skillTracker.getRogueOutfitPieces() : 0;

		for (String label : activeSkillingLabels(active, activityLabel(active), skillTracker.getXpHr(active),
			xpGained, actions, failed, petBase, level, roguePieces))
		{
			final int split = label.indexOf('|');
			if (split <= 0)
			{
				continue;
			}
			panelComponent.getChildren().add(LineComponent.builder()
				.left(label.substring(0, split))
				.right(label.substring(split + 1))
				.rightColor(ACCENT)
				.build());
		}

		return super.render(graphics);
	}

	private void clearSourceTarget()
	{
		this.currentSourceSkill = null;
		this.currentSourceAction = null;
		this.currentSourceName = null;
	}

	private void clearResourceTarget()
	{
		this.currentResourceSkill = null;
		this.currentResourceName = null;
		this.currentResourceEntry = null;
	}

	private String activityLabel(Skill skill)
	{
		if (currentSourceSkill == skill && currentSourceName != null)
		{
			return activeActionLabel(skill, currentSourceAction) + ": " + currentSourceName;
		}
		if (currentResourceSkill == skill && currentResourceName != null)
		{
			return capitalize(skill.getName()) + ": " + currentResourceName;
		}
		return capitalize(skill.getName());
	}

	private SkillTracker.SourceState contextSource(Skill skill)
	{
		if (skill == null || currentSourceSkill != skill || currentSourceName == null)
		{
			return latestSource(skill);
		}
		final SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		return state == null ? null : state.getSourceStates().get(currentSourceName);
	}

	private SkillTracker.SourceState latestSource(Skill skill)
	{
		final SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		if (state == null)
		{
			return null;
		}
		SkillTracker.SourceState latest = null;
		for (SkillTracker.SourceState source : state.getSourceStates().values())
		{
			if (latest == null || source.getLastSeen() > latest.getLastSeen())
			{
				latest = source;
			}
		}
		return latest;
	}

	private ResourceData.ResourceEntry contextResource(Skill skill)
	{
		return currentResourceSkill == skill ? currentResourceEntry : null;
	}

	private long activeActions(SkillTracker.SkillState state, SkillTracker.SourceState source)
	{
		if (source != null)
		{
			return source.getAttemptedActions() > 0L ? source.getAttemptedActions() : sourceObservedActions(source);
		}
		if (state == null)
		{
			return 0L;
		}
		return state.getSuccessfulActions() > 0L ? state.getSuccessfulActions() : skillObservedActions(state);
	}

	private static long activeXpGained(SkillTracker.SkillState state, SkillTracker.SourceState source)
	{
		if (source != null && source.getXpGained() > 0L)
		{
			return source.getXpGained();
		}
		return state == null ? 0L : state.getXpGained();
	}

	private static long sourceObservedActions(SkillTracker.SourceState source)
	{
		long actions = 0L;
		for (long qty : source.getGrossResourceCounts().values())
		{
			actions = Math.max(actions, qty);
		}
		return actions;
	}

	private static long skillObservedActions(SkillTracker.SkillState state)
	{
		long actions = 0L;
		for (long qty : state.getGrossResourceCounts().values())
		{
			actions = Math.max(actions, qty);
		}
		return actions;
	}

	private static int activePetBase(ResourceData.SkillData skillData, ResourceData.ResourceEntry resource)
	{
		if (skillData == null)
		{
			return 0;
		}
		return resource == null
			? skillData.getPetBaseChance()
			: resource.getEffectivePetBaseChance(skillData.getPetBaseChance());
	}

	private static boolean isPickpocketContext(Skill skill, SkillTracker.SourceState source)
	{
		return skill == Skill.THIEVING && source != null && "Pickpocket".equals(source.getActivityType());
	}

	static List<String> activeSkillingLabels(Skill skill, String activityLabel, int xpHr, long xpGained,
		long actions, long failedActions, int petBaseChance, int level, int roguePieces)
	{
		if (skill == null)
		{
			return Collections.emptyList();
		}
		final List<String> labels = new ArrayList<>();
		labels.add(activityLabel + "|");
		if (xpHr > 0)
		{
			labels.add("XP/hour|" + compactNumber(xpHr));
		}
		if (xpGained > 0L)
		{
			labels.add("XP gained|" + formatInt(xpGained));
		}
		if (actions > 0L)
		{
			labels.add("Actions|" + formatInt(actions));
		}
		if (failedActions > 0L && actions > 0L)
		{
			labels.add("Fail rate|" + Math.round((failedActions * 100.0) / actions) + "%");
		}
		if (petBaseChance > 0 && level > 0)
		{
			labels.add(petChanceLabel(skill) + "|" + PetDisplay.oddsText(petBaseChance, level));
		}
		if (skill == Skill.THIEVING && roguePieces > 0)
		{
			labels.add("Rogue outfit|" + roguePieces + "/5, "
				+ SkillTracker.rogueOutfitActivationChancePercent(roguePieces) + "%");
		}
		return Collections.unmodifiableList(labels);
	}

	private static String activeActionLabel(Skill skill, String action)
	{
		if (skill == Skill.THIEVING && "Pickpocket".equalsIgnoreCase(normalizeAction(action)))
		{
			return "Pickpocketing";
		}
		return capitalize(skill.getName());
	}

	private static String normalizeAction(String action)
	{
		return action == null ? "" : action.trim().replace(' ', '-');
	}

	private static String petChanceLabel(Skill skill)
	{
		if (skill == Skill.THIEVING)
		{
			return "Rocky chance";
		}
		if (skill == Skill.WOODCUTTING)
		{
			return "Beaver chance";
		}
		if (skill == Skill.FISHING)
		{
			return "Heron chance";
		}
		return "Pet chance";
	}

	private static String compactNumber(long value)
	{
		if (Math.abs(value) >= 1_000_000L)
		{
			return String.format("%.1fM", value / 1_000_000.0).replace(".0M", "M");
		}
		if (Math.abs(value) >= 1_000L)
		{
			return String.format("%.1fk", value / 1_000.0).replace(".0k", "k");
		}
		return formatInt(value);
	}

	private static String formatInt(long value)
	{
		return String.format("%,d", value);
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}
}
