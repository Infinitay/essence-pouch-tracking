package essencepouchtracking.pouch;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.NpcID;

public class RepairDialog
{

	private static class Regular
	{
		// Regular Essence Pouches via Dark Mage
		// Already repaired pouch dialog options via Dark Mage
		private static final List<String> ALREADY_REPAIRED_DARK_MAGE_DIALOG_OPTIONS = ImmutableList.of("Select an option", "Can I have another Abyssal book?", "Actually, I don't need anything right now.", "", "");
		// Degraded pouch dialog options via Dark Mage
		private static final List<String> PRE_REPAIR_DARK_MAGE_DIALOG_OPTIONS = ImmutableList.of("Select an option", "Can I have another Abyssal book?", "Can you repair my pouches?", "Actually, I don't need anything right now.", "", "");
		private static final String REPAIR_CONFIRMATION_DIALOG_TEXT = "Fine. A simple transfiguration spell should resolve things<br>for you.";
		private static final List<String> POST_REPAIR_DIALOG_OPTIONS = ImmutableList.of("Select an option", "Can I have another Abyssal book?", "Thanks.", "", "");
		// Already repaired pouch NPC dialog text via Dark Mage Menu Interaction (Right-click "Repair")
		private static final String ALREADY_REPAIRED_DARK_MAGE_DIALOG_TEXT = "You don't seem to have any pouches in need of repair.<br>Leave me alone!";
		// Degraded pouch NPC dialog text via Dark Mage Menu Interaction (Right-click "Repair")
		private static final String POST_REPAIR_DARK_MAGE_DIALOG_TEXT = "There, I have repaired your pouches. Now leave me<br>alone. I'm concentrating!";

		// Regular Essence Pouches via Apprentice Cordelia
		// Already repaired pouch dialog options via Apprentice Cordelia (Any interaction)
		public static final String ALREADY_REPAIRED_CORDELIA_DIALOG_TEXT = "You don't seem to have any pouches in need of repair.";
		// Degraded pouch dialog options via Apprentice Cordelia (Any interaction)
		private static final String PRE_REPAIR_CORDELIA_DIALOG_TEXT = "I got someone here in need of a pouch repair."; // Cordelia
		public static final String REPAIR_CONFIRMATION_CORDELIA_DARK_MAGE_DIALOG_TEXT = "OK...It's done."; // Dark Mage
		public static final String POST_REPAIR_CORDELIA_DIALOG_TEXT = "Your pouches have been repaired."; // Cordelia
	}

	private static class Colossal
	{
		// Colossal Essence Pouch via Dark Mage
		// Already repaired pouch dialog options via Dark Mage
		private static final List<String> ALREADY_REPAIRED_DARK_MAGE_DIALOG_OPTIONS = ImmutableList.of("Select an option", "Can I have another Abyssal book?", "Can I have a new essence pouch?", "Actually, I don't need anything right now.", "", "");
		// Degraded pouch dialog options via Dark Mage
		private static final List<String> PRE_REPAIR_DARK_MAGE_DIALOG_OPTIONS = ImmutableList.of("Select an option", "Can I have another Abyssal book?", "Can you repair my pouches?", "Can I have a new essence pouch?", "Actually, I don't need anything right now.", "", "");
		private static final String REPAIR_CONFIRMATION_DIALOG_TEXT = "Fine. A simple transfiguration spell should resolve things<br>for you.";
		private static final List<String> POST_REPAIR_DIALOG_OPTIONS = ImmutableList.of("Select an option", "Can I have another Abyssal book?", "Can I have a new essence pouch?", "Thanks.", "", "");
		// Already repaired pouch NPC dialog text via Dark Mage Menu Interaction (Right-click "Repair")
		private static final String ALREADY_REPAIRED_DARK_MAGE_DIALOG_TEXT = "You don't seem to have any pouches in need of repair.<br>Leave me alone!";
		// Degraded pouch NPC dialog text via Dark Mage Menu Interaction (Right-click "Repair")
		private static final String POST_REPAIR_DARK_MAGE_DIALOG_TEXT = "There, I have repaired your pouches. Now leave me<br>alone. I'm concentrating!";

