package com.systeminterface.services.state;

import java.util.Objects;
import javax.inject.Singleton;
import net.runelite.api.Skill;

/**
 * Pure, {@code client}-free state machine deciding which side-panel sections/columns are expanded
 * and where the section-level tracking table / portrait show, based on in-game activity. Sections
 * change only on an activity switch (mutual exclusion) or a manual toggle/pin — there is no
 * idle/time-based retraction. The {@code nowMs} parameters are retained on the interaction methods
 * for call-site stability but are otherwise unused. The Swing sections render the emitted
 * {@link Snapshot}.
 */
@Singleton
public final class ActivityFocus
{
	public enum Mode { NONE, COMBAT, SKILLING }

	/** Immutable view state; {@link #equals} lets the coordinator skip no-op rebuilds. */
	public static final class Snapshot
	{
		public final Mode activeMode;
		public final boolean combatSectionExpanded;
		public final boolean skillingSectionExpanded;
		public final String combatContextTarget;
		public final String autoExpandedCombatSource;
		public final Skill autoExpandedSkill;
		public final Skill skillingTrackingSkill;

		Snapshot(Mode activeMode, boolean combatSectionExpanded, boolean skillingSectionExpanded,
			String combatContextTarget, String autoExpandedCombatSource, Skill autoExpandedSkill,
			Skill skillingTrackingSkill)
		{
			this.activeMode = activeMode;
			this.combatSectionExpanded = combatSectionExpanded;
			this.skillingSectionExpanded = skillingSectionExpanded;
			this.combatContextTarget = combatContextTarget;
			this.autoExpandedCombatSource = autoExpandedCombatSource;
			this.autoExpandedSkill = autoExpandedSkill;
			this.skillingTrackingSkill = skillingTrackingSkill;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o) return true;
			if (!(o instanceof Snapshot)) return false;
			Snapshot s = (Snapshot) o;
			return combatSectionExpanded == s.combatSectionExpanded
				&& skillingSectionExpanded == s.skillingSectionExpanded
				&& activeMode == s.activeMode
				&& Objects.equals(combatContextTarget, s.combatContextTarget)
				&& Objects.equals(autoExpandedCombatSource, s.autoExpandedCombatSource)
				&& autoExpandedSkill == s.autoExpandedSkill
				&& skillingTrackingSkill == s.skillingTrackingSkill;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(activeMode, combatSectionExpanded, skillingSectionExpanded,
				combatContextTarget, autoExpandedCombatSource, autoExpandedSkill, skillingTrackingSkill);
		}
	}

	private Mode mode = Mode.NONE;

	private String combatContext;
	private Skill skillContext;

	private Boolean combatManual;
	private Boolean skillManual;

	public ActivityFocus()
	{
	}

	public void combatInteraction(String target, long nowMs)
	{
		mode = Mode.COMBAT;
		combatContext = target;
		skillContext = null;
	}

	public void combatSelect(String target, long nowMs)
	{
		combatContext = target;
		combatManual = null;
	}

	public void skillingInteraction(Skill skill, long nowMs)
	{
		mode = Mode.SKILLING;
		skillContext = skill;
		combatContext = null;
	}

	public void manualSectionToggle(Mode section)
	{
		if (section == Mode.COMBAT)
		{
			boolean currentlyExpanded = combatManual != null ? combatManual : currentCombatExpanded();
			combatManual = !currentlyExpanded;
		}
		else if (section == Mode.SKILLING)
		{
			boolean currentlyExpanded = skillManual != null ? skillManual : currentSkillExpanded();
			skillManual = !currentlyExpanded;
		}
	}

	/**
	 * Pins {@code section}'s manual override to open. Called when the user engages content
	 * inside a section (e.g. clicks a source row to browse it) — that engagement should keep
	 * the section open through subsequent interactions/mutual-exclusion, not just a header click.
	 */
	public void pinSectionOpen(Mode section)
	{
		if (section == Mode.COMBAT)
		{
			combatManual = true;
		}
		else if (section == Mode.SKILLING)
		{
			skillManual = true;
		}
	}

	public Snapshot snapshot()
	{
		boolean combatSection = combatManual != null ? combatManual : currentCombatExpanded();
		boolean skillSection = skillManual != null ? skillManual : currentSkillExpanded();

		// Contexts persist until superseded by a new interaction/select or cleared by the other
		// activity's interaction (mutual exclusion) — there is no idle/time-based retraction.
		return new Snapshot(mode, combatSection, skillSection, combatContext, combatContext, skillContext, skillContext);
	}

	private boolean currentCombatExpanded()
	{
		return combatContext != null || mode == Mode.COMBAT;
	}

	private boolean currentSkillExpanded()
	{
		return skillContext != null;
	}
}
