package essencepouchtracker;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.ItemID;

@Getter
@AllArgsConstructor
public enum EssencePouches
{
	SMALL("Small pouch", ItemID.SMALL_POUCH, -1, 3, -1),
	MEDIUM("Medium pouch", ItemID.MEDIUM_POUCH, ItemID.MEDIUM_POUCH_5511, 6, 45),
	LARGE("Large pouch", ItemID.LARGE_POUCH, ItemID.LARGE_POUCH_5513, 9, 29),
	GIANT("Giant pouch", ItemID.GIANT_POUCH, ItemID.GIANT_POUCH_5515, 12, 11),
	COLOSSAL("Colossal pouch", ItemID.COLOSSAL_POUCH, ItemID.COLOSSAL_POUCH_26786, 40, 8);


	private final String name;
	private final int itemID;
	private final int degradedItemID;
	private final int capacity;
	private final int fillsBeforeDecay;

	public static EssencePouches getPouch(int itemID)
	{
		for (EssencePouches pouch : values())
		{
			if (pouch.getItemID() == itemID || pouch.getDegradedItemID() == itemID)
			{
				return pouch;
			}
		}
		return null;
	}

	public int getMaxEssenceBeforeDecay()
	{
		return this == EssencePouches.SMALL ? Integer.MAX_VALUE : capacity * fillsBeforeDecay;
	}

	public static boolean isPouchDegraded(int itemID)
	{
		for (EssencePouches pouch : values())
		{
			if (pouch.getDegradedItemID() == itemID)
			{
				return true;
			}
		}
		return false;
	}

	public static EssencePouch createPouch(int itemID) {
		EssencePouch essencePouch = null;
		for (EssencePouches pouch : values())
		{
			if (pouch.getItemID() == itemID)
			{
				essencePouch = new EssencePouch(pouch);
			} else if (pouch.getDegradedItemID() == itemID) {
				essencePouch = new EssencePouch(pouch);
				essencePouch.setDegraded(true);
				essencePouch.setRemainingEssenceBeforeDecay(Integer.MIN_VALUE);
			}
		}
		return essencePouch;
	}
}
