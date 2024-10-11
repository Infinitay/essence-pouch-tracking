package essencepouchtracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class EssencePouchTrackingPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(EssencePouchTrackingPlugin.class);
		RuneLite.main(args);
	}
}