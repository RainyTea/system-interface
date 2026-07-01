package com.systeminterface.modules.ui;

final class SystemPopupBuffer
{
	static final long DEFAULT_TTL_MILLIS = 3_000L;
	private static final long DEFAULT_DEBOUNCE_MILLIS = 1_500L;

	private final long ttlMillis;
	private final long debounceMillis;
	private String activeMessage;
	private String lastMessage;
	private long activeUntilMillis;
	private long lastShownMillis = Long.MIN_VALUE;

	SystemPopupBuffer()
	{
		this(DEFAULT_TTL_MILLIS, DEFAULT_DEBOUNCE_MILLIS);
	}

	SystemPopupBuffer(long ttlMillis, long debounceMillis)
	{
		this.ttlMillis = ttlMillis;
		this.debounceMillis = debounceMillis;
	}

	boolean push(String message, long nowMillis)
	{
		if (message == null || message.trim().isEmpty())
		{
			return false;
		}
		final String clean = message.trim();
		if (clean.equals(lastMessage) && nowMillis - lastShownMillis < debounceMillis)
		{
			return false;
		}
		activeMessage = clean;
		lastMessage = clean;
		lastShownMillis = nowMillis;
		activeUntilMillis = nowMillis + ttlMillis;
		return true;
	}

	String activeMessage(long nowMillis)
	{
		if (activeMessage == null || nowMillis >= activeUntilMillis)
		{
			activeMessage = null;
			return null;
		}
		return activeMessage;
	}
}
