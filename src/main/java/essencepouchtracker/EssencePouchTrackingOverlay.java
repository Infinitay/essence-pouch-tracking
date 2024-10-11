package essencepouchtracker;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TextComponent;

@Slf4j
public class EssencePouchTrackingOverlay extends WidgetItemOverlay
{
	private final EssencePouchTrackingPlugin plugin;
	private final EssencePouchTrackingConfig config;

	@Inject
	private EssencePouchTrackingOverlay(EssencePouchTrackingPlugin plugin, EssencePouchTrackingConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		showOnInventory();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		Map<EssencePouches, EssencePouch> pouches = plugin.getPouches();

		if (pouches.isEmpty())
		{
			return;
		}

		EssencePouch pouch = pouches.get(EssencePouches.getPouch(itemId));
		if (pouch != null)
		{
			String storedEssenceText = String.valueOf(pouch.getStoredEssence());
			Rectangle itemBounds = widgetItem.getCanvasBounds();
			TextComponent storedEssenceTC = new TextComponent();
			Point storedTextPosition = new Point(itemBounds.x - 1, itemBounds.y + 8);
			storedEssenceTC.setPosition(storedTextPosition);
			storedEssenceTC.setText(storedEssenceText);
			storedEssenceTC.setColor(Color.RED);
			storedEssenceTC.render(graphics);

			int remainingEssence = pouch.getRemainingEssenceBeforeDecay();
			String remainingEssenceText;
			if (!pouch.isDegraded())
			{
				if (remainingEssence < 0)
				{
					remainingEssenceText = "Repair";
				}
				else if (remainingEssence == Integer.MAX_VALUE)
				{
					remainingEssenceText = "∞";
				}
				else
				{
					remainingEssenceText = String.valueOf(remainingEssence);
				}
			}
			else
			{
				remainingEssenceText = "Repair";
			}
			TextComponent remainingTC = new TextComponent();
			FontMetrics fm = graphics.getFontMetrics();
			Point remainingTextPosition = new Point(itemBounds.x + itemBounds.width - fm.stringWidth(remainingEssenceText) - 1, itemBounds.y + itemBounds.height);
			remainingTC.setPosition(remainingTextPosition);
			remainingTC.setText(remainingEssenceText);
			remainingTC.setColor(Color.WHITE);
			remainingTC.render(graphics);
		}
	}
}
