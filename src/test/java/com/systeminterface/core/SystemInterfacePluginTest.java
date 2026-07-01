package com.systeminterface.core;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SystemInterfacePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SystemInterfacePlugin.class);
		RuneLite.main(args);
	}
}