		// Colossal Essence Pouch via Apprentice Cordelia
		// Already repaired pouch dialog options via Apprentice Cordelia (Any interaction)
		private static final String ALREADY_REPAIRED_CORDELIA_DIALOG_TEXT = "You don't seem to have any pouches in need of repair.";
		// Degraded pouch dialog options via Apprentice Cordelia (Any interaction)
		private static final String PRE_REPAIR_CORDELIA_DIALOG_TEXT = "I got someone here in need of a pouch repair."; // Cordelia
		private static final String REPAIR_CONFIRMATION_CORDELIA_DARK_MAGE_DIALOG_TEXT = "OK...It's done."; // Dark Mage
		private static final String POST_REPAIR_CORDELIA_DIALOG_TEXT = "Your pouches have been repaired."; // Cordelia
	}

	/**
	 * The model ID of the Dark Mage NPC widget
	 */
	public static final int DARK_MAGE_WIDGET_MODEL_ID = NpcID.DARK_MAGE;

	/**
	 * The model ID of the Apprentice Cordelia NPC widget
	 * Cordelia's NPC ID is 12180 but her model ID when talking to her is 6717
	 */
	public static final int APPRENTICE_CORDELIA_WIDGET_MODEL_ID = 6717;

	/**
	 * The dialog text of the player requesting the Dark Mage to repair their pouches.
	 * As soon as the player confirms the dialog, the NPC will repair the pouches instantly.
	 */
	public static final String REQUEST_REPAIR_PLAYER_DIALOG_TEXT = "Can you repair my pouches?";

	/**
	 * The dialog text of the player requesting Apprentice Cordelia to repair their pouches.
	 * As soon as the player confirms the dialog, the NPC will repair the pouches instantly.
	 */
	public static final String REQUEST_REPAIR_CORDELIA_DIALOG_TEXT = "I got someone here in need of a pouch repair.";

	/**
	 * The text of the continue widget that appears in dialogs, typically in conversational dialog text
	 */
	public static final String DIALOG_CONTINUE_TEXT = "Click here to continue";

	/**
	 * Set of all dialog texts by the Dark Mage that indicate the player has or had already repaired their pouches
	 */
	public static final Set<String> REPAIRED_DARK_MAGE_DIALOG_TEXTS = ImmutableSet.of(
		Regular.REPAIR_CONFIRMATION_DIALOG_TEXT,
		Regular.POST_REPAIR_DARK_MAGE_DIALOG_TEXT,
		Regular.ALREADY_REPAIRED_DARK_MAGE_DIALOG_TEXT
	);

	/**
	 * Set of all dialog options by the Dark Mage that indicate the player has or had already repaired their pouches
	 */
	public static final Set<String> POST_REPAIR_DIALOG_OPTIONS = ImmutableSet.<String>builder()
		.addAll(Regular.ALREADY_REPAIRED_DARK_MAGE_DIALOG_OPTIONS)
		.addAll(Colossal.ALREADY_REPAIRED_DARK_MAGE_DIALOG_OPTIONS)
		.addAll(Regular.POST_REPAIR_DIALOG_OPTIONS)
		.addAll(Colossal.POST_REPAIR_DIALOG_OPTIONS)
		.build();

	/**
	 * The dialog text by the Dark Mage that indicates the player has repaired their pouches
	 */
	public static final String REPAIR_CONFIRMATION_CORDELIA_DARK_MAGE_DIALOG_TEXT = Regular.REPAIR_CONFIRMATION_CORDELIA_DARK_MAGE_DIALOG_TEXT;

	/**
	 * Set of all dialog texts by Apprentice Cordelia that indicate the player has or had already repaired their pouches
	 */
	public static final Set<String> REPAIRED_CORDELIA_DIALOG_TEXTS = ImmutableSet.of(
		Regular.ALREADY_REPAIRED_CORDELIA_DIALOG_TEXT,
		Regular.POST_REPAIR_CORDELIA_DIALOG_TEXT
	);
}
