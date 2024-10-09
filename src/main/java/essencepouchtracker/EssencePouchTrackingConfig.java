package essencepouchtracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;

@ConfigGroup(EssencePouchTrackingConfig.GROUP)
public interface EssencePouchTrackingConfig extends Config
{
	String GROUP = "essencepouchtracker";
	/*@ConfigItem(
		keyName = "data",
		name = "Pouch Data",
		description = "State of the essence pouches",
		hidden = true
	)
	default String greeting()
	{
		return "Hello";
	}*/
}
