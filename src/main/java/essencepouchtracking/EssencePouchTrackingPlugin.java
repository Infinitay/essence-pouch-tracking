package essencepouchtracking;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.collect.Queues;
import com.google.gson.Gson;
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
import net.runelite.api.GameState;
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
	private Gson gson;

	@Inject
	private EssencePouchTrackingOverlay overlay;

	private EssencePouchTrackingState trackingState;

	@Getter
	private final Map<EssencePouches, EssencePouch> pouches = new HashMap<>();
	private final Deque<PouchActionTask> pouchTaskQueue = Queues.newArrayDeque();
	private final Deque<EssencePouch> checkedPouchQueue = Queues.newArrayDeque();
	private Multiset<Integer> previousInventory = HashMultiset.create();

	private boolean isRepairDialogue;

	private Multiset<Integer> currentInventoryItems = HashMultiset.create();
	private int previousEssenceInInventory, essenceInInventory;
	private int previousInventoryFreeSlots, inventoryFreeSlots;
	private int previousInventoryUsedSlots, inventoryUsedSlots;
	private int pauseUntilTick;

	@Provides
	EssencePouchTrackingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EssencePouchTrackingConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		this.overlayManager.add(overlay);
		// Load the tracking state
		this.loadTrackingState();
	}

	@Override
	protected void shutDown() throws Exception
	{
		this.saveTrackingState();
		this.previousInventory.clear();
		this.pouches.clear();
		this.pouchTaskQueue.clear();
		this.checkedPouchQueue.clear();
		this.currentInventoryItems.clear();
		this.previousEssenceInInventory = this.essenceInInventory = 0;
		this.previousInventoryFreeSlots = this.inventoryFreeSlots = 0;
		this.previousInventoryUsedSlots = this.inventoryUsedSlots = 0;
		this.overlayManager.remove(overlay);
		this.pauseUntilTick = 0;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged changedConfig)
	{
		if (changedConfig.getGroup().equals(EssencePouchTrackingConfig.GROUP))
		{
			// log.debug("Config changed: {}", changedConfig);
		}
	}

	@Override
	public void resetConfiguration()
	{
		log.debug("Resetting tracking state");
		this.trackingState = new EssencePouchTrackingState();
		for (EssencePouch pouch : pouches.values())
		{
			this.updatePouchFromState(pouch);
		}
		this.saveTrackingState();
		this.pouchTaskQueue.clear();
		this.checkedPouchQueue.clear();
		super.resetConfiguration();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		// Handle loading the previous essence pouch state here instead of onStartup because profile key there is null unless we logged in before in the current play session
		if (gameStateChanged.getGameState().equals(GameState.LOGGED_IN))
		{
			// Get the tracking state
			this.loadTrackingState();
		}
	}

	private BankEssenceTask bankEssenceTask;

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked menuOptionClicked)
	{
		log.debug("onMenuOptionClicked: " + this.client.getTickCount());
		log.debug("{}", menuOptionClicked);

		//TODO All essence is removed from a pouch when it is dropped

		// Keep in mind that the current inventory will be updated after this event so if the event is fill now and you have 10 essence in your inventory, the inventory will be updated to 0 after this event
//		ItemContainer currentInventoryContainer = this.getInventoryContainer();

		if (menuOptionClicked.getMenuAction().equals(MenuAction.CC_OP) || menuOptionClicked.getMenuAction().equals(MenuAction.CC_OP_LOW_PRIORITY))
		{
			String menuOption = menuOptionClicked.getMenuOption().toLowerCase();
			// Handle withdrawing essence from the bank in case someone one-tick's it (or faster)
			// First check to see if the bank is open
			if (this.client.getItemContainer(InventoryID.BANK) != null && (menuOption.startsWith("deposit") || menuOption.startsWith("withdraw")))
			{
				int itemID = menuOptionClicked.getItemId();
				String quantity = menuOption.substring(menuOption.indexOf("-") + 1);
				menuOption = menuOption.substring(0, menuOption.indexOf("-"));

				Widget itemWidget = menuOptionClicked.getWidget();
				if (itemWidget == null || !isValidEssencePouchItem(itemID))
				{
					return;
				}
				log.debug("{} {} x{}", menuOption, itemID, quantity);
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
					totalItemAmount = this.essenceInInventory;
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
						this.bankEssenceTask = new BankEssenceTask(menuOption, getItemName(itemID), itemID, quantityNumeric);
					}
				}
			}
			EssencePouches pouchType = EssencePouches.getPouch(menuOptionClicked.getItemId());
			if (pouchType != null)
			{
				if (menuOption.equals("fill") || menuOption.equals("empty"))
				{
					PouchActionTask pouchTask = new PouchActionTask(pouchType, menuOption);
					boolean wasActionSuccessful = onPouchActionCreated(new PouchActionCreated(pouchTask));
					this.pouchTaskQueue.add(pouchTask);
					log.debug("Added {} task to queue: {}", pouchTask, this.pouchTaskQueue);
				}
				else if (menuOption.equals("check"))
				{
					this.checkedPouchQueue.add(this.pouches.get(pouchType));
					this.pauseUntilTick = this.client.getTickCount() + 1;
				}
			}
		}
	}

	public boolean onPouchActionCreated(PouchActionCreated createdPouchAction)
	{
		this.pauseUntilTick = this.client.getTickCount() + 1;
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

		EssencePouch pouch = this.pouches.get(pouchAction.getPouchType());
		if (pouch == null)
		{
			log.debug("Pouch {} not found in pouches map so can't do {}", pouchAction.getPouchType(), pouchAction.getAction());
			return false;
		}

		log.debug("[Inventory Data] Before | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);

		if (pouchAction.getAction().equals(PouchActionTask.PouchAction.FILL))
		{
			// First check if the pouch is already full
			if (pouch.isFilled())
			{
				log.debug("{} is already full so can't fill it", pouchAction.getPouchType());
				return false;
			}
			// Now check to see if there's even essence in the inventory
			if (this.essenceInInventory == 0)
			{
				log.debug("No essence in the inventory to fill the {}", pouchAction.getPouchType());
				return false;
			}

			// We meet all the conditions required to fill the pouch with essence (we have essence and the pouch isn't full)
			int essencePutIntoThePouch = pouch.fill(this.essenceInInventory);
			log.debug("Added {} essence to the pouch for a total of {}/{}", essencePutIntoThePouch, pouch.getStoredEssence(), pouch.getMaximumCapacity());
			this.essenceInInventory -= essencePutIntoThePouch;
			this.inventoryUsedSlots -= essencePutIntoThePouch;
			this.inventoryFreeSlots += essencePutIntoThePouch;
			this.updatePreviousInventoryDetails();
		}
		else
		{
			// First check if the pouch is empty
			if (pouch.isEmpty())
			{
				log.debug("{} is already empty so can't empty it", pouchAction.getPouchType());
				return false;
			}
			// Now check to see if there's even space in the inventory
			if (this.inventoryFreeSlots == 0)
			{
				log.debug("No space in the inventory to empty the {}", pouchAction.getPouchType());
				return false;
			}
			// We meet all the conditions required to empty the pouch into the inventory (we have space to empty some if not all, and the pouch isn't empty)
			// Find out how much essence we can take out of the pouch
			int essenceEmptied = pouch.empty(this.inventoryFreeSlots);
			log.debug("Removed {} essence from the pouch for a total of {}/{}", essenceEmptied, pouch.getStoredEssence(), pouch.getMaximumCapacity());
			this.essenceInInventory += essenceEmptied;
			this.inventoryUsedSlots += essenceEmptied;
			this.inventoryFreeSlots -= essenceEmptied;
			this.updatePreviousInventoryDetails();
		}
		log.debug("[Inventory Data] After | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);
		// blockUpdate = false;
		this.saveTrackingState();
		return true;
	}

	public void onBankEssenceTaskCreated(BankEssenceTask createdBankEssenceTask)
	{
		this.pauseUntilTick = this.client.getTickCount() + 1;
		log.debug("[Inventory Data] Before | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);
		// Update the inventory manually in case of tick race conditions when banking
		if (createdBankEssenceTask.getAction().equals(BankEssenceTask.BankEssenceAction.DEPOSIT))
		{
			// Depositing the essence from the players inventory into the bank
			// Don't worry about the quantity being invalid because this was calculated in onMenuOptionClicked
			this.essenceInInventory -= createdBankEssenceTask.getQuantity();
			this.inventoryUsedSlots -= createdBankEssenceTask.getQuantity();
			this.inventoryFreeSlots += createdBankEssenceTask.getQuantity();
			this.updatePreviousInventoryDetails();
		}
		else
		{
			// Withdrawing the essence from the players bank into their inventory
			// Remember to check the quantity because the quantity was assigned by the # of the item we have in the bank
			int maximumEssenceAvailableToWithdraw = Math.min(createdBankEssenceTask.getQuantity(), this.inventoryFreeSlots);
			this.essenceInInventory += maximumEssenceAvailableToWithdraw;
			this.inventoryUsedSlots += maximumEssenceAvailableToWithdraw;
			this.inventoryFreeSlots -= maximumEssenceAvailableToWithdraw;
			this.updatePreviousInventoryDetails();
		}
		log.debug("[Inventory Data] After | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged itemContainerChanged)
	{
		// Make sure we're only focusing on the inventory
		if (itemContainerChanged.getContainerId() == InventoryID.INVENTORY.getId())
		{
			this.currentInventoryItems.clear();
			List<Item> itemStream = Arrays.stream(itemContainerChanged.getItemContainer().getItems()).filter(this.filterNullItemsPredicate()).collect(Collectors.toList());
			itemStream.forEach(item -> this.currentInventoryItems.add(item.getId(), this.itemManager.getItemComposition(item.getId()).isStackable() ? 1 : item.getQuantity()));
			if (this.pauseUntilTick == -1 || this.client.getTickCount() >= this.pauseUntilTick)
			{
				this.essenceInInventory = 0;
				updatePreviousInventoryDetails();
				itemStream.stream().filter(item -> this.isValidEssencePouchItem(item.getId())).forEach(item -> this.essenceInInventory++);
				this.inventoryUsedSlots = this.currentInventoryItems.size();
				this.inventoryFreeSlots = 28 - this.inventoryUsedSlots;
				log.debug("[Inventory Data] Updated Inventory | Essence in inventory: {}->{}, Free slots: {}->{}, Used slots: {}->{}",
					this.previousEssenceInInventory, this.essenceInInventory,
					this.previousInventoryFreeSlots, this.inventoryFreeSlots,
					this.previousInventoryUsedSlots, this.inventoryUsedSlots
				);
			}
			else
			{
				log.debug("[Inventory Data] Blocked updating the inventory");
			}

			Multiset<Integer> currentInventory = HashMultiset.create();
			List<Item> inventoryItems = Arrays.stream(itemContainerChanged.getItemContainer().getItems()).filter(item -> item.getId() != -1).collect(Collectors.toList());
			inventoryItems.forEach(item -> currentInventory.add(item.getId(), item.getQuantity()));

			// Remember that for set operations difference A - B != B - A
			Multiset<Integer> addedItems = Multisets.difference(currentInventory, this.previousInventory);
			Multiset<Integer> removedItems = Multisets.difference(this.previousInventory, currentInventory);
			log.debug("Added Items: " + addedItems);
			log.debug("Removed Items: " + removedItems);

			// Now that we've handling inventory state changes, we can handle the pouches
			// First check if we have any pouches in the inventory in case this is the user's first run or some other issue
			for (int itemId : addedItems)
			{
				EssencePouch pouch = EssencePouches.createPouch(itemId);
				if (pouch != null && !this.pouches.containsKey(pouch.getPouchType()))
				{
					this.pouches.put(pouch.getPouchType(), pouch);
					this.updatePouchFromState(pouch);
				}

				// Check to see if pouch has degraded
				if (pouch != null && this.pouches.containsKey(pouch.getPouchType()))
				{
					EssencePouch currentPouch = this.pouches.get(pouch.getPouchType());
					if (currentPouch != null && currentPouch.getPouchType().getDegradedItemID() == itemId)
					{
						currentPouch.setDegraded(true);
						log.debug("{} has degraded", currentPouch.getPouchType().getName());
						// Re-run the action to update the pouch's state
						this.handPouchActionsPostDegrade(currentPouch);
					}
				}
			}
			this.previousInventory = currentInventory;
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (this.pauseUntilTick != -1 && this.client.getTickCount() > this.pauseUntilTick)
		{
			this.pouchTaskQueue.clear();
			this.checkedPouchQueue.clear();
			this.pauseUntilTick = -1;
		}
		//TODO If there is no repair option then that means no pouch has decayed
		if (this.isRepairDialogue)
		{
			boolean repairedPouches = false;

			Widget dialogNPCHeadModel = this.client.getWidget(ComponentID.DIALOG_NPC_HEAD_MODEL);
			if (dialogNPCHeadModel != null && dialogNPCHeadModel.getModelId() == NpcID.DARK_MAGE)
			{
				Widget dialogText = this.client.getWidget(ComponentID.DIALOG_NPC_TEXT);
				if (dialogText != null && dialogText.getText().equals("Fine. A simple transfiguration spell should resolve things<br>for you."))
				{
					repairedPouches = true;
				}
			}

			Widget dialogOptionsWidget = this.client.getWidget(ComponentID.DIALOG_OPTION_OPTIONS);
			if (dialogOptionsWidget != null && dialogOptionsWidget.getChildren() != null)
			{
				List<String> options = Arrays.stream(dialogOptionsWidget.getChildren()).filter(Objects::nonNull).map(Widget::getText).collect(Collectors.toList());
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
				this.pouches.values().forEach(EssencePouch::repairPouch);
				log.debug("{}", this.pouches.values());
				this.isRepairDialogue = false;
				this.saveTrackingState();
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
				this.isRepairDialogue = false;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
		switch (widgetLoaded.getGroupId())
		{
			case InterfaceID.DIALOG_NPC:
			case InterfaceID.DIALOG_OPTION:
				this.isRepairDialogue = true;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		// Bank's custom withdraw/deposit is set with a Varbit
		// We can also tell whether a pouch is fully filled with a VarPlayer
		// TODO Use essence pouch VarPlayer to set pouch state when it's unknown, or to verify and update state if need-be

		if (varbitChanged.getVarbitId() == 13688 && varbitChanged.getValue() == 0)
		{
			// Clear pouches if a new GOTR game has started
			this.pouches.values().forEach(EssencePouch::empty);
			this.saveTrackingState();
		}
		else if (varbitChanged.getVarbitId() == 13691 && varbitChanged.getValue() == 0)
		{
			// Clear pouches if a player leaves the GOTR portal
			this.pouches.values().forEach(EssencePouch::empty);
			this.saveTrackingState();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage receivedChatMessage)
	{
		String message = receivedChatMessage.getMessage().toLowerCase();
		if (receivedChatMessage.getType().equals(ChatMessageType.GAMEMESSAGE)
			&& (message.endsWith("essences in this pouch.") || message.endsWith("essence in this pouch."))
		)
		{
			int numberOfEssence = EssencePouches.checkEssenceStringToInt(message);
			EssencePouch pouch = this.checkedPouchQueue.poll();
			if (pouch != null)
			{
				int previousStoredEssence = pouch.getStoredEssence();
				int difference = numberOfEssence - previousStoredEssence;

				// If the difference is negative, then essence was removed from the pouch at some point
				// If the difference is positive, then essence was added to the pouch at some point
				// At this point, we could either set the EssencePouch#setUnknownDecay to true because we don't know if the user continued to fill the pouch or not
				// However, there's no need to complicate things further. Although it's hypocritical in this case, let's not assume that the user had more than one fill action that resulted in the current state
				log.debug("Pouch {} previously had a state with {} stored essence, now has {} stored essence", pouch.getPouchType().getName(), previousStoredEssence, numberOfEssence);
				if (difference > 0)
				{
					pouch.fill(difference);
				}
				else if (difference < 0)
				{
					pouch.empty(-difference);
				}
				else
				{
					// Difference is 0, so nothing has changed
					pouch.setUnknownStored(false);
					pouch.setStoredEssence(numberOfEssence);
				}
			}
			else
			{
				log.debug("Received a check pouch count message, but there was no more pouches in the queue.");
			}
		}

		// Manually set this variable to true to help with debugging. I won't be removing all of this spaghetti in case I need to debug something in the future related with ticks or state
		boolean isDevMode = false;
		if (!isDevMode)
		{
			return;
		}
		if (receivedChatMessage.getType().equals(ChatMessageType.PUBLICCHAT) && receivedChatMessage.getName().equalsIgnoreCase(this.client.getLocalPlayer().getName()))
		{
			switch (message)
			{
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
					for (EssencePouch pouch : this.pouches.values())
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
					for (EssencePouch pouch : this.pouches.values())
					{
						log.debug("{}", pouch);
					}
					break;
				case "!repair":
					for (EssencePouch pouch : this.pouches.values())
					{
						pouch.repairPouch();
						log.debug("{}", pouch);
					}
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Pouches have been repaired", null);
					break;
				case "!reset":
					for (EssencePouch pouch : this.pouches.values())
					{
						pouch.repairPouch();
						pouch.setStoredEssence(0);
						log.debug("{}", pouch);
					}
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Pouches have been repaired & reset", null);
				case "!d":
					log.debug("{}", this.pouches.values());
					log.debug("{}", this.pouchTaskQueue);
					log.debug("[Inventory Data] Inventory | Essence in inventory: {}->{}, Free slots: {}->{}, Used slots: {}->{}",
						this.previousEssenceInInventory, this.essenceInInventory,
						this.previousInventoryFreeSlots, this.inventoryFreeSlots,
						this.previousInventoryUsedSlots, this.inventoryUsedSlots
					);
					break;
				case "!ts":
					log.debug("{}", this.trackingState);
					break;
				case "!c":
					log.debug("{}", this.configManager.getConfigurationKeys(EssencePouchTrackingConfig.GROUP));
					break;
				case "!update":
					log.debug("Updating tracking state");
					this.updateTrackingState();
					break;
				case "!load":
					log.debug("LOADED");
					log.debug("{}", this.configManager.getConfiguration(this.config.GROUP, "trackingState"));
					log.debug("{}", this.configManager.getRSProfileConfiguration(this.config.GROUP, "trackingState"));
					break;
				case "!clear":
					this.configManager.unsetConfiguration(this.config.GROUP, "trackingState");
					this.configManager.unsetRSProfileConfiguration(this.config.GROUP, "trackingState");
					break;
				default:
					break;
			}
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired postFiredScript)
	{
		// 681 = Input Dialog Enter Pressed (By then, the input text is set back to empty string ""
		// 212 Seems to be for any input dialog as it contains cs2 code for handling "k,m,b" inputs
		if (this.bankEssenceTask != null && postFiredScript.getScriptId() == 212)
		{
			try
			{
				int quantity = Integer.parseInt(this.client.getVarcStrValue(VarClientStr.INPUT_TEXT));
				this.bankEssenceTask.setQuantity(quantity);
				onBankEssenceTaskCreated(this.bankEssenceTask);
			}
			catch (NumberFormatException nfe)
			{
				log.debug("The input dialogue was not an integer for some reason \"{}\"", this.client.getVarcStrValue(VarClientStr.INPUT_TEXT));
			}
			this.bankEssenceTask = null;
		}
	}

	private String inputDialogText;

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged varClientStrChanged)
	{
		if (varClientStrChanged.getIndex() == VarClientStr.INPUT_TEXT)
		{
			this.inputDialogText = this.client.getVarcStrValue(VarClientStr.INPUT_TEXT);
		}
	}

	private void fakeFillEssencePouch(EssencePouches[] essencePouches, int essenceInInventory)
	{
		for (EssencePouches pouchType : essencePouches)
		{
			if (this.pouches.containsKey(pouchType))
			{
				// MenuOptionClicked(getParam0=0, getParam1=9764864, getMenuOption=Fill, getMenuTarget=<col=ff9040>Small pouch</col>, getMenuAction=CC_OP, getId=2)
				MenuEntry fillMenuOption = this.client.getMenu().createMenuEntry(-1);
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
			if (this.pouches.containsKey(pouchType))
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
		ItemContainerChanged itemContainerChanged = new ItemContainerChanged(InventoryID.INVENTORY.getId(), this.client.getItemContainer(InventoryID.INVENTORY.getId()));
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
		return this.client.getItemContainer(InventoryID.INVENTORY.getId());
	}

	private Predicate<Item> filterNullItemsPredicate()
	{
		return item -> item.getId() != -1;
	}

	private void updatePreviousInventoryDetails()
	{
		this.previousEssenceInInventory = this.essenceInInventory;
		this.previousInventoryFreeSlots = this.inventoryFreeSlots;
		this.previousInventoryUsedSlots = this.inventoryUsedSlots;
	}

	private void restorePreviousInventoryDetails()
	{
		this.essenceInInventory = this.previousEssenceInInventory;
		this.inventoryFreeSlots = this.previousInventoryFreeSlots;
		this.inventoryUsedSlots = this.previousInventoryUsedSlots;
	}

	private void handPouchActionsPostDegrade(EssencePouch degradedPouch)
	{
		// Okay so the pouch just degraded. Lets undo the decay amount on the degraded pouch and then handle the filling again
		PouchActionTask degradedPouchTask = this.pouchTaskQueue.stream().filter(task -> task.getPouchType().equals(degradedPouch.getPouchType())).findFirst().orElse(null);
		if (degradedPouchTask == null)
		{
			log.debug("For some reason, we couldn't find a corresponding degraded pouch task: {}", this.pouchTaskQueue);
		}

		// Loop through the task queue to get the last actions and restore their counts
		// Clone the queue so we can modify it as we'll need to re-use the queue to re-run the actions after degrading
		Deque<PouchActionTask> tempActionQueue = Queues.newArrayDeque();
		this.pouchTaskQueue.forEach(tempTask -> tempActionQueue.add(new PouchActionTask(tempTask)));
		log.debug("Before Temp Pouch Task Queue: {}", tempActionQueue);

		// Check to see if we even have any other tasks to fix otherwise we're finished
		if (tempActionQueue.isEmpty())
		{
			return;
		}

		PouchActionTask task;
		// Filter out the actions until we remove the degraded pouch
		while (!(task = tempActionQueue.poll()).getPouchType().equals(degradedPouch.getPouchType()))
		{
			// It should always* be FILL because the pouch that degraded was last filled. However just in case, handle empty case.
			if (degradedPouchTask.getAction().equals(PouchActionTask.PouchAction.FILL))
			{
				// Undo the fill
				int numberOfEssenceEmptied = degradedPouch.empty(this.inventoryFreeSlots);
				degradedPouch.setRemainingEssenceBeforeDecay(degradedPouch.getRemainingEssenceBeforeDecay() + numberOfEssenceEmptied);
				log.debug("Re-removing {} essence from the now-degraded pouch for a total of {}/{}", numberOfEssenceEmptied, degradedPouch.getStoredEssence(), degradedPouch.getMaximumCapacity());
				this.essenceInInventory += numberOfEssenceEmptied;
				this.inventoryUsedSlots += numberOfEssenceEmptied;
				this.inventoryFreeSlots -= numberOfEssenceEmptied;
				this.updatePreviousInventoryDetails();
				// Now that the pouch has been emptied out, we can re-fill it
				PouchActionTask pouchFilLAction = new PouchActionTask(degradedPouch.getPouchType(), "Fill");
				onPouchActionCreated(new PouchActionCreated(pouchFilLAction));
			}
			else
			{
				// Undo the empty
				int numberOfEssenceRestored = degradedPouch.fill(this.essenceInInventory);
				degradedPouch.setRemainingEssenceBeforeDecay(degradedPouch.getRemainingEssenceBeforeDecay() - numberOfEssenceRestored);
				log.debug("Restoring {} essence to the pouch for a total of {}/{}", numberOfEssenceRestored, degradedPouch.getStoredEssence(), degradedPouch.getMaximumCapacity());
				this.essenceInInventory -= numberOfEssenceRestored;
				this.inventoryUsedSlots -= numberOfEssenceRestored;
				this.inventoryFreeSlots += numberOfEssenceRestored;
				this.updatePreviousInventoryDetails();
				// Now that the pouch has been re-filled out, we can re-fill it
				PouchActionTask pouchFilLEmpty = new PouchActionTask(degradedPouch.getPouchType(), "Empty");
				onPouchActionCreated(new PouchActionCreated(pouchFilLEmpty));
			}
		}
		tempActionQueue.addFirst(task);
		log.debug("After Temp Pouch Task Queue: {}", tempActionQueue);

		// Now re-do the tasks
		Deque<PouchActionTask> tempOppositeActionQueue = Queues.newArrayDeque();
		tempActionQueue.forEach(tempTask -> tempOppositeActionQueue.add(new PouchActionTask(tempTask)));
		log.debug("Before Opposite Actions Queue: {}", tempOppositeActionQueue);
		while ((task = tempOppositeActionQueue.poll()) != null)
		{
			log.debug("Handling opposite for task: {}", task);
			task.setAction(task.getAction().equals(PouchActionTask.PouchAction.FILL) ? PouchActionTask.PouchAction.EMPTY : PouchActionTask.PouchAction.FILL);
			onPouchActionCreated(new PouchActionCreated(task));
		}
		log.debug("After Opposite Actions Queue: {}", tempOppositeActionQueue);

		while ((task = tempActionQueue.poll()) != null)
		{
			log.debug("Now handling the proper task for task: {}", task);
			onPouchActionCreated(new PouchActionCreated(task));
		}

		log.debug("Finished re-handling pouch actions post degrade");
	}

	private void saveTrackingState()
	{
		// Update the state once more to ensure proper state
		// this.updateTrackingState();
		String serializeStateAsJSON = this.serializeState(this.trackingState);
		this.configManager.setRSProfileConfiguration(this.config.GROUP, "trackingState", serializeStateAsJSON);
		log.debug("Saved the tracking state");
	}

	private void loadTrackingState()
	{

		String trackingStateAsJSONString = this.configManager.getRSProfileConfiguration(this.config.GROUP, "trackingState");
		log.debug("{}", trackingStateAsJSONString);
		EssencePouchTrackingState trackingState = this.deserializeState(trackingStateAsJSONString);
		if (trackingState != null)
		{
			this.trackingState = trackingState;
			log.debug("Loaded tracking state: {}", trackingState);
			for (EssencePouches pouchType : EssencePouches.values())
			{
				this.pouches.put(pouchType, trackingState.getPouch(pouchType));
			}
		}
		else
		{
			log.debug("Unable to load tracking state to the loaded tracking state being null");
			this.updateTrackingState(); // It'll initialize the state
		}
	}

	private void updateTrackingState()
	{
		if (this.trackingState != null)
		{
			for (EssencePouch pouch : this.pouches.values())
			{
				this.trackingState.setPouch(pouch);
				log.debug("Updated tracking state for {} ({} stored, {} until decay)", pouch.getPouchType(), pouch.getStoredEssence(), pouch.getRemainingEssenceBeforeDecay());
			}
			log.debug("Updated the tracking state");
		}
		else
		{
			log.debug("Unable to update the tracking state due to the state being null. Initializing the state.");
			this.trackingState = new EssencePouchTrackingState();
			// Initialize the pouches
			for (EssencePouches pouch : EssencePouches.values())
			{
				this.pouches.put(pouch, new EssencePouch(pouch));
			}
		}
		this.saveTrackingState();
	}

	private String getItemName(int itemID)
	{
		return itemManager.getItemComposition(itemID).getName();
	}

	private void updatePouchFromState(EssencePouch pouch)
	{
		EssencePouch pouchFromState = this.trackingState.getPouch(pouch.getPouchType());
		this.pouches.put(pouch.getPouchType(), pouchFromState);
		log.debug("Updated the {} from the tracking state ({} stored, {} until decay)", pouch.getPouchType().getName(), pouch.getStoredEssence(), pouch.getRemainingEssenceBeforeDecay());
	}

	private String serializeState(EssencePouchTrackingState state)
	{
		return this.gson.toJson(state, EssencePouchTrackingState.class);
	}

	private EssencePouchTrackingState deserializeState(String serializedStateAsJSON)
	{
		return this.gson.fromJson(serializedStateAsJSON, EssencePouchTrackingState.class);
	}
}
