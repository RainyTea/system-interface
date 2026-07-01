package com.systeminterface.modules.skills;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the Phase 4 schema generalization: node lookups now return a <em>list</em> of
 * resources. Trees/rocks resolve to a 1-element list (no behaviour change); a fishing spot
 * NPC id resolves to several fish. Loads the real bundled {@code ResourceData.json}.
 */
public class ResourceDataTest
{
	private ResourceData data;

	@Before
	public void setUp()
	{
		data = ResourceData.load(new Gson());
	}

	private Set<String> names(List<ResourceData.ResourceEntry> entries)
	{
		Set<String> n = new HashSet<>();
		for (ResourceData.ResourceEntry e : entries)
		{
			n.add(e.getName());
		}
		return n;
	}

	@Test
	public void woodcuttingObject_resolvesToSingleElementList()
	{
		List<ResourceData.ResourceEntry> oak = data.forObjectId(10820); // oak tree
		assertEquals(1, oak.size());
		assertEquals("Oak logs", oak.get(0).getName());
		assertEquals(Skill.WOODCUTTING, oak.get(0).getSkill());
	}

	@Test
	public void miningObject_resolvesToSingleElementList()
	{
		List<ResourceData.ResourceEntry> copper = data.forObjectId(10943); // copper rock
		assertEquals(1, copper.size());
		assertEquals("Copper ore", copper.get(0).getName());
		assertEquals(Skill.MINING, copper.get(0).getSkill());
	}

	@Test
	public void resourceWikiPages_areExplicitAndAvoidGenericTreeFallbacks()
	{
		assertEquals(null, data.forObjectId(1276).get(0).getWikiPage());
		assertEquals("Oak tree", data.forObjectId(10820).get(0).getWikiPage());
		assertEquals("Copper rocks", data.forObjectId(10943).get(0).getWikiPage());
	}

	@Test
	public void fishingSpotNpc_resolvesToAllFishItOffers()
	{
		// 1514 is a net/bait (SALTFISH) spot — shrimp, sardine, herring, anchovies.
		List<ResourceData.ResourceEntry> fishes = data.forNpcId(1514);
		assertEquals(4, fishes.size());
		Set<String> n = names(fishes);
		assertTrue(n.contains("Raw shrimps"));
		assertTrue(n.contains("Raw sardine"));
		assertTrue(n.contains("Raw herring"));
		assertTrue(n.contains("Raw anchovies"));
		for (ResourceData.ResourceEntry e : fishes)
		{
			assertEquals(Skill.FISHING, e.getSkill());
		}
	}

	@Test
	public void multipleResources_canShareTheSameNodeId()
	{
		// The crux of the schema change: one node id -> many resources.
		assertTrue(data.forNpcId(1514).size() > 1);
	}

	@Test
	public void cageHarpoonSpot_offersTunaLobsterSwordfish()
	{
		List<ResourceData.ResourceEntry> fishes = data.forNpcId(1510); // RAREFISH spot
		Set<String> n = names(fishes);
		assertTrue(n.contains("Raw tuna"));
		assertTrue(n.contains("Raw lobster"));
		assertTrue(n.contains("Raw swordfish"));
	}

	@Test
	public void unknownNode_returnsEmptyListNotNull()
	{
		assertTrue(data.forObjectId(999_999).isEmpty());
		assertTrue(data.forNpcId(999_999).isEmpty());
	}

	@Test
	public void forItemId_stillResolvesASingleResource()
	{
		ResourceData.ResourceEntry shrimp = data.forItemId(317); // raw shrimps
		assertNotNull(shrimp);
		assertEquals("Raw shrimps", shrimp.getName());
		assertEquals(Skill.FISHING, shrimp.getSkill());
	}

