package essencepouchtracking;

import com.google.common.collect.ImmutableMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.ItemID;

@Getter
@AllArgsConstructor
public enum EssencePouches
{
	SMALL("Small pouch", ItemID.SMALL_POUCH, -1, 3, -1, 3),
	MEDIUM("Medium pouch", ItemID.MEDIUM_POUCH, ItemID.MEDIUM_POUCH_5511, 6, 45, 3),
	LARGE("Large pouch", ItemID.LARGE_POUCH, ItemID.LARGE_POUCH_5513, 9, 29, 7),
	GIANT("Giant pouch", ItemID.GIANT_POUCH, ItemID.GIANT_POUCH_5515, 12, 11, 9),
	COLOSSAL("Colossal pouch", ItemID.COLOSSAL_POUCH, ItemID.COLOSSAL_POUCH_26786, 40, 8, 35);

	private final String name;
	private final int itemID;
	private final int degradedItemID;
	private final int maxCapacity;
	private final int fillsBeforeDecay;
	private final int maxInitialDegradedCapacity;

	private static final Pattern CHECK_STRING_PATTERN = Pattern.compile("There (are|is) (?<number>[a-z-]+)", Pattern.CASE_INSENSITIVE);
	private static final ImmutableMap<String, Integer> CHECK_STRING_CONVERSION_MAP = ImmutableMap.<String, Integer>builder().put("no", 0)
		.put("one", 1)
		.put("two", 2)
		.put("three", 3)
		.put("four", 4)
		.put("five", 5)
		.put("six", 6)
		.put("seven", 7)
		.put("eight", 8)
		.put("nine", 9)
		.put("ten", 10)
		.put("eleven", 11)
		.put("twelve", 12)
		.put("thirteen", 13)
		.put("fourteen", 14)
		.put("fifteen", 15)
		.put("sixteen", 16)
		.put("seventeen", 17)
		.put("eighteen", 18)
		.put("nineteen", 19)
		.put("twenty", 20)
		.put("twenty-one", 21)
		.put("twenty-two", 22)
		.put("twenty-three", 23)
		.put("twenty-four", 24)
		.put("twenty-five", 25)
		.put("twenty-six", 26)
		.put("twenty-seven", 27)
		.put("twenty-eight", 28)
		.put("twenty-nine", 29)
		.put("thirty", 30)
		.put("thirty-one", 31)
		.put("thirty-two", 32)
		.put("thirty-three", 33)
		.put("thirty-four", 34)
		.put("thirty-five", 35)
		.put("thirty-six", 36)
		.put("thirty-seven", 37)
		.put("thirty-eight", 38)
		.put("thirty-nine", 39)
		.put("forty", 40)
		.build();

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
		return this == EssencePouches.SMALL ? Integer.MAX_VALUE : maxCapacity * fillsBeforeDecay;
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

	public static EssencePouch createPouch(int itemID)
	{
		EssencePouch essencePouch = null;
		for (EssencePouches pouch : values())
		{
			if (pouch.getItemID() == itemID)
			{
				essencePouch = new EssencePouch(pouch);
			}
			else if (pouch.getDegradedItemID() == itemID)
			{
				essencePouch = new EssencePouch(pouch);
				essencePouch.setDegraded(true);
				essencePouch.setRemainingEssenceBeforeDecay(Integer.MIN_VALUE);
			}
		}
		return essencePouch;
	}

	/**
	 * Converts the number of essence in the pouch as a word to an integer.
	 *
	 * @param essenceString The string when checking an essence pouch
	 * @return The number of essence in the pouch as an integer or -1 if it wasn't found
	 */
	public static int checkEssenceStringToInt(String essenceString)
	{
		// Pre-check before calling that the string ends in "in this pouch."
		essenceString = essenceString.toLowerCase();
		Matcher checkMatcher = CHECK_STRING_PATTERN.matcher(essenceString);
		if (checkMatcher.find())
		{
			String numberAsWords = checkMatcher.group("number");
			Integer number = CHECK_STRING_CONVERSION_MAP.get(numberAsWords);
			return number != null ? number : -1;
		}
		return -1;
	}
}
