package essencepouchtracking;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.ComponentID;
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
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		EssencePouchTrackingState trackingState = this.plugin.getTrackingState();
		if (trackingState == null || !this.config.showStoredEssence() || !this.config.showDecay())
		{
			return;
		}

		EssencePouch pouch = trackingState.getPouch(itemId);
		if (pouch != null)
		{
			if (this.config.showStoredEssence())
			{
				renderStoredEssence(graphics, itemId, widgetItem, pouch);
			}

			if (this.config.showDecay())
			{
				renderDecayRemaining(graphics, itemId, widgetItem, pouch);
			}
		}
	}

	private void renderStoredEssence(Graphics2D graphics, int itemId, WidgetItem widgetItem, EssencePouch pouch)
	{
		boolean unknownState = pouch.isUnknownStored();
		String storedEssenceText = unknownState ? "?" : String.valueOf(pouch.getStoredEssence());
		Rectangle itemBounds = widgetItem.getCanvasBounds();
		TextComponent storedEssenceTC = new TextComponent();
		FontMetrics fm = graphics.getFontMetrics();
		Point storedTextPosition;
		if (widgetItem.getWidget().getParentId() != ComponentID.BANK_ITEM_CONTAINER)
		{
			storedTextPosition = new Point(itemBounds.x, itemBounds.y + 8 + 2);
		}
		else
		{
			storedTextPosition = new Point(itemBounds.x + itemBounds.width - fm.stringWidth(storedEssenceText), itemBounds.y + 8 + 2);
		}
		storedEssenceTC.setPosition(storedTextPosition);
		storedEssenceTC.setText(storedEssenceText);
		storedEssenceTC.setColor(Color.RED);
		storedEssenceTC.render(graphics);
	}

	private void renderDecayRemaining(Graphics2D graphics, int itemId, WidgetItem widgetItem, EssencePouch pouch)
	{
		Rectangle itemBounds = widgetItem.getCanvasBounds();
		boolean unknownState = pouch.isUnknownDecay();
		int remainingEssence = pouch.getRemainingEssenceBeforeDecay();
		String remainingEssenceText;
		if (!pouch.isDegraded())
		{
			if (remainingEssence <= 0 || unknownState)
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
		Point remainingTextPosition = new Point(itemBounds.x + itemBounds.width - fm.stringWidth(remainingEssenceText), itemBounds.y + itemBounds.height - 2);
		remainingTC.setPosition(remainingTextPosition);
		remainingTC.setText(remainingEssenceText);
		remainingTC.setColor(Color.WHITE);
		remainingTC.render(graphics);
	}
}
