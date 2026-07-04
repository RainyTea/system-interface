package com.systeminterface.services.lookup;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;

/**
 * EDT/render-safe snapshot of the item ids the player currently holds (inventory) or wears (worn),
 * for conditional-reward gating (spec §3). {@link Client#getItemContainer(int)} asserts the client
 * thread, so the overlay/panel must never read containers directly — this cache recomputes on the
 * client thread (from {@code ItemContainerChanged}) and serves readers a volatile immutable snapshot,
 * exactly as {@link ItemNameCache} does for names.
 */
@Singleton
public final class HeldItemCache
{
	private final Client client;
	private final ClientThread clientThread;

	private volatile Set<Integer> held = Collections.emptySet();

	@Inject
	HeldItemCache(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/** EDT/render-safe: the current held+worn item id snapshot (immutable). */
	public Set<Integer> heldIds()
	{
		return held;
	}

	/** Recomputes the snapshot on the client thread from the two live containers. */
	public void refresh(ItemContainer inv, ItemContainer worn)
	{
		held = idsOf(inv == null ? null : inv.getItems(), worn == null ? null : worn.getItems());
	}

	/** Convenience: recompute from the current containers. MUST run on the client thread. */
	public void refreshNow()
	{
		clientThread.invoke(() ->
			refresh(client.getItemContainer(InventoryID.INV), client.getItemContainer(InventoryID.WORN)));
	}

	/** Pure: union of non-negative item ids across both arrays. Either may be null. */
	public static Set<Integer> idsOf(Item[] inv, Item[] worn)
	{
		Set<Integer> ids = new HashSet<>();
		addIds(ids, inv);
		addIds(ids, worn);
		return Collections.unmodifiableSet(ids);
	}

	private static void addIds(Set<Integer> ids, Item[] items)
	{
		if (items == null)
		{
			return;
		}
		for (Item item : items)
		{
			if (item != null && item.getId() >= 0)
			{
				ids.add(item.getId());
			}
		}
	}
}
