package com.systeminterface.core;

import com.systeminterface.modules.ui.SystemPanelOverlay;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.services.state.StateTracker;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.NPCManager;
import net.runelite.client.util.Text;

/**
 * Stateless event handlers. The plugin's {@code @Subscribe} methods delegate
 * one-line into here so this class can be unit-tested without an EventBus.
 *
 * <p>See {@code com.systeminterface.core} package javadoc for the full
 * event-to-feature map.
 */
@Slf4j
@Singleton
public final class InteractionListener
{
	private static final int LIVE_KILL_DEDUPE_TICKS = 10;

	/**
	 * Matches OSRS kill/completion/chest/catch chat messages. Captures the
	 * target name (group 1) and the count (group 3, may contain commas).
	 * Examples:
	 * <ul>
	 *   <li>{@code "Your Vorkath kill count is: 1,284."}</li>
	 *   <li>{@code "Your Zulrah kill count is: 567."}</li>
	 *   <li>{@code "Your Tempoross completion count is: 12."}</li>
	 * </ul>
	 */
	private static final Pattern KC_PATTERN = Pattern.compile(
		"^Your (.+?) (kill|completion|chest|catch|harvest|subdued) count is:?\\s*(\\d[\\d,]*)\\.?$");

	private final Client client;
	private final StateTracker stateTracker;
	private final SystemPanelOverlay overlay;
	private final NPCManager npcManager;
	private final SkillTracker skillTracker;
	private Object lastLiveKillKey;
	private String lastLiveKillName;
	private int lastLiveKillTick = Integer.MIN_VALUE;

	@Inject
	public InteractionListener(Client client, StateTracker stateTracker, SystemPanelOverlay overlay,
		NPCManager npcManager, SkillTracker skillTracker)
	{
		this.client = client;
		this.stateTracker = stateTracker;
		this.overlay = overlay;
		this.npcManager = npcManager;
		this.skillTracker = skillTracker;
	}

	// ---------------------------------------------------------------------
	// ChatMessage — primary live KC source
	// ---------------------------------------------------------------------

	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}
		final String message = Text.removeTags(event.getMessage());
		final Matcher m = KC_PATTERN.matcher(message);
		if (!m.matches())
		{
			return;
		}
		final String target = m.group(1).trim();
		final int kc;
		try
		{
			kc = Integer.parseInt(m.group(3).replace(",", ""));
		}
		catch (NumberFormatException nfe)
		{
			return;
		}
		stateTracker.observeKillCount(target, kc, StateTracker.KcSource.CHAT_MESSAGE);
	}

	// ---------------------------------------------------------------------
	// InteractingChanged — drives the contextual System Panel
	// ---------------------------------------------------------------------

	public void onInteractingChanged(InteractingChanged event)
	{
		// Only care about the local player initiating an interaction.
		if (event.getSource() != client.getLocalPlayer())
		{
			return;
		}
		Actor target = event.getTarget();
		if (target instanceof NPC)
		{
			NPC npc = (NPC) target;
			// Fishing spots are NPCs too — route them to skill tracking and skip the combat
			// panel so they don't pollute the combat target list.
			if (skillTracker.onFishingSpotInteract(npc.getId()))
			{
				return;
			}
			String name = npc.getName();
			if (name != null)
			{
				Integer maxHp = npcManager.getHealth(npc.getId());
				overlay.setCurrentTarget(name, npc, maxHp != null ? maxHp : 0);
				int combatLevel = npc.getCombatLevel();
				if (combatLevel > 0)
				{
					stateTracker.setCombatLevel(name, combatLevel);
				}
				// Interacting with an NPC counts as engaging it — reveals its loot
				// table in the side panel even before the first kill.
				stateTracker.markEngaged(name);
			}
		}
		// Intentionally NOT clearing on null target — keep the last target
		// sticky so the panel doesn't flicker mid-fight when interaction lapses
		// briefly (e.g. between phases or during animations).
	}

	// ---------------------------------------------------------------------
	// NpcLootReceived — primary live drop source
	// ---------------------------------------------------------------------

	public void onNpcLootReceived(NpcLootReceived event)
	{
		final NPC npc = event.getNpc();
		if (npc == null)
		{
			return;
		}
		final String npcName = npc.getName();
		if (npcName == null)
		{
			return;
		}
		recordLiveKill(npcName, npc);

		// Increment KC for non-chat-tracked NPCs (Man, Goblin, Cow, etc.) so the
		// counter stays in sync even when the game doesn't print a KC chat line.
		// For chat-tracked NPCs (Vorkath, Zulrah, GWD bosses) this is a no-op —
		// the chat line is authoritative.

		// Always bump the session counter — session KC is transient and resets
		// on profile switch / plugin restart, so double-counting with the chat
		// lifetime path isn't a concern.

		// Receiving loot means we're still actively engaged — refresh the
		// auto-hide timer so the panel doesn't vanish right after a kill.
		overlay.refreshActivity();

		// Read AFTER the increment so the drop's recorded KC reflects this kill.
		final com.systeminterface.services.state.TargetState state = stateTracker.get(npcName);
		final int kc = state != null ? state.getCurrentKc() : 1;

		for (ItemStack stack : event.getItems())
		{
			ItemComposition def = client.getItemDefinition(stack.getId());
			String itemName = def != null ? def.getName() : null;
			if (itemName == null || itemName.isEmpty() || "null".equalsIgnoreCase(itemName))
			{
				continue;
			}
			stateTracker.rememberItemName(stack.getId(), itemName);
			stateTracker.recordDrop(npcName, itemName, kc, StateTracker.KcSource.LIVE_DROP_EVENT);
		}
	}

	public void onNoDropKill(String npcName, Object npcKey)
	{
		if (recordLiveKill(npcName, npcKey))
		{
			overlay.refreshActivity();
		}
	}

	private boolean recordLiveKill(String npcName, Object npcKey)
	{
		if (npcName == null)
		{
			return false;
		}
		final int tick = client.getTickCount();
		if (isDuplicateLiveKill(npcName, npcKey, tick))
		{
			return false;
		}

		stateTracker.recordKillIfNotChatTracked(npcName);
		stateTracker.incrementSessionKill(npcName);
		lastLiveKillName = npcName;
		lastLiveKillKey = npcKey;
		lastLiveKillTick = tick;
		return true;
	}

	private boolean isDuplicateLiveKill(String npcName, Object npcKey, int tick)
	{
		if (tick - lastLiveKillTick > LIVE_KILL_DEDUPE_TICKS)
		{
			return false;
		}
		if (npcKey != null && npcKey == lastLiveKillKey)
		{
			return true;
		}
		return npcKey == null && npcName.equals(lastLiveKillName) && tick == lastLiveKillTick;
	}

	public void onGameStateChanged()
	{
	}
}



