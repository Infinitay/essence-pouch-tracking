package essencepouchtracking;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Deque;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

@Slf4j
public class EssencePouchTrackingDebugOverlay extends OverlayPanel
{
	private final EssencePouchTrackingPlugin plugin;
	private final EssencePouchTrackingConfig config;
	private final Client client;

	@Inject
	private EssencePouchTrackingDebugOverlay(EssencePouchTrackingPlugin plugin, EssencePouchTrackingConfig config, Client client)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setPriority(PRIORITY_MED);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<EssencePouches, EssencePouch> pouches = this.plugin.getPouches();
		Deque<PouchActionTask> pouchTaskQueue = this.plugin.getPouchTaskQueue();
		int previousEssenceInInventory = this.plugin.getPreviousEssenceInInventory();
		int essenceInInventory = this.plugin.getEssenceInInventory();

		int previousInventoryFreeSlots = this.plugin.getPreviousInventoryFreeSlots();
		int inventoryFreeSlots = this.plugin.getInventoryFreeSlots();
		int previousInventoryUsedSlots = this.plugin.getPreviousInventoryUsedSlots();
		int inventoryUsedSlots = this.plugin.getInventoryUsedSlots();

//		boolean isPaused = (this.plugin.getPauseUntilTick() != -1 && this.client.getTickCount() > this.plugin.getPauseUntilTick());
		boolean isPaused = !(this.plugin.getPauseUntilTick() == -1 || this.client.getTickCount() >= this.plugin.getPauseUntilTick());
		boolean wasLastActionCraft = this.plugin.isWasLastActionCraftRune();

		buildLine("Current Tick", String.valueOf(this.client.getTickCount()));
		buildLine("Updates Paused?", String.valueOf(isPaused) + " (" + this.plugin.getPauseUntilTick() + ")");
		buildLine("Crafted?", String.valueOf(wasLastActionCraft));
		buildLine("", "");
		buildLine("Prev Free Slot", String.valueOf(previousInventoryFreeSlots));
		buildLine("Free Slot", String.valueOf(inventoryFreeSlots));
		buildLine("Prev Used Slot", String.valueOf(previousInventoryUsedSlots));
		buildLine("Used Slot", String.valueOf(inventoryUsedSlots));
		buildLine("Prev Essence", String.valueOf(previousEssenceInInventory));
		buildLine("Essence", String.valueOf(essenceInInventory));

		if (!pouchTaskQueue.isEmpty())
		{
			buildLine("", "");
			int q = 1;
			for (PouchActionTask pouch : pouchTaskQueue)
			{
				buildLine(String.valueOf(q++), pouch.toString());
			}
		}

		if (!pouches.isEmpty())
		{
			buildLine("", "");
			for (Map.Entry<EssencePouches, EssencePouch> entry : pouches.entrySet())
			{
				EssencePouch pouch = entry.getValue();
				buildLine(entry.getKey().toString(), String.valueOf(pouch.getStoredEssence()) + "(" + pouch.getMaximumCapacity() + " " + pouch.getMaxDegradedCapacity() + ")");
			}
		}
		return super.render(graphics);
	}

	private void buildLine(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
	}
}
