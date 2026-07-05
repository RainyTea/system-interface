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
import static org.junit.Assert.assertNull;
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
	public void baitAndLureFish_carryRequiredSecondaries()
	{
		assertTrue(data.forItemId(335).getSecondaries().contains(314));      // trout -> feathers
		assertTrue(data.forItemId(349).getSecondaries().contains(313));      // pike -> fishing bait
		assertTrue(data.forItemId(13439).getSecondaries().contains(13431));  // anglerfish -> sandworms
		assertTrue(data.forItemId(317).getSecondaries().isEmpty());          // shrimps (net) -> none
	}

	@Test
	public void forNpcIdAndMethodFiltersMultiMethodSpotByMethod()
	{
		// NPC 1510: tuna+swordfish (harpoon) + lobster (cage).
		Set<String> harpoonNames = names(data.forNpcIdAndMethod(1510, "harpoon"));
		assertTrue(harpoonNames.contains("Raw tuna"));
		assertTrue(harpoonNames.contains("Raw swordfish"));
		assertFalse(harpoonNames.contains("Raw lobster"));   // cage fish excluded

		Set<String> cageNames = names(data.forNpcIdAndMethod(1510, "cage"));
		assertTrue(cageNames.contains("Raw lobster"));
		assertFalse(cageNames.contains("Raw tuna"));
	}

	@Test
	public void forNpcIdAndMethodNullMethodReturnsAll()
	{
		assertEquals(data.forNpcId(1510).size(), data.forNpcIdAndMethod(1510, null).size());
	}

	@Test
	public void forNpcIdAndMethodUnknownMethodFallsBackToAll()
	{
		// No entry at 1510 uses "net" -> fallback shows all rather than empty.
		assertEquals(data.forNpcId(1510).size(), data.forNpcIdAndMethod(1510, "net").size());
	}

	@Test
	public void resolveFishingMethodIsSpotAwareForNetFamily()
	{
		// 1514 is a small-net/bait spot; 1511 is a big-net/harpoon spot. Same option word,
		// different method depending on which the spot actually offers.
		assertEquals("net", data.resolveFishingMethod(1514, "Net"));
		assertEquals("bignet", data.resolveFishingMethod(1511, "Net"));
		assertEquals("bignet", data.resolveFishingMethod(1511, "Big Net"));
	}

	@Test
	public void resolveFishingMethodHandlesUnambiguousOptions()
	{
		assertEquals("harpoon", data.resolveFishingMethod(1510, "Harpoon"));
		assertEquals("cage", data.resolveFishingMethod(1510, "Cage"));
		assertEquals("harpoon", data.resolveFishingMethod(1511, "Harpoon"));
		assertEquals("bait", data.resolveFishingMethod(1514, "Bait"));
		assertEquals("lure", data.resolveFishingMethod(1506, "Lure"));
		assertEquals("vessel", data.resolveFishingMethod(4712, "Fish"));
		assertEquals("harpoon", data.resolveFishingMethod(1510, "harpoon")); // case-insensitive
	}

	@Test
	public void resolveFishingMethodNullWhenOptionNotOfferedOrUnknown()
	{
		assertNull(data.resolveFishingMethod(1510, "Net"));   // 1510 offers no net/bignet
		assertNull(data.resolveFishingMethod(1510, "Examine"));
		assertNull(data.resolveFishingMethod(999999, "Harpoon")); // not a fishing spot
		assertNull(data.resolveFishingMethod(1510, null));
	}

	@Test
	public void isTrackableMethodExcludesOnlyBignet()
	{
		assertFalse(ResourceData.isTrackableMethod("bignet"));
		assertTrue(ResourceData.isTrackableMethod("harpoon"));
		assertTrue(ResourceData.isTrackableMethod("net"));
		assertTrue(ResourceData.isTrackableMethod("cage"));
		assertTrue(ResourceData.isTrackableMethod(null));
	}

	@Test
	public void woodcuttingHasBirdNestSecondaryAndForestryConditional()
	{
		List<ResourceData.RewardEntry> rewards = data.getRewards(Skill.WOODCUTTING);
		Set<String> names = new HashSet<>();
		for (ResourceData.RewardEntry r : rewards) { names.add(r.getName()); }
		assertTrue(names.contains("Bird nest"));
		assertTrue(names.contains("Leaves"));
	}

	@Test
	public void birdNestIsSkillWideSecondaryWithRate()
	{
		ResourceData.RewardEntry nest = data.rewardForItemId(5073);
		assertNotNull(nest);
		assertEquals(ResourceData.RewardType.SECONDARY, nest.getType());
		assertEquals(Skill.WOODCUTTING, nest.getSkill());
		assertNotNull(nest.getRate());
		assertTrue(nest.getRequiredItemIds().isEmpty());
	}

	@Test
	public void leavesIsConditionalGatedOnForestryKit()
	{
		ResourceData.RewardEntry leaves = data.rewardForItemId(6020);
		assertNotNull(leaves);
		assertEquals(ResourceData.RewardType.CONDITIONAL, leaves.getType());
		assertTrue(leaves.getRequiredItemIds().contains(28136)); // Forestry kit
	}

	@Test
	public void applicableRewards_secondaryAlways_conditionalOnlyWhenHeld()
	{
		Set<Integer> none = java.util.Collections.emptySet();
		Set<String> withoutKit = new HashSet<>();
		for (ResourceData.RewardEntry r : data.getApplicableRewards(Skill.WOODCUTTING, none)) { withoutKit.add(r.getName()); }
		assertTrue(withoutKit.contains("Bird nest"));   // secondary always
		assertFalse(withoutKit.contains("Leaves"));     // conditional gated out

		Set<Integer> withKit = new HashSet<>(java.util.Arrays.asList(28136));
		Set<String> withKitNames = new HashSet<>();
		for (ResourceData.RewardEntry r : data.getApplicableRewards(Skill.WOODCUTTING, withKit)) { withKitNames.add(r.getName()); }
		assertTrue(withKitNames.contains("Bird nest"));
		assertTrue(withKitNames.contains("Leaves"));     // conditional now applies
	}

	@Test
	public void getRewards_unknownSkill_isEmptyNotNull()
	{
		assertTrue(data.getRewards(Skill.AGILITY).isEmpty());
		assertNull(data.rewardForItemId(999_999));
	}

	@Test
	public void loadsNestedProvenanceSchema()
	{
		// A resource in the nested {mainAction, extraRolls} form flattens to the same ResourceEntry.
		String json = "{\"woodcutting\":{\"petBaseChance\":317647,\"resources\":[{"
			+ "\"mainAction\":{\"name\":\"Yew logs\",\"itemId\":1515,\"levelRequired\":60,"
			+ "\"xpPerAction\":175.0,\"objectIds\":[10822],\"npcIds\":[],\"method\":null,\"secondaries\":[]},"
			+ "\"extraRolls\":{\"uses\":[\"Fletching:65\"],\"petBaseChanceOverride\":null,\"rate\":null}"
			+ "}]}}";
		ResourceData nested = loadJson(json);
		ResourceData.ResourceEntry yew = nested.forItemId(1515);
		assertNotNull(yew);
		assertEquals("Yew logs", yew.getName());
		assertEquals(60, yew.getLevelRequired());
		assertEquals(175.0, yew.getXpPerAction(), 1e-9);
		assertTrue(yew.getObjectIds().contains(10822));
		assertEquals("Fletching:65", yew.getUses().get(0));
		assertEquals(Skill.WOODCUTTING, yew.getSkill());
	}

	private static ResourceData loadJson(String json)
	{
		return ResourceData.load(new java.io.StringReader(json), new com.google.gson.Gson());
	}
}