	@Test
	public void treesAndRocks_haveNoNpcIds_fishHaveNoObjectIds()
	{
		assertTrue(data.forObjectId(10820).get(0).getNpcIds().isEmpty()); // oak
		assertTrue(data.forItemId(317).getObjectIds().isEmpty());         // shrimp
		assertTrue(data.forItemId(317).getNpcIds().contains(1514));
	}

	@Test
	public void fishCarryTheirFishingMethod()
	{
		assertEquals("net", data.forItemId(317).getMethod());   // raw shrimps
		assertEquals("lure", data.forItemId(335).getMethod());  // raw trout (fly)
		assertEquals("bait", data.forItemId(349).getMethod());  // raw pike (bait)
		assertEquals("cage", data.forItemId(377).getMethod());  // raw lobster
		assertEquals("harpoon", data.forItemId(371).getMethod()); // raw swordfish
	}

	@Test
	public void nonFishingResources_haveNoMethod()
	{
		assertEquals(null, data.forItemId(1521).getMethod()); // oak logs
	}

	@Test
	public void woodcuttingBirdNest_hasTrackableRate()
	{
		ResourceData.ResourceEntry nest = data.forItemId(22798, Skill.WOODCUTTING);

		assertNotNull(nest);
		assertEquals("Bird nest", nest.getName());
		assertEquals(1.0 / 256.0, nest.getRate(), 0.0000001);
		assertTrue(data.allResourceItemIds(Skill.WOODCUTTING).contains(5070));
	}

	@Test
	public void woodcuttingBirdNest_aliasesNormalNestTypesUnderSingleRate()
	{
		int[] normalNestIds = {22798, 5073, 5074, 5070, 5071, 5072};
		for (int itemId : normalNestIds)
		{
			ResourceData.ResourceEntry nest = data.forItemId(itemId, Skill.WOODCUTTING);
			assertNotNull("itemId=" + itemId, nest);
			assertEquals("itemId=" + itemId, "Bird nest", nest.getName());
			assertEquals("itemId=" + itemId, 1.0 / 256.0, nest.getRate(), 0.0000001);
		}
	}

	@Test
	public void woodcuttingClueNestsResolveWithoutFakeStaticRate()
	{
		assertEquals("Clue nest", data.forItemId(23127, Skill.WOODCUTTING).getName()); // beginner
		assertEquals("Clue nest", data.forItemId(19712, Skill.WOODCUTTING).getName()); // easy
		assertEquals("Clue nest", data.forItemId(19714, Skill.WOODCUTTING).getName()); // medium
		assertEquals("Clue nest", data.forItemId(19716, Skill.WOODCUTTING).getName()); // hard
		assertEquals("Clue nest", data.forItemId(19718, Skill.WOODCUTTING).getName()); // elite
		assertEquals(null, data.forItemId(23127, Skill.WOODCUTTING).getRate());
	}

	@Test
	public void woodcuttingClueNestAliases_resolveSeparatelyFromNormalNestRate()
	{
		assertEquals("Clue nest", data.forItemId(23127, Skill.WOODCUTTING).getName()); // beginner
		assertEquals("Clue nest", data.forItemId(19712, Skill.WOODCUTTING).getName()); // easy
		assertEquals("Clue nest", data.forItemId(19714, Skill.WOODCUTTING).getName()); // medium
		assertEquals("Clue nest", data.forItemId(19716, Skill.WOODCUTTING).getName()); // hard
		assertEquals("Clue nest", data.forItemId(19718, Skill.WOODCUTTING).getName()); // elite
	}

