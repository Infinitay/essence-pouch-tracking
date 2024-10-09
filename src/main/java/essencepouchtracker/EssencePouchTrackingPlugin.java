package essencepouchtracker;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.collect.Queues;
import com.google.inject.Provides;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.api.NpcID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Essence Pouch Tracking"
)
public class EssencePouchTrackingPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private EssencePouchTrackingConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private EssencePouchTrackingOverlay overlay;

	@Getter
	private final Map<EssencePouches, EssencePouch> pouches = new HashMap<>();
	private final Queue<PouchActionTask> pouchQueue = Queues.newArrayDeque();
	private Multiset<Integer> previousInventory = HashMultiset.create();

	private boolean firstStart;
	private boolean isRepairDialogue;

	@Provides
	EssencePouchTrackingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EssencePouchTrackingConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Example started!");
//		firstStart = true;
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Example stopped!");
		previousInventory.clear();
		pouches.clear();
		pouchQueue.clear();
		overlayManager.remove(overlay);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged changedConfig)
	{
		if (changedConfig.getGroup().equals(EssencePouchTrackingConfig.GROUP))
		{
			log.debug("Config changed: {}", changedConfig.getKey());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		log.debug("onGameStateChanged: " + gameStateChanged.getGameState());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked menuOptionClicked)
	{
		log.debug("onMenuOptionClicked: " + client.getTickCount());
		if (menuOptionClicked.getMenuAction() == MenuAction.CC_OP || menuOptionClicked.getMenuAction() == MenuAction.CC_OP_LOW_PRIORITY)
		{
			EssencePouches pouchType = EssencePouches.getPouch(menuOptionClicked.getItemId());
			if (pouchType != null && (menuOptionClicked.getMenuOption().equals("Fill") || menuOptionClicked.getMenuOption().equals("Empty")))
			{
				PouchActionTask pouchTask = new PouchActionTask(pouchType, menuOptionClicked.getMenuOption());
				pouchQueue.add(pouchTask);
				log.debug("Adding task \"{}\" to the queue", pouchTask);
			}
			else if (pouchType != null && menuOptionClicked.getMenuOption().equals("Examine"))
			{
				log.debug("Examine option clicked");
				log.debug("{}", pouches.values());
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged itemContainerChanged)
	{
		log.debug("onItemContainerChanged ({}): " + client.getTickCount(), itemContainerChanged.getContainerId());
		// Make sure we're only focusing on the inventory
		if (itemContainerChanged.getContainerId() == InventoryID.INVENTORY.getId())
		{
			Multiset<Integer> currentInventory = HashMultiset.create();
			List<Item> inventoryItems = Arrays.stream(itemContainerChanged.getItemContainer().getItems()).filter(item -> item.getId() != -1).collect(Collectors.toList());
			int usedInventorySlots = inventoryItems.size();
			int freeSlots = 28 - usedInventorySlots;
			boolean hasFillableEssenceInInventory = inventoryItems.stream().anyMatch(item -> this.isValidEssencePouchItem(item.getId()));
			inventoryItems.forEach(item -> currentInventory.add(item.getId(), item.getQuantity()));
//			log.debug("Previous Inventory: " + previousInventory.stream().filter(e -> e != -1).collect(Collectors.toList()));
//			log.debug("Current Inventory: " + currentInventory.stream().filter(e -> e != -1).collect(Collectors.toList()));

			// Remember that for set operations difference A - B != B - A
			Multiset<Integer> addedItems = Multisets.difference(currentInventory, previousInventory);
			Multiset<Integer> removedItems = Multisets.difference(previousInventory, currentInventory);
			log.debug("Added Items: " + addedItems);
			log.debug("Removed Items: " + removedItems);
			previousInventory = currentInventory;

			boolean pouchJustDegraded = false;
			// Now that we've handling inventory state changes, we can handle the pouches
			// First check if we have any pouches in the inventory in case this is the user's first run or some other issue
			for (int itemId : addedItems)
			{
				EssencePouch pouch = EssencePouches.createPouch(itemId);
				if (pouch != null && !pouches.containsKey(pouch.getPouchType()))
				{
					log.debug("Adding new pouch: " + pouch.getPouchType().getName());
					pouches.put(pouch.getPouchType(), pouch);
				}

				// Check to see if pouch has degraded
				if (pouch != null && pouches.containsKey(pouch.getPouchType()))
				{
					EssencePouch currentPouch = pouches.get(pouch.getPouchType());
					if (currentPouch != null && currentPouch.getPouchType().getDegradedItemID() == itemId)
					{
						currentPouch.setDegraded(true);
						log.debug("{} has degraded", currentPouch.getPouchType().getName());
						pouchJustDegraded = true;
					}
				}
			}

			// First check to see if we even can fill the pouches by checking how much essence was removed from the inventory
			// TODO Add support for multiple essence types because you can't mix essence types when filling pouches
			int essenceToFill = (int) removedItems.stream().filter(removedItemID -> isValidEssencePouchItem(removedItemID)).count();
			int essenceToEmpty = (int) addedItems.stream().filter(addedItemID -> isValidEssencePouchItem(addedItemID)).count();
			log.debug("Essence to fill: " + essenceToFill);
			log.debug("Essence to empty: " + essenceToEmpty);
			while (!pouchQueue.isEmpty())
			{
				// Get the first pouch in the queue to be filled
				log.debug("Pouch Queue Before Filling/Emptying: " + pouchQueue);
				PouchActionTask pouchAction = pouchQueue.poll();

				EssencePouch pouch = pouches.get(pouchAction.getPouchType());
				if (pouch != null)
				{
					// Edge case where the user fills the pouch but there's no essence to fill it with despite having it in the inventory
					// This can happen when a user instantly fills and empties a pouch on the same tick. Then there will be no inventory change
					if (essenceToFill == 0 && hasFillableEssenceInInventory) {
						// There's essence but the action was too fast to register the inventory change so handle it manually
						essenceToFill += pouch.getStoredEssence();
						log.debug("Essence to fill after manual check due to potential tick error: " + essenceToFill);
					}
					if (essenceToEmpty == 0 && freeSlots > 0) {
						// There's essence but the action was too fast to register the inventory change so handle it manually
						essenceToEmpty += inventoryItems.stream().filter(item -> this.isValidEssencePouchItem(item.getId())).count();
						log.debug("Essence to empty after manual check due to potential tick error: " + essenceToEmpty);
					}
					if (pouchAction.getAction() == PouchActionTask.PouchAction.FILL)
					{
						if (essenceToFill < 1)
						{
							log.debug("Can't fill {} pouch because there is no essence to fill it with", pouch.getPouchType().getName());
							continue;
						}
						essenceToFill = pouch.fill(essenceToFill);
					}
					else if (pouchAction.getAction() == PouchActionTask.PouchAction.EMPTY)
					{
						log.debug("Just Degraded: {} Added Items: {}", pouchJustDegraded, addedItems.contains(pouch.getPouchType().getDegradedItemID()));
						if (pouchJustDegraded && addedItems.contains(pouch.getPouchType().getDegradedItemID()))
						{
							// If the pouch just degraded, we can't empty it because it's already empty
							log.debug("Can't empty {} pouch because it just degraded therefore the object is \"null\"", pouch.getPouchType().getName());
							continue;
						}

						// If there is more essence being emptied and added to our inventory than the current pouch can hold, only empty the max amount the pouch can hold
						if (essenceToEmpty >= pouch.getStoredEssence())
						{
							log.debug("IF CASE Emptying {} essence when essence to empty is {}", pouch.getStoredEssence(), essenceToEmpty);
							pouch.empty(pouch.getStoredEssence());
							essenceToEmpty -= pouch.getStoredEssence();
						}
						else
						{
							log.debug("ELSE CASE: Emptying {} essence when essence to empty is {}", essenceToEmpty, pouch.getStoredEssence());
							pouch.empty(essenceToEmpty);
							essenceToEmpty = 0;
						}
					}
				}
			}
			log.debug("Pouch Queue After Filling/Emptying: " + pouchQueue);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (isRepairDialogue)
		{
			boolean repairedPouches = false;

			Widget dialogNPCHeadModel = client.getWidget(ComponentID.DIALOG_NPC_HEAD_MODEL);
			if (dialogNPCHeadModel != null && dialogNPCHeadModel.getModelId() == NpcID.DARK_MAGE)
			{
				Widget dialogText = client.getWidget(ComponentID.DIALOG_NPC_TEXT);
				if (dialogText != null && dialogText.getText().equals("Fine. A simple transfiguration spell should resolve things<br>for you."))
				{
					repairedPouches = true;
				}
			}

			Widget x = client.getWidget(ComponentID.DIALOG_OPTION_OPTIONS);
			if (x != null && x.getChildren() != null)
			{
				List<String> options = Arrays.stream(x.getChildren()).filter(Objects::nonNull).map(Widget::getText).collect(Collectors.toList());
				List<String> POST_REPAIR_DIALOG_OPTIONS = ImmutableList.of("Select an option", "Can I have another Abyssal book?", "Thanks.", "", "");
				log.debug("Dialog Option Options: " + options);
				log.debug("Dialog Options Equals: " + options.equals(POST_REPAIR_DIALOG_OPTIONS));
				if (options.equals(POST_REPAIR_DIALOG_OPTIONS))
				{
					repairedPouches = true;
				}
			}

			if (repairedPouches)
			{
				log.debug("Pouches have been repaired");
				pouches.values().forEach(EssencePouch::repairPouch);
				log.debug("{}", pouches.values());
				isRepairDialogue = false;
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed widgetClosed)
	{
		switch (widgetClosed.getGroupId())
		{
			case InterfaceID.DIALOG_NPC:
			case InterfaceID.DIALOG_OPTION:
				isRepairDialogue = false;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
		log.debug("Widget Loaded: " + widgetLoaded.getGroupId());
		switch (widgetLoaded.getGroupId())
		{
			case InterfaceID.DIALOG_NPC:
			case InterfaceID.DIALOG_OPTION:
				isRepairDialogue = true;
				break;
			default:
				break;
		}
	}

	private boolean isValidEssencePouchItem(int itemID)
	{
		switch (itemID)
		{
			case ItemID.RUNE_ESSENCE:
			case ItemID.PURE_ESSENCE:
			case ItemID.DAEYALT_ESSENCE:
			case ItemID.GUARDIAN_ESSENCE:
				return true;
			default:
				return false;
		}
	}
}
