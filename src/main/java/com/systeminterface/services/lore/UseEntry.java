package com.systeminterface.services.lore;

/** One recipe this item is an ingredient of — the compact "Use" line's data. */
public final class UseEntry
{
	private final String outputName;
	private final String facility;
	private final String skill;
	private final Integer level;
	private final Integer xp;

	public UseEntry(String outputName, String facility, String skill, Integer level, Integer xp)
	{
		this.outputName = outputName;
		this.facility = facility;
		this.skill = skill;
		this.level = level;
		this.xp = xp;
	}

	public String getOutputName() { return outputName; }
	/** Facility/tool context (e.g. "Bone grinder"), or null. */
	public String getFacility() { return facility; }
	/** Skill name when the recipe trains one, or null. */
	public String getSkill() { return skill; }
	public Integer getLevel() { return level; }
	public Integer getXp() { return xp; }
}