	@Test
	public void woodcuttingTreeObjectAliases_resolveWikiTreeIds()
	{
		assertEquals("Oak logs", data.forObjectId(10820).get(0).getName());
		assertEquals("Oak logs", data.forObjectId(42395).get(0).getName());
		assertEquals("Oak logs", data.forObjectId(51772).get(0).getName());
		assertEquals("Logs", data.forObjectId(1279).get(0).getName());
		assertEquals("Logs", data.forObjectId(52823).get(0).getName());
		assertEquals("Logs", data.forObjectId(58540).get(0).getName());
		assertEquals("Maple logs", data.forObjectId(36681).get(0).getName());
		assertEquals("Maple logs", data.forObjectId(40754).get(0).getName());
		assertEquals("Yew logs", data.forObjectId(42391).get(0).getName());
		assertEquals("Yew logs", data.forObjectId(57790).get(0).getName());
		assertEquals("Teak logs", data.forObjectId(40758).get(0).getName());
		assertEquals("Mahogany logs", data.forObjectId(40760).get(0).getName());
		assertEquals("Arctic pine logs", data.forObjectId(3037).get(0).getName());
		assertEquals("Redwood logs", data.forObjectId(29669).get(0).getName());
		assertEquals("Redwood logs", data.forObjectId(29671).get(0).getName());
		assertEquals("Redwood logs", data.forObjectId(29681).get(0).getName());
		assertEquals("Redwood logs", data.forObjectId(29682).get(0).getName());
		assertEquals("Sulliuscep cap", data.forObjectId(30602).get(0).getName());
		assertEquals("Sulliuscep cap", data.forObjectId(30603).get(0).getName());
	}

	@Test
	public void woodcuttingTreeTargets_resolveWhenObjectIdIsMissing()
	{
		assertEquals("Logs", data.forObjectName("Tree").get(0).getName());
		assertEquals("Oak logs", data.forObjectName("Oak tree").get(0).getName());
		assertEquals("Willow logs", data.forObjectName("Willow tree").get(0).getName());
		assertEquals("Maple logs", data.forObjectName("Maple tree").get(0).getName());
		assertEquals("Yew logs", data.forObjectName("Yew tree").get(0).getName());
		assertEquals("Magic logs", data.forObjectName("Magic tree").get(0).getName());
		assertEquals("Redwood logs", data.forObjectName("Redwood tree").get(0).getName());
		assertEquals("Sulliuscep cap", data.forObjectName("Sulliuscep").get(0).getName());

		assertFalse(names(data.forObjectName("Oak tree")).contains("Oak leaves"));
	}

