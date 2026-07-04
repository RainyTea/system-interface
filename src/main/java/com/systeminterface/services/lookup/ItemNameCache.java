package com.systeminterface.services.lookup;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * EDT-safe item name lookup for arbitrary combat drops. {@link ItemManager#getItemComposition(int)}
 * asserts the client thread and throws off it, so a Swing render can never call it directly — this
 * cache resolves names on the client thread (once per id, debounced) and serves the EDT from an
 * in-memory map. The very first render of a never-seen item id falls back to {@code "Item <id>"};
 * the real name appears on the next refresh once the client-thread resolve lands.
 */
@Singleton
public final class ItemNameCache
{
	private final ItemManager itemManager;
	private final ClientThread clientThread;

	private final Map<Integer, String> names = new ConcurrentHashMap<>();
	private final Set<Integer> pending = ConcurrentHashMap.newKeySet();

	@Inject
	ItemNameCache(ItemManager itemManager, ClientThread clientThread)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
	}

	/**
	 * @return the cached display name for {@code itemId}, or {@code "Item " + itemId} if it hasn't
	 *         been resolved yet (a one-time client-thread resolve is scheduled in that case). Safe
	 *         to call from the EDT — never touches {@code ItemManager} on the calling thread.
	 */
	public String name(int itemId)
	{
		final String cached = names.get(itemId);
		if (cached != null)
		{
			return cached;
		}
		if (pending.add(itemId))
		{
			clientThread.invoke(() ->
			{
				try
				{
					ItemComposition c = itemManager.getItemComposition(itemId);
					String n = c != null ? c.getName() : null;
					if (n != null && !n.isEmpty() && !"null".equalsIgnoreCase(n))
					{
						names.put(itemId, n);
					}
				}
				catch (Throwable ignored)
				{
					// Definition unavailable — leave unresolved, fall back to the id.
				}
				finally
				{
					pending.remove(itemId);
				}
			});
		}
		return "Item " + itemId;
	}
}
