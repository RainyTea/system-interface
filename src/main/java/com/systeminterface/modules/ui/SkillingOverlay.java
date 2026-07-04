package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.modules.skills.PetDisplay;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
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

	@Inject
	public SkillingOverlay(Client client, SkillTracker skillTracker, SystemInterfaceConfig config)
	{
		this.client = client;
		this.skillTracker = skillTracker;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_LEFT);
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
			.text("Skill Tracker")
			.color(OSRS_GOLD)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Skill")
			.right(capitalize(active.getName()))
			.rightColor(ACCENT)
			.build());

		final int level = client.getRealSkillLevel(active);

		// Pet odds at the current level, sitting directly under the skill (the fuller
		// pet breakdown — dry streak, chance seen, obtained — lives in the side panel).
		final ResourceData.SkillData skillData = skillTracker.getResourceData().getSkillData(active);
		if (skillData != null && level > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Pet odds")
				.right(PetDisplay.oddsText(skillData.getPetBaseChance(), level))
				.rightColor(ACCENT)
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Level")
			.right(String.valueOf(level))
			.build());

		// XP/hr and actions/hr from RuneLite's XP Tracker
		final int xpHr = skillTracker.getXpHr(active);
		if (xpHr > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(compact ? "XP/hr" : "XP / hour")
				.right(String.format("%,d", xpHr))
				.build());
		}

		final int actionsHr = skillTracker.getActionsHr(active);
		if (actionsHr > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(compact ? "Res/hr" : "Resources / hour")
				.right(String.format("%,d", actionsHr))
				.build());
		}

		// Resources gathered this session
		final int sessionActions = skillTracker.getActions(active);
		if (sessionActions > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(compact ? "Session" : "Session resources")
				.right(String.format("%,d", sessionActions))
				.build());
		}

		// Estimated time to the next level, directly under session (hidden when the
		// rate isn't known yet).
		final String timeToNext = skillTracker.getTimeToNextLevel(active);
		if (timeToNext != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(compact ? "Next lvl" : "Time to next lvl")
				.right(timeToNext)
				.rightColor(DIM)
				.build());
		}

		return super.render(graphics);
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