	@Test
	public void woodcuttingLeafBearingTrees_includeConditionalForestryLeaves()
	{
		ResourceData.ResourceEntry oak = data.forObjectId(10820).get(0);
		Set<String> oakRewards = names(data.statisticalRewardsForResource(oak));

		assertTrue(oakRewards.contains("Bird nest"));
		assertTrue(oakRewards.contains("Oak leaves"));
		assertFalse(data.forObjectId(10820).stream().anyMatch(e -> e.getName().equals("Oak leaves")));

		assertLeafReward(6022, "Oak leaves");
		assertLeafReward(6024, "Willow leaves");
		assertLeafReward(6028, "Maple leaves");
		assertLeafReward(6026, "Yew leaves");
		assertLeafReward(6030, "Magic leaves");

		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10819).get(0))).contains("Willow leaves"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10832).get(0))).contains("Maple leaves"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10822).get(0))).contains("Yew leaves"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10834).get(0))).contains("Magic leaves"));
		assertFalse(names(data.statisticalRewardsForResource(data.forObjectId(9036).get(0))).contains("Oak leaves"));
	}

	private void assertLeafReward(int itemId, String expectedName)
	{
		ResourceData.ResourceEntry leaves = data.forItemId(itemId, Skill.WOODCUTTING);
		assertNotNull(leaves);
		assertEquals(expectedName, leaves.getName());
		assertTrue(leaves.isRewardOnly());
		assertEquals(0.25, leaves.getRate(), 0.0000001);
		assertTrue(leaves.getRequiredItemsAny().contains(28136));
		assertTrue(leaves.getRequiredItemsAny().contains(28143));
		assertTrue(leaves.getRequiredItemsAny().contains(28145));
	}

	@Test
	public void woodcuttingNormalBirdNest_isTreeScopedNotSkillWide()
	{
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10820).get(0))).contains("Bird nest"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10819).get(0))).contains("Bird nest"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(9036).get(0))).contains("Bird nest"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(3037).get(0))).contains("Bird nest"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10832).get(0))).contains("Bird nest"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(9034).get(0))).contains("Bird nest"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10822).get(0))).contains("Bird nest"));
		assertTrue(names(data.statisticalRewardsForResource(data.forObjectId(10834).get(0))).contains("Bird nest"));

		assertFalse(names(data.statisticalRewardsForResource(data.forObjectId(1276).get(0))).contains("Bird nest"));
		assertFalse(names(data.statisticalRewardsForResource(data.forObjectId(29668).get(0))).contains("Bird nest"));
	}

	@Test
	public void woodcuttingResourceContext_canExistWithoutFixedRateRewards()
	{
		ResourceData.ResourceEntry regularTree = data.forObjectId(1279).get(0);
		ResourceData.ResourceEntry redwood = data.forObjectId(29681).get(0);

		assertEquals("Logs", regularTree.getName());
		assertEquals("Redwood logs", redwood.getName());
		assertTrue(data.statisticalRewardsForResource(regularTree).isEmpty());
		assertTrue(data.statisticalRewardsForResource(redwood).isEmpty());
	}

	@Test
	public void woodcuttingPetBaseOverrides_matchWikiTreeTable()
	{
		ResourceData.SkillData woodcutting = data.getSkillData(Skill.WOODCUTTING);

		assertEquals(317647, woodcutting.getPetBaseChance());
		assertEquals(361146, data.forObjectId(10820).get(0).getEffectivePetBaseChance(woodcutting.getPetBaseChance()));
		assertEquals(289286, data.forObjectId(10819).get(0).getEffectivePetBaseChance(woodcutting.getPetBaseChance()));
		assertEquals(264336, data.forObjectId(9036).get(0).getEffectivePetBaseChance(woodcutting.getPetBaseChance()));
		assertEquals(72321, data.forObjectId(29668).get(0).getEffectivePetBaseChance(woodcutting.getPetBaseChance()));
	}

	@Test
	public void baitAndLureFish_carryRequiredSecondaries()
	{
		assertTrue(data.forItemId(335).getSecondaries().contains(314));      // trout -> feathers
		assertTrue(data.forItemId(349).getSecondaries().contains(313));      // pike -> fishing bait
		assertTrue(data.forItemId(13439).getSecondaries().contains(13431));  // anglerfish -> sandworms
		assertTrue(data.forItemId(317).getSecondaries().isEmpty());          // shrimps (net) -> none
	}

	@Test
	public void barbarianFish_carryBarbarianMethodAndBait()
	{
		assertEquals("barbarian", data.forItemId(11328).getMethod()); // leaping trout
		assertEquals("barbarian", data.forItemId(11330).getMethod()); // leaping salmon
		assertEquals("barbarian", data.forItemId(11332).getMethod()); // leaping sturgeon
		assertTrue(data.forItemId(11328).getSecondaries().contains(11334)); // fish offcuts
	}

	@Test
	public void hunterFarmingThieving_starterResourcesResolveByItem()
	{
		assertEquals(Skill.HUNTER, data.forItemId(10033).getSkill());   // chinchompa
		assertEquals(Skill.HUNTER, data.forItemId(10014).getSkill());   // black warlock jar
		assertEquals(Skill.FARMING, data.forItemId(1942).getSkill());   // potato
		assertEquals(Skill.THIEVING, data.forItemId(22521).getSkill()); // citizen coin pouch
	}

	@Test
	public void thievingCoinPouchAliases_resolveToTheSameResource()
	{
		ResourceData.ResourceEntry citizen = data.forItemId(22521);
		ResourceData.ResourceEntry wealthyCitizen = data.forItemId(28822);

		assertNotNull(wealthyCitizen);
		assertEquals(Skill.THIEVING, wealthyCitizen.getSkill());
		assertEquals(citizen.getName(), wealthyCitizen.getName());
		assertTrue(data.allResourceItemIds(Skill.THIEVING).contains(28822));
	}

	@Test
	public void wealthyCitizenLoot_resolvesAllKnownPickpocketRewards()
	{
		assertEquals(Skill.THIEVING, data.forItemId(28822, Skill.THIEVING).getSkill()); // coin pouch
		assertEquals(Skill.THIEVING, data.forItemId(29325, Skill.THIEVING).getSkill()); // house keys
		assertEquals(Skill.THIEVING, data.forItemId(2677, Skill.THIEVING).getSkill());  // clue scroll easy
		assertEquals(Skill.THIEVING, data.forItemId(24362, Skill.THIEVING).getSkill()); // scroll box easy
	}

	@Test
	public void wealthyCitizenLoot_resolvesByPickpocketSource()
	{
		List<ResourceData.ResourceEntry> rewards =
			data.forSourceAction(Skill.THIEVING, "Wealthy citizen", "Pickpocket");
		Set<String> n = names(rewards);

		assertTrue(n.contains("Coin pouch"));
		assertTrue(n.contains("House keys"));
		assertTrue(n.contains("Clue scroll (easy)"));
		assertEquals(Skill.THIEVING, rewards.get(0).getSkill());
		assertEquals(1.0 / 85.0, data.forItemId(2677, Skill.THIEVING).getRate(), 0.0001);
	}

	@Test
	public void thievingStalls_resolveByStealFromSource()
	{
		Set<String> bakery = names(data.forSourceAction(Skill.THIEVING, "Baker's stall", "Steal-from"));
		assertTrue(bakery.contains("Bread"));
		assertTrue(bakery.contains("Cake"));
		assertTrue(bakery.contains("Chocolate slice"));

		Set<String> tea = names(data.forSourceAction(Skill.THIEVING, "Tea stall", "Steal-from"));
		assertTrue(tea.contains("Cup of tea"));

		Set<String> fruit = names(data.forSourceAction(Skill.THIEVING, "Fruit stall", "Steal-from"));
		assertTrue(fruit.contains("Cooking apple"));
		assertTrue(fruit.contains("Golovanova fruit top"));

		Set<String> fish = names(data.forSourceAction(Skill.THIEVING, "Fish stall", "Steal from"));
		assertTrue(fish.contains("Raw salmon"));
		assertTrue(fish.contains("Raw tuna"));
		assertTrue(fish.contains("Raw lobster"));

		Set<String> crossbow = names(data.forSourceAction(Skill.THIEVING, "Crossbow stall", "Steal-from"));
		assertTrue(crossbow.contains("Bronze bolts"));
		assertTrue(crossbow.contains("Mithril limbs"));

		Set<String> silver = names(data.forSourceAction(Skill.THIEVING, "Silver stall", "Steal-from"));
		assertTrue(silver.contains("Silver ore"));
		assertTrue(silver.contains("Silver bar"));
		assertTrue(silver.contains("Tiara"));

		Set<String> spice = names(data.forSourceAction(Skill.THIEVING, "Spice stall", "Steal-from"));
		assertTrue(spice.contains("Spice"));

		Set<String> magic = names(data.forSourceAction(Skill.THIEVING, "Magic stall", "Steal-from"));
		assertTrue(magic.contains("Air rune"));
		assertTrue(magic.contains("Law rune"));

		Set<String> scimitar = names(data.forSourceAction(Skill.THIEVING, "Scimitar stall", "Steal-from"));
		assertTrue(scimitar.contains("Iron scimitar"));
		assertTrue(scimitar.contains("Adamant scimitar"));

		Set<String> gem = names(data.forSourceAction(Skill.THIEVING, "Gem stall", "Steal-from"));
		assertTrue(gem.contains("Uncut sapphire"));
		assertTrue(gem.contains("Uncut diamond"));

		Set<String> ore = names(data.forSourceAction(Skill.THIEVING, "Ore stall", "Steal-from"));
		assertTrue(ore.contains("Coal"));
		assertTrue(ore.contains("Runite ore"));

		Set<String> cannonball = names(data.forSourceAction(Skill.THIEVING, "Cannonball stall", "Steal-from"));
		assertTrue(cannonball.contains("Bronze cannonball"));
		assertTrue(cannonball.contains("Dragon cannonball"));
	}

	@Test
	public void thievingStallRates_matchWikiStarterTables()
	{
		assertEquals(14.0 / 20.0, data.forItemId(331, Skill.THIEVING).getRate(), 0.0001);
		assertEquals(5.0 / 20.0, data.forItemId(359, Skill.THIEVING).getRate(), 0.0001);
		assertEquals(1.0 / 20.0, data.forItemId(377, Skill.THIEVING).getRate(), 0.0001);

		assertEquals(4.0 / 5.0, data.forItemId(442, Skill.THIEVING).getRate(), 0.0001);
		assertEquals(3.0 / 20.0, data.forItemId(2355, Skill.THIEVING).getRate(), 0.0001);
		assertEquals(1.0 / 20.0, data.forItemId(5525, Skill.THIEVING).getRate(), 0.0001);

		assertEquals(105.0 / 128.0, data.forItemId(1623, Skill.THIEVING).getRate(), 0.0001);
		assertEquals(17.0 / 128.0, data.forItemId(1621, Skill.THIEVING).getRate(), 0.0001);
		assertEquals(5.0 / 128.0, data.forItemId(1619, Skill.THIEVING).getRate(), 0.0001);
		assertEquals(1.0 / 128.0, data.forItemId(1617, Skill.THIEVING).getRate(), 0.0001);
	}

	@Test
	public void sourceScopedRewards_doNotPolluteSkillWideStatisticalTables()
	{
		Set<String> thievingWide = names(data.skillWideStatisticalRewards(Skill.THIEVING));
		assertFalse(thievingWide.contains("Clue scroll (easy)"));
		assertFalse(thievingWide.contains("Raw lobster"));

		Set<String> woodcuttingWide = names(data.skillWideStatisticalRewards(Skill.WOODCUTTING));
		assertFalse(woodcuttingWide.contains("Bird nest"));
		assertFalse(woodcuttingWide.contains("Oak leaves"));
		assertFalse(woodcuttingWide.contains("Clue nest"));
	}

	@Test
	public void actionSourceLookup_distinguishesWrongAction()
	{
		assertTrue(data.forSourceAction(Skill.THIEVING, "Wealthy citizen", "Attack").isEmpty());
		assertTrue(data.forSourceAction(Skill.THIEVING, "Man", "Pickpocket").stream()
			.anyMatch(e -> e.getName().equals("Coin pouch")));
	}

	@Test
	public void sharedItemId_canResolveForTheMatchingSkill()
	{
		List<ResourceData.ResourceEntry> silverOre = data.forItemIdAll(442);

		assertTrue(silverOre.size() >= 2);
		assertEquals(Skill.MINING, data.forItemId(442, Skill.MINING).getSkill());
		assertEquals(Skill.THIEVING, data.forItemId(442, Skill.THIEVING).getSkill());
		assertTrue(data.allResourceItemIds(Skill.MINING).contains(442));
		assertTrue(data.allResourceItemIds(Skill.THIEVING).contains(442));
	}

	@Test
	public void xpOnlyStarterSkills_doNotShowPetOddsYet()
	{
		assertEquals(0, data.getSkillData(Skill.HUNTER).getPetBaseChance());
		assertEquals(0, data.getSkillData(Skill.FARMING).getPetBaseChance());
		assertEquals(257211, data.getSkillData(Skill.THIEVING).getPetBaseChance());
	}
}
