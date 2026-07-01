package com.systeminterface.common.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link ItemAppraisal}, focused on the dual (context-aware) ranking
 * added in Phase 3: a wieldable skilling tool is ranked by its skill grade with a
 * secondary, weaker combat grade.
 */
public class ItemAppraisalTest
{
	private static final int WEAPON_SLOT = 3;

	/** Rune pickaxe: A-grade mining tool, but only a D-grade weapon by value. */
	@Test
	public void runePickaxe_dualRanked_skillPrimaryCombatSecondary()
	{
		ItemAppraisal a = ItemAppraisal.appraise("Rune pickaxe", 26_000, true, WEAPON_SLOT);

		assertEquals(ItemAppraisal.ItemClass.TOOL, a.getItemClass());
		assertEquals(ItemRank.A, a.getRank());            // material tier 6 → A
		assertEquals("Skill", a.getPrimaryContext());
		assertEquals(ItemRank.D, a.getSecondaryRank());   // 26k gp → D by value
		assertEquals("Combat", a.getSecondaryContext());
		assertEquals("Mining", a.getSkillUse());
	}

	/** Dragon pickaxe: S-grade tool, modest combat value. */
	@Test
	public void dragonPickaxe_dualRanked()
	{
		ItemAppraisal a = ItemAppraisal.appraise("Dragon pickaxe", 30_000, true, WEAPON_SLOT);

		assertEquals(ItemAppraisal.ItemClass.TOOL, a.getItemClass());
		assertEquals(ItemRank.S, a.getRank());            // material tier 7 → S
		assertEquals(ItemRank.D, a.getSecondaryRank());
		assertEquals("Skill", a.getPrimaryContext());
		assertEquals("Combat", a.getSecondaryContext());
	}

	/**
	 * Bronze pickaxe: tier and value both bottom out at F, so there is no meaningful
	 * second context — the dual rank is suppressed.
	 */
	@Test
	public void bronzePickaxe_noDualWhenRanksMatch()
	{
		ItemAppraisal a = ItemAppraisal.appraise("Bronze pickaxe", 50, true, WEAPON_SLOT);

		assertEquals(ItemAppraisal.ItemClass.TOOL, a.getItemClass());
		assertEquals(ItemRank.F, a.getRank());
		assertNull(a.getPrimaryContext());
		assertNull(a.getSecondaryRank());
		assertNull(a.getSecondaryContext());
	}

	/** A non-wieldable tool (chisel) has only one context: no secondary rank. */
	@Test
	public void chisel_singleContext()
	{
		ItemAppraisal a = ItemAppraisal.appraise("Chisel", 100, false, -1);

		assertEquals(ItemAppraisal.ItemClass.TOOL, a.getItemClass());
		assertNull(a.getPrimaryContext());
		assertNull(a.getSecondaryRank());
	}

	/** A real weapon is not dual-ranked — only skilling tools are. */
	@Test
	public void runeScimitar_weaponSingleContext()
	{
		ItemAppraisal a = ItemAppraisal.appraise("Rune scimitar", 15_000, true, WEAPON_SLOT);

		assertEquals(ItemAppraisal.ItemClass.WEAPON, a.getItemClass());
		assertEquals(ItemRank.A, a.getRank());            // tier 6 → A, beats value
		assertNull(a.getPrimaryContext());
		assertNull(a.getSecondaryRank());
	}

	/** Non-tool, non-weapon items keep their plain value rank and no contexts. */
	@Test
	public void plainResource_unchanged()
	{
		ItemAppraisal a = ItemAppraisal.appraise("Oak logs", 30, false, -1);

		assertNull(a.getPrimaryContext());
		assertNull(a.getSecondaryRank());
		assertEquals(ItemRank.F, a.getRank());
	}

	@Test
	public void notedItem_usesNotedClassAndDoesNotDuplicateSuffix()
	{
		ItemAppraisal a = ItemAppraisal.appraise("Oak logs (noted)", 30, false, -1, true);

		assertEquals(ItemAppraisal.ItemClass.NOTED, a.getItemClass());
		assertEquals("Noted form of Oak logs. Trade or bank to use.", a.getDescription());
	}
}
