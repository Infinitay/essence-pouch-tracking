package essencepouchtracking;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.collect.Queues;
import com.google.inject.Provides;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NpcID;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
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
	private final Deque<PouchActionTask> pouchQueue = Queues.newArrayDeque();
	private Multiset<Integer> previousInventory = HashMultiset.create();

	private boolean firstStart;
	private boolean isRepairDialogue;

	private Multiset<Integer> currentInventoryItems = HashMultiset.create();
	private int previousEssenceInInventory, essenceInInventory;
	private int previousInventoryFreeSlots, inventoryFreeSlots;
	private int previousInventoryUsedSlots, inventoryUsedSlots;
	private boolean blockUpdate;

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
		currentInventoryItems.clear();
		previousEssenceInInventory = essenceInInventory = 0;
		previousInventoryFreeSlots = inventoryFreeSlots = 0;
		previousInventoryUsedSlots = inventoryUsedSlots = 0;
		blockUpdate = false;
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

	private boolean bankedXInput = false;
	private BankEssenceTask bankEssenceTask;

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked menuOptionClicked)
	{
		log.debug("onMenuOptionClicked: " + client.getTickCount());
		log.debug("{}", menuOptionClicked);
		log.debug("Is bank open: {}", client.getItemContainer(InventoryID.BANK) != null);

		// Keep in mind that the current inventory will be updated after this event so if the event is fill now and you have 10 essence in your inventory, the inventory will be updated to 0 after this event
//		ItemContainer currentInventoryContainer = this.getInventoryContainer();


		if (menuOptionClicked.getMenuAction() == MenuAction.CC_OP || menuOptionClicked.getMenuAction() == MenuAction.CC_OP_LOW_PRIORITY)
		{
			String menuOption = menuOptionClicked.getMenuOption().toLowerCase();
			// Handle withdrawing essence from the bank in case someone one-tick's it (or faster)
			// First check to see if the bank is open
			if (client.getItemContainer(InventoryID.BANK) != null && (menuOption.startsWith("deposit") || menuOption.startsWith("withdraw")))
			{
				int itemID = menuOptionClicked.getItemId();
				String quantity = menuOption.substring(menuOption.indexOf("-") + 1);
				menuOption = menuOption.substring(0, menuOption.indexOf("-"));
				log.debug("{} {} x{}", menuOption, itemID, quantity);

				Widget itemWidget = menuOptionClicked.getWidget();
				if (itemWidget == null)
				{
					return;
				}
				// Should only be used when Withdrawing
				int totalItemAmount;
				if (menuOption.equals("withdraw"))
				{
					// Withdrawing from the bank => Quantity is on the item Widget
					totalItemAmount = itemWidget.getItemQuantity();
				}
				else
				{
					// The player is depositing from the inventory so fetch the total amount of the item inside the inventory
					totalItemAmount = (int) currentInventoryItems.stream().filter(inventoryItemID -> inventoryItemID == itemID).count();
					totalItemAmount = essenceInInventory;
				}
				int quantityNumeric;
				try
				{
					quantityNumeric = Integer.parseInt(quantity);
					onBankEssenceTaskCreated(new BankEssenceTask(menuOption, getItemName(itemID), itemID, quantityNumeric));
				}
				catch (NumberFormatException nfe)
				{
					// The quantity mode was either -X, -All, -All-but-1
					// Lets handle the easy ones first All/but-1
					if (!quantity.equals("x"))
					{
						switch (quantity)
						{
							case "all":
								quantityNumeric = totalItemAmount;
								break;
							case "all-but-1":
								quantityNumeric = totalItemAmount - 1;
								break;
							default:
								quantityNumeric = -1;
								log.debug("Unknown {} quantity {}", menuOption, quantity);
								break;
						}
						onBankEssenceTaskCreated(new BankEssenceTask(menuOption, getItemName(itemID), itemID, quantityNumeric));
					}
					else
					{
						// The player selected to Deposit/Withdraw-X so we need to wait for the user to submit a value
						quantityNumeric = -1;
						bankedXInput = true;
						bankEssenceTask = new BankEssenceTask(menuOption, getItemName(itemID), itemID, quantityNumeric);
					}
				}
			}
			EssencePouches pouchType = EssencePouches.getPouch(menuOptionClicked.getItemId());
			if (pouchType != null && (menuOption.equals("fill") || menuOption.equals("empty")))
			{
				PouchActionTask pouchTask = new PouchActionTask(pouchType, menuOption);
				onPouchActionCreated(new PouchActionCreated(pouchTask));
//				pouchQueue.add(pouchTask);
//				log.debug("Adding task \"{}\" to the queue", pouchTask);
			}
		}
	}

	public void onPouchActionCreated(PouchActionCreated createdPouchAction)
	{
		blockUpdate = true;
		PouchActionTask pouchAction = createdPouchAction.getPouchActionTask();
		log.debug("New Pouch Action Received: {}", pouchAction);

		// List of possible tasks:
		// 1. Fill pouch
		// What could happen when we try to fill a pouch?
		//  ✓a. There is enough essence in the inventory to fill the pouch => Fill the pouch
		//  ✓b. There is not enough essence in the inventory to fill the pouch => Fill the pouch with the max amount of essence in the inventory
		//  ✓c. We don't have any essence in the inventory => Do nothing
		//    ✓c1. EDGE CASE (Tick race-case): The event inventory change has the wrong # of items removed (added to pouch) due to multiple actions per tick.
		//                                    This could mean that in this tick, 9 essence was removed despite filling the small, medium, and large pouches.
		//                                    Usually, there would be another event that would have the change with the other 3 essence removed. Although it could not be the case.
		//                                    There are two ways to handle this: manually use the previous inventory state or just keep the queue and wait for the next event.
		//  ✓d. The pouch is already full so we can't fill it => Do nothing
		//  ✓e. The pouch just degraded => Fill it with the max amount of essence (Handled now via EssencePouch#getMaximumCapacity via EssencePouch#fill)
		// 2. Empty pouch
		// What could happen when we try to empty a pouch?
		//  ✓a. The pouch is empty => Do nothing
		//  ✓b. The pouch is not empty => Empty the pouch
		//  ✓c. The pouch is not empty but inventory doesn't have enough space => Empty the pouch with the max amount of space in the inventory
		//  ✓d. The pouch is not empty but inventory is full => Do nothing

		EssencePouch pouch = pouches.get(pouchAction.getPouchType());
		if (pouch == null)
		{
			log.debug("Pouch {} not found in pouches map so can't do {}", pouchAction.getPouchType(), pouchAction.getAction());
			blockUpdate = false;
			return;
		}

		log.debug("[Inventory Data] Before | Essence in inventory: {}, Free slots: {}, Used slots: {}", essenceInInventory, inventoryFreeSlots, inventoryUsedSlots);

		if (pouchAction.getAction() == PouchActionTask.PouchAction.FILL)
		{
			// First check if the pouch is already full
			if (pouch.isFilled())
			{
				log.debug("{} is already full so can't fill it", pouchAction.getPouchType());
				blockUpdate = false;
				return;
			}
			// Now check to see if there's even essence in the inventory
			if (essenceInInventory == 0)
			{
				log.debug("No essence in the inventory to fill the {}", pouchAction.getPouchType());
				blockUpdate = false;
				return;
			}

			// We meet all the conditions required to fill the pouch with essence (we have essence and the pouch isn't full)
			int essencePutIntoThePouch = pouch.fill(essenceInInventory);
			log.debug("Added {} essence to the pouch for a total of {}/{}", essencePutIntoThePouch, pouch.getStoredEssence(), pouch.getMaximumCapacity());
			essenceInInventory -= essencePutIntoThePouch;
			inventoryUsedSlots -= essencePutIntoThePouch;
			inventoryFreeSlots += essencePutIntoThePouch;
			updatePreviousInventoryDetails();
		}
		else
		{
			// First check if the pouch is empty
			if (pouch.isEmpty())
			{
				log.debug("{} is already empty so can't empty it", pouchAction.getPouchType());
				blockUpdate = false;
				return;
			}
			// Now check to see if there's even space in the inventory
			if (inventoryFreeSlots == 0)
			{
				log.debug("No space in the inventory to empty the {}", pouchAction.getPouchType());
				blockUpdate = false;
				return;
			}
			// We meet all the conditions required to empty the pouch into the inventory (we have space to empty some if not all, and the pouch isn't empty)
			// Find out how much essence we can take out of the pouch
			int essenceToEmpty = Math.min(inventoryFreeSlots, pouch.getStoredEssence());
			pouch.empty(essenceToEmpty);
			log.debug("Removed {} essence from the pouch for a total of {}/{}", essenceToEmpty, pouch.getStoredEssence(), pouch.getMaximumCapacity());
			essenceInInventory += essenceToEmpty;
			inventoryUsedSlots += essenceToEmpty;
			inventoryFreeSlots -= essenceToEmpty;
			updatePreviousInventoryDetails();
		}
		log.debug("[Inventory Data] After | Essence in inventory: {}, Free slots: {}, Used slots: {}", essenceInInventory, inventoryFreeSlots, inventoryUsedSlots);
//		blockUpdate = false;
	}

	public void onBankEssenceTaskCreated(BankEssenceTask createdBankEssenceTask)
	{
		blockUpdate = true;
		log.debug("Bank Essence Task Created: {}", createdBankEssenceTask);
		log.debug("[Inventory Data] Before | Essence in inventory: {}, Free slots: {}, Used slots: {}", essenceInInventory, inventoryFreeSlots, inventoryUsedSlots);
		// Update the inventory manually in case of tick race conditions when banking
		if (createdBankEssenceTask.getAction() == BankEssenceTask.BankEssenceAction.DEPOSIT)
		{
			// Depositing the essence from the players inventory into the bank
			// Don't worry about the quantity being invalid because this was calculated in onMenuOptionClicked
			essenceInInventory -= createdBankEssenceTask.getQuantity();
			inventoryUsedSlots -= createdBankEssenceTask.getQuantity();
			inventoryFreeSlots += createdBankEssenceTask.getQuantity();
			updatePreviousInventoryDetails();
		}
		else
		{
			// Withdrawing the essence from the players bank into their inventory
			// Remember to check the quantity because the quantity was assigned by the # of the item we have in the bank
			int maximumEssenceAvailableToWithdraw = Math.min(createdBankEssenceTask.getQuantity(), inventoryFreeSlots);
			essenceInInventory += maximumEssenceAvailableToWithdraw;
			inventoryUsedSlots += maximumEssenceAvailableToWithdraw;
			inventoryFreeSlots -= maximumEssenceAvailableToWithdraw;
			updatePreviousInventoryDetails();
		}
		log.debug("[Inventory Data] After | Essence in inventory: {}, Free slots: {}, Used slots: {}", essenceInInventory, inventoryFreeSlots, inventoryUsedSlots);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", createdBankEssenceTask.toString(), null);
		blockUpdate = true;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged itemContainerChanged)
	{
		log.debug("onItemContainerChanged ({}): " + client.getTickCount(), itemContainerChanged.getContainerId());
		// Make sure we're only focusing on the inventory
		if (itemContainerChanged.getContainerId() == InventoryID.INVENTORY.getId())
		{
			currentInventoryItems.clear();
			List<Item> itemStream = Arrays.stream(itemContainerChanged.getItemContainer().getItems()).filter(this.filterNullItemsPredicate()).collect(Collectors.toList());
			itemStream.forEach(item -> currentInventoryItems.add(item.getId(), itemManager.getItemComposition(item.getId()).isStackable() ? 1 : item.getQuantity()));
			if (!blockUpdate)
			{
				essenceInInventory = 0;
				updatePreviousInventoryDetails();
				itemStream.stream().filter(item -> this.isValidEssencePouchItem(item.getId())).forEach(item -> essenceInInventory++);
				inventoryUsedSlots = currentInventoryItems.size();
				inventoryFreeSlots = 28 - inventoryUsedSlots;
				log.debug("[Inventory Data] Updated Inventory | Essence in inventory: {}->{}, Free slots: {}->{}, Used slots: {}->{}",
					previousEssenceInInventory, essenceInInventory,
					previousInventoryFreeSlots, inventoryFreeSlots,
					previousInventoryUsedSlots, inventoryUsedSlots
				);
			}
			else
			{
				log.debug("onItemContainerChanged (Inventory) blocked");
				blockUpdate = false;
			}

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
			log.debug("Used Slots: " + usedInventorySlots);
			log.debug("Free Slots: " + freeSlots);

			Map<EssencePouches, Boolean> justDegradedPouches = new HashMap<>();
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
						justDegradedPouches.put(currentPouch.getPouchType(), true);
						// Re-run the action to update the pouch's state
						restorePreviousInventoryDetails();
						PouchActionTask pouchFilLEmpty = new PouchActionTask(currentPouch.getPouchType(), "Empty");
						PouchActionTask pouchFilLAction = new PouchActionTask(currentPouch.getPouchType(), "Fill");
						onPouchActionCreated(new PouchActionCreated(pouchFilLEmpty));
						onPouchActionCreated(new PouchActionCreated(pouchFilLAction));
					}
				}
			}
			previousInventory = currentInventory;
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
//		if (blockUpdate) {
//			blockUpdate = false;
//		}
//		log.debug("{}", blockUpdate);
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
		log.debug("Closed Widget {}", widgetClosed);
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

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		/*if (false && bankEssenceTask != null && varbitChanged.getVarbitId() == Varbits.BANK_REQUESTEDQUANTITY)
		{
			bankEssenceTask.setQuantity(varbitChanged.getValue());
			onBankEssenceTaskCreated(bankEssenceTask);
			bankEssenceTask = null;
		}*/
	}

	@Subscribe
	public void onChatMessage(ChatMessage receivedChatMessage)
	{
		// If the message was sent in the public chat by a player named "Nefarious" then log "hello world"
		if (receivedChatMessage.getType() == ChatMessageType.PUBLICCHAT && receivedChatMessage.getName().equalsIgnoreCase(client.getLocalPlayer().getName()))
		{
			String message = receivedChatMessage.getMessage().toLowerCase();
			switch (message)
			{
				case "!hello":
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Hello, world!", null);
					break;
				case "!fill s":
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.SMALL}, 3);
					this.fakeItemContainerChanged(-1, -1, -1);
					break;
				case "!fill m":
					break;
				case "!fill l":
					break;
				case "!fill g":
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 3);
					this.fakeItemContainerChanged(-1, -1, -1);
					break;
				case "!empty s":
					this.fakeEmptyEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 3);
					this.fakeItemContainerChanged(-1, -1, -1);
					break;
				case "!empty m":
					break;
				case "!empty l":
					break;
				case "!empty g":
					break;
				case "!cts":
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 12);
					this.fakeEmptyEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 12);
					this.fakeItemContainerChanged(-1, -1, -1);
					for (EssencePouch pouch : pouches.values())
					{
						log.debug("{}", pouch);
					}
					break;
				case "!ct":
					int essence = 21;
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.SMALL}, essence);
					essence -= EssencePouches.SMALL.getMaxCapacity();
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.MEDIUM}, essence);
					essence -= EssencePouches.MEDIUM.getMaxCapacity();
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.LARGE}, essence);
					essence -= EssencePouches.LARGE.getMaxCapacity();
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, essence);
					this.fakeEmptyEssencePouch(new EssencePouches[]{EssencePouches.SMALL}, 12);
					this.fakeEmptyEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 12);
					this.fakeEmptyEssencePouch(new EssencePouches[]{EssencePouches.LARGE}, 12);
					this.fakeEmptyEssencePouch(new EssencePouches[]{EssencePouches.MEDIUM}, 12);
					this.fakeItemContainerChanged(-1, -1, -1);
					for (EssencePouch pouch : pouches.values())
					{
						log.debug("{}", pouch);
					}
					break;
				case "!repair":
					for (EssencePouch pouch : pouches.values())
					{
						pouch.repairPouch();
						log.debug("{}", pouch);
					}
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Pouches have been repaired", null);
					break;
				case "!reset":
					for (EssencePouch pouch : pouches.values())
					{
						pouch.repairPouch();
						pouch.setStoredEssence(0);
						log.debug("{}", pouch);
					}
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Pouches have been repaired & reset", null);
				case "!d":
					log.debug("Examine option clicked");
					log.debug("{}", pouches.values());
					log.debug("{}", pouchQueue);
					log.debug("[Inventory Data] Inventory | Essence in inventory: {}->{}, Free slots: {}->{}, Used slots: {}->{}",
						previousEssenceInInventory, essenceInInventory,
						previousInventoryFreeSlots, inventoryFreeSlots,
						previousInventoryUsedSlots, inventoryUsedSlots
					);
					break;
				default:
					break;
			}
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired postFiredScript)
	{
		/*if (bankedXInput && postFiredScript.getScriptId() == ScriptID.MESSAGE_LAYER_CLOSE)
		{
			log.debug("Message input was closed");
			bankedXInput = false;
			client.va
		}*/

		// 681 = Input Dialog Enter Pressed (By then, the input text is set back to empty string ""
		// 212 Seems to be for any input dialog as it contains cs2 code for handling "k,m,b" inputs
		if (bankEssenceTask != null && postFiredScript.getScriptId() == 212)
		{
			try
			{
				int quantity = Integer.parseInt(client.getVarcStrValue(VarClientStr.INPUT_TEXT));
				bankEssenceTask.setQuantity(quantity);
				onBankEssenceTaskCreated(bankEssenceTask);
			}
			catch (NumberFormatException nfe)
			{
				log.debug("The input dialogue was not an integer for some reason \"{}\"", client.getVarcStrValue(VarClientStr.INPUT_TEXT));
			}
			bankEssenceTask = null;
		}
	}

	private String inputDialogText;

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged varClientStrChanged)
	{
		if (varClientStrChanged.getIndex() == VarClientStr.INPUT_TEXT)
		{
			inputDialogText = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
			log.debug("Input changed: {}", inputDialogText);
		}
	}

	private void fakeFillEssencePouch(EssencePouches[] essencePouches, int essenceInInventory)
	{
		for (EssencePouches pouchType : essencePouches)
		{
			if (pouches.containsKey(pouchType))
			{
				// MenuOptionClicked(getParam0=0, getParam1=9764864, getMenuOption=Fill, getMenuTarget=<col=ff9040>Small pouch</col>, getMenuAction=CC_OP, getId=2)
				MenuEntry fillMenuOption = client.getMenu().createMenuEntry(-1);
				fillMenuOption.setParam0(0);
				fillMenuOption.setParam1(9764864);
				fillMenuOption.setOption("Fill");
				fillMenuOption.setTarget("<col=ff9040>" + pouchType.getName() + "</col>");
				fillMenuOption.setItemId(pouchType.getItemID());
				fillMenuOption.setType(MenuAction.CC_OP);
				fillMenuOption.setIdentifier(2);
				this.onMenuOptionClicked(new MenuOptionClicked(fillMenuOption));
			}
		}
	}

	private void fakeEmptyEssencePouch(EssencePouches[] pouchTypes, int essenceToRemove)
	{
		for (EssencePouches pouchType : pouchTypes)
		{
			if (pouches.containsKey(pouchType))
			{
				// MenuOptionClicked(getParam0=0, getParam1=9764864, getMenuOption=Empty, getMenuTarget=<col=ff9040>Small pouch</col>, getMenuAction=CC_OP, getId=3)
				MenuEntry fillMenuOption = client.getMenu().createMenuEntry(-1);
				fillMenuOption.setParam0(0);
				fillMenuOption.setParam1(9764864);
				fillMenuOption.setOption("Empty");
				fillMenuOption.setTarget("<col=ff9040>" + pouchType.getName() + "</col>");
				fillMenuOption.setItemId(pouchType.getItemID());
				fillMenuOption.setType(MenuAction.CC_OP);
				fillMenuOption.setIdentifier(3);
				this.onMenuOptionClicked(new MenuOptionClicked(fillMenuOption));
			}
		}
	}

	private void fakeItemContainerChanged(int fakeNumberOfEssence, int fakeNumberOfUsedSlots, int fakeNumberOfFreeSlots)
	{
		ItemContainerChanged itemContainerChanged = new ItemContainerChanged(InventoryID.INVENTORY.getId(), client.getItemContainer(InventoryID.INVENTORY.getId()));
		this.onItemContainerChanged(itemContainerChanged);
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

	private ItemContainer getInventoryContainer()
	{
		return client.getItemContainer(InventoryID.INVENTORY.getId());
	}

	private Predicate<Item> filterNullItemsPredicate()
	{
		return item -> item.getId() != -1;
	}

	private void updatePreviousInventoryDetails()
	{
		previousEssenceInInventory = essenceInInventory;
		previousInventoryFreeSlots = inventoryFreeSlots;
		previousInventoryUsedSlots = inventoryUsedSlots;
	}

	private void restorePreviousInventoryDetails()
	{
		essenceInInventory = previousEssenceInInventory;
		inventoryFreeSlots = previousInventoryFreeSlots;
		inventoryUsedSlots = previousInventoryUsedSlots;
	}

	private String getItemName(int itemID)
	{
		return itemManager.getItemComposition(itemID).getName();
	}
}
