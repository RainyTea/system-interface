package com.systeminterface.services.lore;

import java.util.Collections;
import java.util.List;

/** Wiki lore for a (typically non-combat) NPC — the Appraise lore card's data. */
public final class NpcLore
{
	private final String examine;
	private final String location;
	private final List<String> quests;
	private final String imageFile;

	public NpcLore(String examine, String location, List<String> quests, String imageFile)
	{
		this.examine = examine;
		this.location = location;
		this.quests = quests == null ? Collections.emptyList() : Collections.unmodifiableList(quests);
		this.imageFile = imageFile;
	}

	public String getExamine() { return examine; }
	public String getLocation() { return location; }
	/** Quest involvement, cleaned of wikitext. Never null; empty when none. */
	public List<String> getQuests() { return quests; }
	/** Wiki image file name without the {@code File:} prefix, or null. */
	public String getImageFile() { return imageFile; }
}
