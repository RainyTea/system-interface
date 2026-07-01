package com.systeminterface.services.lookup;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;

/**
 * Resolves whether an item (by name) is members-only.
 *
 * <p>{@link net.runelite.client.game.ItemManager#search(String)} only indexes
 * <em>tradeable</em> items (it iterates the GE price map), so it can't resolve
 * untradeables like champion scrolls — which then slip through the free-world
 * drop filter. To cover every item we build a one-time {@code name -> members}
 * index by walking all item definitions on the client thread (where
 * {@link Client#getItemDefinition(int)} is safe to call), then answer lookups
 * from that index in O(1).
 *
 * <p>The index is built lazily on first lookup. Until it's ready, lookups return
 * {@code false} (keep the item) so nothing is wrongly hidden; once the build
 * lands, {@link #setOnReady(Runnable)} fires so callers can re-render.
 */
@Singleton
public final class ItemMembership
{
	private final Client client;
	private final ClientThread clientThread;

	/** Lowercased item name → members-only flag. {@code null} until built. */
	private volatile Map<String, Boolean> index;
	private final AtomicBoolean building = new AtomicBoolean(false);
	private volatile Runnable onReady;

	@Inject
	ItemMembership(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/** Callback fired (once) on the client thread when the index becomes available. */
	//public void setOnReady(Runnable onReady) {this.onReady = onReady;}
    public void setOnReady(Runnable onReady) {this.onReady = onReady;}


	/** True once the index is built and lookups are authoritative. */
	public boolean isReady()
	{
		return index != null;
	}

	/**
	 * @return {@code true} if the named item is members-only. Returns {@code true}
	 *         (conservative — hides on F2P) when the index is still building OR when
	 *         the item name is not found in the game cache. This guarantees F2P worlds
	 *         never show members items, even if a wiki name doesn't exactly match a
	 *         game cache name.
	 */
	public boolean isMembers(String name)
	{
		final Map<String, Boolean> idx = index;
		if (idx == null)
		{
			build();
			return true;
		}
		if (name == null)
		{
			return false;
		}
		final Boolean members = idx.get(name.toLowerCase());
		return members == null || members;
	}

	/**
	 * Builds the {@code name -> members} index once, on the client thread. No-op if
	 * already built or in progress. Aborts (to retry later) if the item cache isn't
	 * loaded yet.
	 */
	public void build()
	{
		if (index != null || !building.compareAndSet(false, true))
		{
			return;
		}
		clientThread.invoke(() ->
		{
			Map<String, Boolean> built = null;
			try
			{
				final int count = client.getItemCount();
				if (count <= 0)
				{
					return; // cache not ready — leave index null so we retry on next lookup
				}
				final Map<String, Boolean> idx = new HashMap<>(count);
				for (int id = 0; id < count; id++)
				{
					final ItemComposition comp;
					try
					{
						comp = client.getItemDefinition(id);
					}
					catch (RuntimeException e)
					{
						continue;
					}
					// Skip placeholders and noted variants — they duplicate the real
					// item's name and carry the same membership anyway.
					if (comp == null || comp.getPlaceholderTemplateId() != -1 || comp.getNote() != -1)
					{
						continue;
					}
					final String n = comp.getName();
					if (n == null || n.isEmpty() || "null".equals(n))
					{
						continue;
					}
					idx.putIfAbsent(n.toLowerCase(), comp.isMembers());
				}
				built = idx;
				index = idx;
			}
			finally
			{
				building.set(false);
			}
			final Runnable r = onReady;
			if (built != null && r != null)
			{
				r.run();
			}
		});
	}
}
