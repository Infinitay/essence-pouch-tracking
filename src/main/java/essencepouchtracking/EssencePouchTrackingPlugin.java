package essencepouchtracking;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.collect.Queues;
import com.google.gson.Gson;
import com.google.inject.Provides;
import essencepouchtracking.pouch.RepairDialog;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Skill;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Essence Pouch Tracker"
)
public class EssencePouchTrackingPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EssencePouchTrackingConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Gson gson;

	@Inject
	private EssencePouchTrackingOverlay overlay;

	@Inject
	private EssencePouchTrackingDebugOverlay debugOverlay;

	@Inject
	@Named("developerMode")
	boolean developerMode;

	@Getter
	private EssencePouchTrackingState trackingState;

	@Getter
	private final Map<EssencePouches, EssencePouch> pouches = new HashMap<>();
	@Getter
	private final Deque<PouchActionTask> pouchTaskQueue = Queues.newArrayDeque();
	private final Deque<EssencePouch> checkedPouchQueue = Queues.newArrayDeque();
	private BankEssenceTask bankEssenceTask;
	private Multiset<Integer> previousInventory = HashMultiset.create();
	private Multiset<Integer> currentInventoryItems = HashMultiset.create();

	@Getter
	private int previousEssenceInInventory, essenceInInventory;
	@Getter
	private int previousInventoryFreeSlots, inventoryFreeSlots;
	@Getter
	private int previousInventoryUsedSlots, inventoryUsedSlots;

	@Getter
	private int pauseUntilTick;
	private boolean isRepairDialogue;
	private boolean didUnlockGOTRRepair;

	// Last action of the tick
	@Getter
	private boolean wasLastActionCraftRune;
	private int lastCraftRuneTick;
	private int lastRCXP = -1;
	private int currentRCLevel = -1;

	private final Set<Integer> RC_CAPES_SET = ImmutableSet.<Integer>builder().addAll(this.getItemVariants(ItemID.RUNECRAFT_CAPE))
		.addAll(this.getItemVariants(ItemID.MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.ACCUMULATOR_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.ASSEMBLER_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.MASORI_ASSEMBLER_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.DIZANAS_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.FIRE_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.INFERNAL_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.IMBUED_GUTHIX_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.IMBUED_SARADOMIN_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.IMBUED_ZAMORAK_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.MYTHICAL_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.GUTHIX_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.SARADOMIN_MAX_CAPE))
		.addAll(this.getItemVariants(ItemID.ZAMORAK_MAX_CAPE))
		.build();
	private boolean isCapeDecayPreventionActive;
	private boolean hasRedwoodAbyssalLanternEquipped;
	private boolean hasRedwoodAbyssalLanternInInventory;
	private boolean isLanternDecayPreventionAvailable;

	private boolean didSendCheckNotification;
	private int delayTicksUntilLevelsInitialized = 2;

	@Provides
	EssencePouchTrackingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EssencePouchTrackingConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Loading the tracking state on plugin start");
		// Load the tracking state
		this.loadTrackingState();
		this.overlayManager.add(overlay);
		if (developerMode && this.config.showDebugOverlay())
		{
			this.overlayManager.add(debugOverlay);
		}
		this.clientThread.invokeLater(() ->
			{
				if (this.client.getGameState().equals(GameState.LOGGED_IN))
				{
					this.didUnlockGOTRRepair = this.client.getVarbitValue(14672) == 1;
					this.currentRCLevel = this.getRunecraftingLevel();
				}
			}
		);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Resetting the plugin state and saving the tracking state before plugin shutting down");
		this.resetPluginState();
		this.saveTrackingState();
		this.overlayManager.remove(overlay);
		if (developerMode)
		{
			this.overlayManager.remove(debugOverlay);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged changedConfig)
	{
		if (changedConfig.getGroup().equals(EssencePouchTrackingConfig.GROUP))
		{
			if (changedConfig.getKey().equals("showDebugOverlay") && this.developerMode)
			{
				if (this.config.showDebugOverlay())
				{
					this.overlayManager.add(debugOverlay);
				}
				else
				{
					this.overlayManager.remove(debugOverlay);
				}
			}
		}
	}

	@Override
	public void resetConfiguration()
	{
		log.debug("Resetting the plugin and tracking states");
		// First reset plugin state followed by tracking state in order to initialize all pouches back into this.pouches
		this.resetPluginState();
		this.resetTrackingState();
		this.clientThread.invokeLater(() ->
		{
			if (this.client.getGameState().equals(GameState.LOGGED_IN))
			{
				this.didUnlockGOTRRepair = this.client.getVarbitValue(14672) == 1 ? true : false;
				this.currentRCLevel = this.getRunecraftingLevel();
				// Player is already logged in, so reload inventory and equipment data by resending the events
				ItemContainer currentInventory = this.client.getItemContainer(InventoryID.INVENTORY);
				ItemContainer currentEquipment = this.client.getItemContainer(InventoryID.EQUIPMENT);
				if (currentInventory != null)
				{
					this.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), currentInventory));
				}
				if (currentEquipment != null)
				{
					this.onItemContainerChanged(new ItemContainerChanged(InventoryID.EQUIPMENT.getId(), currentEquipment));
				}
			}
		});
		super.resetConfiguration();
	}

	@Subscribe
	public void onAccountHashChanged(AccountHashChanged changedAccountHash)
	{
		this.resetPluginState();
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
		else if (gameStateChanged.getGameState().equals(GameState.LOGGING_IN) || gameStateChanged.getGameState().equals(GameState.HOPPING))
		{
			this.delayTicksUntilLevelsInitialized = 2;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked menuOptionClicked)
	{
		this.wasLastActionCraftRune = false;
		//TODO All essence is removed from a pouch when it is dropped

		// Keep in mind that the current inventory will be updated after this event so if the event is fill now and you have 10 essence in your inventory, the inventory will be updated to 0 after this event
		// ItemContainer currentInventoryContainer = this.getInventoryContainer();

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
					// User withdrew a specific (1/5/10/x) quantity of essence
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
				log.debug("Player attempted to {} {} x{} ({}) | Total Available in {}: {}",
					menuOption,
					this.getItemName(itemID),
					quantityNumeric,
					quantity,
					menuOption.equals("withdraw") ? "Bank" : "Inventory",
					totalItemAmount
				);
			}

			EssencePouches pouchType = EssencePouches.getPouch(menuOptionClicked.getItemId());
			if (pouchType != null)
			{
				if (menuOption.equals("fill") || menuOption.equals("empty"))
				{
					PouchActionTask pouchTask = new PouchActionTask(pouchType, menuOption, this.client.getTickCount());
					if (this.delayTicksUntilLevelsInitialized > 0)
					{
						log.debug("The player attempted to {} a pouch. Delaying the task until the player's levels are initialized", pouchTask.getAction());
						menuOptionClicked.consume();
						return;
					}
					boolean wasActionSuccessful = onPouchActionCreated(new PouchActionCreated(pouchTask));
					if (wasActionSuccessful || this.lastCraftRuneTick != -1)
					{
						pouchTask.setWasSuccessful(wasActionSuccessful);
						this.pouchTaskQueue.add(pouchTask);
						log.debug("Added {} task to queue: {}", pouchTask, this.pouchTaskQueue);
					}
					log.debug("Was the {} task successful? {}", menuOption, wasActionSuccessful);
					if (!wasActionSuccessful && this.client.getWidget(ComponentID.BANK_CONTAINER) != null)
					{
						log.debug("Failed to {} the {} therefore consuming the click", menuOption, pouchType);
						menuOptionClicked.consume();
					}
				}
				else if (menuOption.equals("check"))
				{
					this.checkedPouchQueue.add(this.pouches.get(pouchType));
					this.pauseUntilTick = this.client.getTickCount() + 1;
				}
			}
		}

		// MenuOptionClicked(getParam0=53, getParam1=55, getMenuOption=Craft-rune, getMenuTarget=<col=ffff>Altar, getMenuAction=GAME_OBJECT_FIRST_OPTION, getId=34771)
		if (menuOptionClicked.getMenuAction().equals(MenuAction.GAME_OBJECT_FIRST_OPTION) && menuOptionClicked.getMenuOption().equalsIgnoreCase("craft-rune"))
		{
			// Player has interacted with an Altar to craft runes
			// Handle the state change after the experience drop which confirms the craft
			this.wasLastActionCraftRune = true;
			this.lastCraftRuneTick = this.client.getTickCount();
			this.pouchTaskQueue.clear();
		}

		if (menuOptionClicked.getMenuAction().equals(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT))
		{
			if (menuOptionClicked.getMenuOption().equalsIgnoreCase("use") && menuOptionClicked.getMenuTarget().endsWith("Altar"))
			{
				if (this.client.isWidgetSelected())
				{
					int selectedItemID = this.client.getSelectedWidget().getItemId();
					if (selectedItemID != -1 && this.itemManager.getItemComposition(selectedItemID).getName().endsWith("rune"))
					{
						// Player has interacted with an Altar using a rune (attempting to craft combination runes)
						// Handle the state change after the experience drop which confirms the craft
						this.wasLastActionCraftRune = true;
						this.lastCraftRuneTick = this.client.getTickCount();
						this.pouchTaskQueue.clear();
					}
				}
			}
		}

		// WIDGET_CONTINUE is for when a player clicks an option on a dialog which includes "Click here to continue"
		if (menuOptionClicked.getMenuAction().equals(MenuAction.WIDGET_CONTINUE))
		{
			Widget clickedWidget = menuOptionClicked.getWidget();
			Widget dialogPlayerTextWidget = this.client.getWidget(ComponentID.DIALOG_PLAYER_TEXT);
			if (clickedWidget != null && dialogPlayerTextWidget != null && clickedWidget.getText().equals(RepairDialog.DIALOG_CONTINUE_TEXT) && dialogPlayerTextWidget.getText().equals(RepairDialog.REQUEST_REPAIR_PLAYER_DIALOG_TEXT))
			{
				this.repairAllPouches();
			}
			if (this.didUnlockGOTRRepair)
			{
				Widget dialogNPCTextWidget = this.client.getWidget(ComponentID.DIALOG_NPC_TEXT);
				if (clickedWidget != null && dialogNPCTextWidget != null && clickedWidget.getText().equals(RepairDialog.DIALOG_CONTINUE_TEXT) && dialogNPCTextWidget.getText().equals(RepairDialog.REQUEST_REPAIR_CORDELIA_DIALOG_TEXT))
				{
					this.repairAllPouches();
				}
			}
		}
	}

	public boolean onPouchActionCreated(PouchActionCreated createdPouchAction)
	{
		this.pauseUntilTick = this.client.getTickCount() + 1;
		PouchActionTask pouchAction = createdPouchAction.getPouchActionTask();
		log.debug("New Pouch Action Received: {}", pouchAction);
		log.debug("[Inventory Data] PouchActionCreated Inventory | Essence in inventory: {}->{}, Free slots: {}->{}, Used slots: {}->{}",
			this.previousEssenceInInventory, this.essenceInInventory,
			this.previousInventoryFreeSlots, this.inventoryFreeSlots,
			this.previousInventoryUsedSlots, this.inventoryUsedSlots
		);
		log.debug("Task Queue: {}", this.pouchTaskQueue);
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
			this.pauseUntilTick = -1;
			return false;
		}
		else if (pouch.isUnknownStored())
		{
			log.debug("Pouch {} is unknown so can't do {} to avoid improper state", pouchAction.getPouchType(), pouchAction.getAction());
			this.pauseUntilTick = -1;
			return false;
		}

		log.debug("[Inventory Data] Before | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);

		if (pouchAction.getAction().equals(PouchActionTask.PouchAction.FILL))
		{
			// First check if the pouch is already full
			if (pouch.isFilled())
			{
				log.debug("{} is already full so can't fill it", pouchAction.getPouchType());
				this.pauseUntilTick = -1;
				return false;
			}
			// Now check to see if there's even essence in the inventory
			if (this.essenceInInventory == 0)
			{
				log.debug("No essence in the inventory to fill the {}", pouchAction.getPouchType());
				this.pauseUntilTick = -1;
				return false;
			}

			// We meet all the conditions required to fill the pouch with essence (we have essence and the pouch isn't full)
			int essencePutIntoThePouch = pouch.fill(this.essenceInInventory, this.shouldPreventFurtherDecay());
			log.debug("Added {} essence to the pouch for a total of {}/{}", essencePutIntoThePouch, pouch.getStoredEssence(), pouch.getMaximumCapacity());
			this.updatePreviousInventoryDetails();
			this.essenceInInventory -= essencePutIntoThePouch;
			this.inventoryUsedSlots -= essencePutIntoThePouch;
			this.inventoryFreeSlots += essencePutIntoThePouch;
		}
		else
		{
			// First check if the pouch is empty
			if (pouch.isEmpty())
			{
				log.debug("{} is already empty so can't empty it", pouchAction.getPouchType());
				this.pauseUntilTick = -1;
				return false;
			}
			// Now check to see if there's even space in the inventory
			if (this.inventoryFreeSlots == 0)
			{
				log.debug("No space in the inventory to empty the {}", pouchAction.getPouchType());
				this.pauseUntilTick = -1;
				return false;
			}
			// We meet all the conditions required to empty the pouch into the inventory (we have space to empty some if not all, and the pouch isn't empty)
			// Find out how much essence we can take out of the pouch
			int essenceEmptied = pouch.empty(this.inventoryFreeSlots);
			log.debug("Removed {} essence from the pouch for a total of {}/{}", essenceEmptied, pouch.getStoredEssence(), pouch.getMaximumCapacity());
			this.updatePreviousInventoryDetails();
			this.essenceInInventory += essenceEmptied;
			this.inventoryUsedSlots += essenceEmptied;
			this.inventoryFreeSlots -= essenceEmptied;
		}
		log.debug("[Inventory Data] After | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);
		// blockUpdate = false;
		this.saveTrackingState();
		return true;
	}

	public void onBankEssenceTaskCreated(BankEssenceTask createdBankEssenceTask)
	{
		this.pauseUntilTick = this.client.getTickCount() + 1;
		log.debug("[Inventory Data] Before onBankEssenceTaskCreated | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);
		// Update the inventory manually in case of tick race conditions when banking
		if (createdBankEssenceTask.getAction().equals(BankEssenceTask.BankEssenceAction.DEPOSIT))
		{
			// Depositing the essence from the players inventory into the bank
			// Remember to check the quantity because the quantity was assigned by the # of the item we have in the inventory
			int maximumEssenceAvailableToDeposit = Math.min(createdBankEssenceTask.getQuantity(), this.essenceInInventory);
			this.updatePreviousInventoryDetails();
			this.essenceInInventory -= maximumEssenceAvailableToDeposit;
			this.inventoryUsedSlots -= maximumEssenceAvailableToDeposit;
			this.inventoryFreeSlots += maximumEssenceAvailableToDeposit;
			log.debug("Deposited {} essence into the bank", maximumEssenceAvailableToDeposit);
		}
		else
		{
			// Withdrawing the essence from the players bank into their inventory
			// Remember to check the quantity because the quantity was assigned by the # of the item we have in the bank
			int maximumEssenceAvailableToWithdraw = Math.min(createdBankEssenceTask.getQuantity(), this.inventoryFreeSlots);
			this.updatePreviousInventoryDetails();
			this.essenceInInventory += maximumEssenceAvailableToWithdraw;
			this.inventoryUsedSlots += maximumEssenceAvailableToWithdraw;
			this.inventoryFreeSlots -= maximumEssenceAvailableToWithdraw;
			log.debug("Withdrew {} essence from the bank", maximumEssenceAvailableToWithdraw);
		}
		log.debug("[Inventory Data] After onBankEssenceTaskCreated | Essence in inventory: {}, Free slots: {}, Used slots: {}", this.essenceInInventory, this.inventoryFreeSlots, this.inventoryUsedSlots);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged itemContainerChanged)
	{
		// In case of unequipping an item -> INVENTORY -> EQUIPMENT changes
		if (itemContainerChanged.getContainerId() == InventoryID.INVENTORY.getId())
		{
			this.currentInventoryItems.clear();
			List<Item> itemStream = Arrays.stream(itemContainerChanged.getItemContainer().getItems()).filter(this.filterNullItemsPredicate()).collect(Collectors.toList());
			itemStream.forEach(item -> this.currentInventoryItems.add(item.getId(), this.itemManager.getItemComposition(item.getId()).isStackable() ? 1 : item.getQuantity()));
			boolean updatedInventoryState = false;
			if (this.pauseUntilTick == -1 || this.client.getTickCount() >= this.pauseUntilTick)
			{
				this.updatePreviousInventoryDetails();
				this.essenceInInventory = 0;
				itemStream.stream().filter(item -> this.isValidEssencePouchItem(item.getId())).forEach(item -> this.essenceInInventory++);
				this.inventoryUsedSlots = this.currentInventoryItems.size();
				this.inventoryFreeSlots = 28 - this.inventoryUsedSlots;
				updatedInventoryState = true;
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

			// Set containing essence pouches that either were degraded or repaired
			Set<EssencePouches> modifiedEssencePouch = new HashSet<>(EssencePouches.values().length);
			// Now that we've handling inventory state changes, we can handle the pouches
			// First check if we have any pouches in the inventory in case this is the user's first run or some other issue
			for (int itemId : addedItems.elementSet())
			{
				EssencePouch pouch = EssencePouches.createPouch(itemId, this.currentRCLevel);
				if (pouch != null && !this.pouches.containsKey(pouch.getPouchType()))
				{
					log.debug("Player added a {} to their inventory: {}", pouch.getPouchType().getName(), pouch);
					this.pouches.put(pouch.getPouchType(), pouch);
					this.updatePouchFromState(pouch);
					// Now lets check to see if the pouch has any unknown state, and if so, then send a message instructing the user
					EssencePouch currentPouch = this.pouches.get(pouch.getPouchType());
					if (!didSendCheckNotification && (currentPouch.isUnknownStored() || currentPouch.isUnknownDecay()))
					{
						String message = new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append("Please check your essence pouch(es) and attempt to repair them to initialize their state(s).").build();
						QueuedMessage queuedMessage = QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(message).build();
						this.chatMessageManager.queue(queuedMessage);
						this.didSendCheckNotification = true;
					}

					// Check to see if the pouch was upgraded or downgraded (Regular <-> Colossal) and reset respectively
					// If the pouch was upgraded, then the player should have the new pouch in their inventory
					if (EssencePouches.isARegularEssencePouch(itemId))
					{
						// User added a regular essence pouch into their inventory -> downgraded from colossal -> reset colossal pouch
						this.downgradedToRegularPouch();
					}
					else if (EssencePouches.isAColossalEssencePouch(itemId))
					{
						// User added a colossal essence pouch into their inventory -> upgraded from regular -> reset all regular pouches
						this.upgradedToColossalPouch();
					}
				}

				// Check to see if pouch has degraded
				if (pouch != null && this.pouches.containsKey(pouch.getPouchType()))
				{
					EssencePouch currentPouch = this.pouches.get(pouch.getPouchType());
					if (currentPouch != null && currentPouch.getPouchType().getDegradedItemID() == itemId && removedItems.contains(currentPouch.getPouchType().getItemID()))
					{
						modifiedEssencePouch.add(currentPouch.getPouchType());
						currentPouch.setDegraded(true);
						log.debug("{} has degraded and was added to the inventory", currentPouch.getPouchType().getName());
						if (currentPouch.getPouchType().equals(EssencePouches.COLOSSAL))
						{
							currentPouch.updateMaxDegradedCapacity(this.getRunecraftingLevel());
						}
						if (updatedInventoryState)
						{
							// Reset the inventory state to before the inventory update since we only had one action and the inventory was updated
							this.restorePreviousInventoryDetails();
						}
						// Re-run the action to update the pouch's state
						this.handPouchActionsPostDegrade(currentPouch);
					}
					else if (currentPouch != null && currentPouch.getPouchType().getItemID() == itemId && removedItems.contains(currentPouch.getPouchType().getDegradedItemID()))
					{
						modifiedEssencePouch.add(currentPouch.getPouchType());
						log.debug("{} has been repaired and was added to the inventory", currentPouch.getPouchType().getName());
					}
				}

				// Check to see if the player has an Abyssal Lantern (Redwood) in their inventory
				if (itemId == ItemID.ABYSSAL_LANTERN_REDWOOD_LOGS)
				{
					this.hasRedwoodAbyssalLanternInInventory = true;
					log.debug("Player added a Redwood Lantern in their inventory.");
				}
			}

			if (removedItems.elementSet().contains(ItemID.ABYSSAL_LANTERN_REDWOOD_LOGS))
			{
				this.hasRedwoodAbyssalLanternInInventory = false;
				log.debug("Player removed the Redwood Lantern from their inventory.");
			}
			removedItems.elementSet().stream().map(EssencePouches::getPouch).filter(Objects::nonNull).filter(Predicate.not(modifiedEssencePouch::contains)).forEach(pouchType ->
			{
				if (this.pouches.containsKey(pouchType))
				{
					this.pouches.remove(pouchType);
					log.debug("Player removed {} from their inventory.", pouchType.getName());
				}
			});
			this.previousInventory = currentInventory;
		}
		else if (itemContainerChanged.getContainerId() == InventoryID.EQUIPMENT.getId())
		{
			Item capeItem = this.getEquipmentContainer().getItem(EquipmentInventorySlot.CAPE.getSlotIdx());
			boolean hasEquippedSpecialCape = capeItem != null && this.RC_CAPES_SET.contains(capeItem.getId());
			if (this.isCapeDecayPreventionActive != hasEquippedSpecialCape)
			{
				this.isCapeDecayPreventionActive = hasEquippedSpecialCape;
				log.debug("Player {} a specialized cape. {} decay pouches further.",
					hasEquippedSpecialCape ? "equipped" : "unequipped",
					hasEquippedSpecialCape ? "Shouldn't" : "Should");
			}

			Item offHandItem = this.getEquipmentContainer().getItem(EquipmentInventorySlot.SHIELD.getSlotIdx());
			boolean hasEquippedLantern = offHandItem != null && offHandItem.getId() == ItemID.ABYSSAL_LANTERN_REDWOOD_LOGS;

			// If the lantern is equipped => this.hasRedwoodAbyssalLantern = true
			// If the lantern is not equipped
			//     a. If the lantern is in our inventory => this.hasRedwoodAbyssalLantern = true
			//     b. If the lantern is not in our inventory => this.hasRedwoodAbyssalLantern = false
			if (hasEquippedLantern != this.hasRedwoodAbyssalLanternEquipped)
			{
				log.debug("Player {} the Redwood Lantern.", hasEquippedLantern ? "equipped" : "unequipped");
				this.hasRedwoodAbyssalLanternEquipped = hasEquippedLantern;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		// On cold-starts or even other logins, apparently the levels are not initialized until some ticks after. The XpTracker plugin waits until two ticks, lets do the same
		if (this.delayTicksUntilLevelsInitialized > 0 && --this.delayTicksUntilLevelsInitialized == 0)
		{
			log.debug("Two ticks have passed since the player logged in. Re-updating max pouch capacities (RC Level: {})", this.currentRCLevel);
			this.currentRCLevel = this.getRunecraftingLevel();
			boolean didUpdateAnyPouch = false;
			for (EssencePouch pouch : this.pouches.values())
			{
				log.debug("Updating {}'s max capacities", pouch.getPouchType().getName());
				pouch.updateMaxCapacity(this.currentRCLevel);
				pouch.updateMaxDegradedCapacity(this.currentRCLevel);
				didUpdateAnyPouch = true;
			}
			// Re-save the tracking state to ensure the pouches are updated for future sessions
			if (didUpdateAnyPouch)
			{
				this.updateTrackingState();
			}
		}

		if (this.pauseUntilTick != -1 && this.client.getTickCount() > this.pauseUntilTick)
		{
			log.debug("Unblocking the inventory update and clearing queues");
			this.pouchTaskQueue.clear();
			this.checkedPouchQueue.clear();
			this.pauseUntilTick = -1;
		}
		// Wait two ticks just in case the action was delayed for some reason but the craft still went through
		// Also, it could be the case that the player is running to the altar and hasn't changed their action
		if (this.lastCraftRuneTick != -1 && this.client.getTickCount() > this.lastCraftRuneTick + 1 && !this.wasLastActionCraftRune)
		{
			this.lastCraftRuneTick = -1;
		}

		// Clear expired actions
		if (!this.pouchTaskQueue.isEmpty())
		{
			this.pouchTaskQueue.removeIf(task -> this.client.getTickCount() > task.getCreatedAtGameTick());
		}

		if (this.isRepairDialogue)
		{
			// Check if the player is in the dialogue with the Dark Mage to repair pouches
			// Starting with "Fine" = Dialog to repair
			// Starting with "There" = Dialog when Repair menu option
			// Starting with "You" = Dialog when Repair menu option but no pouches to repair
			Widget dialogNPCHeadModel = this.client.getWidget(ComponentID.DIALOG_NPC_HEAD_MODEL);
			Widget dialogText = this.client.getWidget(ComponentID.DIALOG_NPC_TEXT);
			if (dialogNPCHeadModel != null && dialogText != null)
			{
				if (dialogNPCHeadModel.getModelId() == RepairDialog.DARK_MAGE_WIDGET_MODEL_ID && (RepairDialog.REPAIRED_DARK_MAGE_DIALOG_TEXTS.contains(dialogText.getText())
					|| (this.didUnlockGOTRRepair && dialogText.getText().equals(RepairDialog.REPAIR_CONFIRMATION_CORDELIA_DARK_MAGE_DIALOG_TEXT))))
				{
					this.repairAllPouches();
				}
				// Cordelia's NPC ID is 12180 but her model ID when talking to her is 6717
				if (this.didUnlockGOTRRepair && dialogNPCHeadModel.getModelId() == RepairDialog.APPRENTICE_CORDELIA_WIDGET_MODEL_ID && RepairDialog.REPAIRED_CORDELIA_DIALOG_TEXTS.contains(dialogText.getText()))
				{
					this.repairAllPouches();
				}
			}

			// Check if the player is in the dialogue with the Dark Mage to repair pouches
			// Checks to see i the dialog options are after a repair dialogue or when the pouches are already repaired
			Widget dialogOptionsWidget = this.client.getWidget(ComponentID.DIALOG_OPTION_OPTIONS);
			if (dialogOptionsWidget != null && dialogOptionsWidget.getChildren() != null)
			{
				List<String> options = Arrays.stream(dialogOptionsWidget.getChildren()).filter(Objects::nonNull).map(Widget::getText).collect(Collectors.toList());
				if (RepairDialog.POST_REPAIR_DIALOG_OPTIONS.contains(options))
				{
					this.repairAllPouches();
				}
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
			case InterfaceID.DIALOG_PLAYER:
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
			case InterfaceID.DIALOG_PLAYER:
				this.isRepairDialogue = true;
				break;
			case InterfaceID.GOTR:
				log.debug("GOTR interface loaded");
				this.isLanternDecayPreventionAvailable = true;
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
			this.pouches.values().forEach(EssencePouch::resetStored);
			this.saveTrackingState();
		}
		else if (varbitChanged.getVarbitId() == 13691 && varbitChanged.getValue() == 0)
		{
			// Clear pouches if a player leaves the GOTR portal
			this.pouches.values().forEach(EssencePouch::resetStored);
			this.saveTrackingState();
			log.debug("Player has left the GOTR portal");
			this.isLanternDecayPreventionAvailable = false;
		}
		else if (varbitChanged.getVarbitId() == 14672)
		{
			// GOTR Pouch Repair Ability
			this.didUnlockGOTRRepair = varbitChanged.getValue() == 1;
		}
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop fakeXpDrop)
	{
		// Fires every login including when account switching.
		if (fakeXpDrop.getSkill().equals(Skill.RUNECRAFT))
		{
			// Runecrafting level checks and updates due to colossal pouch
			// Ignore the initial login xp drop
			if (this.currentRCLevel == -1)
			{
				this.currentRCLevel = this.getRunecraftingLevel();
			}
			else
			{
				int tempRCLevel = this.getRunecraftingLevel();
				if (tempRCLevel != this.currentRCLevel)
				{
					this.currentRCLevel = tempRCLevel;
					EssencePouch colossalPouch = this.pouches.get(EssencePouches.COLOSSAL);
					if (colossalPouch != null)
					{
						log.debug("onFakeXpDrop | Runecrafting level changed to {}. Attempting to update Colossal Pouch capacity & max decay.", this.currentRCLevel);
						colossalPouch.updateMaxCapacity(this.currentRCLevel);
						colossalPouch.updateMaxDegradedCapacity(this.currentRCLevel);
					}
				}
			}

			// Ignore the initial login xp drop
			if (this.lastRCXP == -1)
			{
				this.lastRCXP = fakeXpDrop.getXp();
				return;
			}

			// Courtesy of SpecialCounterPlugin
			if (fakeXpDrop.getXp() > this.lastRCXP)
			{
				this.lastRCXP = fakeXpDrop.getXp();
				// The last action was crafting runes and xp drop was detected
				// This should confirm the player crafted runes at an altar successfully and therefore used up essence
				log.debug("XP drop & craft action detected.");
				if (this.lastCraftRuneTick == -1)
				{
					log.debug("Received RC XP but lastCraftRuneTick is -1");
					return;
				}
				// Make sure there's essence because the onItemChangeContainer could have caught it such as upon the first craft
				if (this.essenceInInventory == 0)
				{
					log.debug("No essence in the inventory despite crafting runes");
					return;
				}
				this.updatePreviousInventoryDetails();
				this.inventoryUsedSlots -= this.essenceInInventory;
				this.inventoryFreeSlots += this.essenceInInventory;
				this.essenceInInventory = 0;
				Deque<PouchActionTask> tempActionQueue = Queues.newArrayDeque();
				PouchActionTask action;
				this.pouchTaskQueue.stream().forEach(tempTask -> tempActionQueue.add(new PouchActionTask(tempTask)));
				log.debug("Pouch task queue: {}", this.pouchTaskQueue);
				log.debug("Temp action queue: {}", tempActionQueue);
				while ((action = tempActionQueue.poll()) != null)
				{
					onPouchActionCreated(new PouchActionCreated(action));
				}
				log.debug("[Inventory Data] onFakeXpDrop Inventory | Essence in inventory: {}->{}, Free slots: {}->{}, Used slots: {}->{}",
					this.previousEssenceInInventory, this.essenceInInventory,
					this.previousInventoryFreeSlots, this.inventoryFreeSlots,
					this.previousInventoryUsedSlots, this.inventoryUsedSlots
				);
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		// Fires every login including when account switching.
		if (statChanged.getSkill().equals(Skill.RUNECRAFT))
		{
			// Runecrafting level checks and updates due to colossal pouch
			// Ignore the initial login xp drop
			if (this.currentRCLevel == -1)
			{
				this.currentRCLevel = this.getRunecraftingLevel();
			}
			else
			{
				int tempRCLevel = this.getRunecraftingLevel();
				if (tempRCLevel != this.currentRCLevel)
				{
					this.currentRCLevel = tempRCLevel;
					EssencePouch colossalPouch = this.pouches.get(EssencePouches.COLOSSAL);
					if (colossalPouch != null)
					{
						log.debug("onStatChanged | Runecrafting level changed to {}. Attempting to update Colossal Pouch capacity & max decay.", this.currentRCLevel);
						colossalPouch.updateMaxCapacity(this.currentRCLevel);
						colossalPouch.updateMaxDegradedCapacity(this.currentRCLevel);
					}
				}
			}

			// Ignore the initial login xp drop
			if (this.lastRCXP == -1)
			{
				this.lastRCXP = statChanged.getXp();
				return;
			}

			// Courtesy of SpecialCounterPlugin
			if (statChanged.getXp() > this.lastRCXP)
			{
				this.lastRCXP = statChanged.getXp();
				// The last action was crafting runes and xp drop was detected
				// This should confirm the player crafted runes at an altar successfully and therefore used up essence
				log.debug("XP drop & craft action detected.");
				if (this.lastCraftRuneTick == -1)
				{
					log.debug("Received RC XP but lastCraftRuneTick is -1");
					return;
				}
				// Make sure there's essence because the onItemChangeContainer could have caught it such as upon the first craft
				if (this.essenceInInventory == 0)
				{
					log.debug("No essence in the inventory despite crafting runes");
					return;
				}
				this.updatePreviousInventoryDetails();
				this.inventoryUsedSlots -= this.essenceInInventory;
				this.inventoryFreeSlots += this.essenceInInventory;
				this.essenceInInventory = 0;
				Deque<PouchActionTask> tempActionQueue = Queues.newArrayDeque();
				PouchActionTask action;
				this.pouchTaskQueue.stream().forEach(tempTask -> tempActionQueue.add(new PouchActionTask(tempTask)));
				log.debug("Pouch task queue: {}", this.pouchTaskQueue);
				log.debug("Temp action queue: {}", tempActionQueue);
				while ((action = tempActionQueue.poll()) != null)
				{
					onPouchActionCreated(new PouchActionCreated(action));
				}
				log.debug("[Inventory Data] onStatChanged Inventory | Essence in inventory: {}->{}, Free slots: {}->{}, Used slots: {}->{}",
					this.previousEssenceInInventory, this.essenceInInventory,
					this.previousInventoryFreeSlots, this.inventoryFreeSlots,
					this.previousInventoryUsedSlots, this.inventoryUsedSlots
				);
			}
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
				for (PouchActionTask taskAction : this.pouchTaskQueue)
				{
					if (taskAction.getPouchType().equals(pouch.getPouchType()))
					{
						if (taskAction.wasSuccessful())
						{
							log.debug("Pouch action for {} was successful therefore ignoring pouch check result.", pouch.getPouchType());
							return;
						}
					}
				}

				if (pouch.isUnknownStored())
				{
					log.debug("{} has an unknown stored essence count. Setting it to {} stored essence.", pouch.getPouchType().getName(), numberOfEssence);
					pouch.setStoredEssence(numberOfEssence);
					this.updateTrackingState();
					return;
				}

				int previousStoredEssence = pouch.getStoredEssence();
				int difference = numberOfEssence - previousStoredEssence;

				// If the difference is negative, then essence was removed from the pouch at some point
				// If the difference is positive, then essence was added to the pouch at some point
				// At this point, we could either set the EssencePouch#setUnknownDecay to true because we don't know if the user continued to fill the pouch or not
				// However, there's no need to complicate things further. Although it's hypocritical in this case, let's not assume that the user had more than one fill action that resulted in the current state
				log.debug("Pouch {} previously had a state with {} stored essence, now has {} stored essence", pouch.getPouchType().getName(), previousStoredEssence, numberOfEssence);
				if (difference > 0)
				{
					pouch.fill(difference, this.shouldPreventFurtherDecay());
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
				this.updateTrackingState();
				return;
			}
			else
			{
				log.debug("Received a check pouch count message, but there was no more pouches in the queue.");
				return;
			}
		}

		if (!developerMode)
		{
			return;
		}

		if (receivedChatMessage.getType().equals(ChatMessageType.FRIENDSCHAT) && receivedChatMessage.getName().equalsIgnoreCase(this.client.getLocalPlayer().getName()))
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
				case "!savecolo":
					String stateJSON = "{\"smallPouch\":{\"pouchType\":\"SMALL\",\"storedEssence\":0,\"remainingEssenceBeforeDecay\":2147483647,\"isDegraded\":false,\"shouldDegrade\":false,\"unknownStored\":false,\"unknownDecay\":false,\"maxCapacity\":0},\"mediumPouch\":{\"pouchType\":\"MEDIUM\",\"storedEssence\":0,\"remainingEssenceBeforeDecay\":270,\"isDegraded\":false,\"shouldDegrade\":true,\"unknownStored\":false,\"unknownDecay\":false,\"maxCapacity\":0},\"largePouch\":{\"pouchType\":\"LARGE\",\"storedEssence\":0,\"remainingEssenceBeforeDecay\":261,\"isDegraded\":false,\"shouldDegrade\":true,\"unknownStored\":false,\"unknownDecay\":false,\"maxCapacity\":0},\"giantPouch\":{\"pouchType\":\"GIANT\",\"storedEssence\":0,\"remainingEssenceBeforeDecay\":132,\"isDegraded\":false,\"shouldDegrade\":true,\"unknownStored\":false,\"unknownDecay\":false,\"maxCapacity\":0},\"colossalPouch\":{\"pouchType\":\"COLOSSAL\",\"storedEssence\":0,\"remainingEssenceBeforeDecay\":261,\"isDegraded\":false,\"shouldDegrade\":true,\"unknownStored\":false,\"unknownDecay\":false,\"maxCapacity\":0}}";
					this.trackingState = this.deserializeState(stateJSON);
					this.saveTrackingState();
					log.debug("Saved Tracking State: {}", this.trackingState);
					this.pouches.replaceAll((key, value) -> this.trackingState.getPouch(key));
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

		// 2153 proc,chatbox_keyinput_matched any key pressed matched with the input dialog
		// So for example space bar to "Click here to continue" or a number key associated with the menu option
		// Didn't seem to fire for some input dialogs like the threshold to re-enable running or ESC
		// Go through a dialog with mouse clicks only -> No CS2 fired
		// Go through a dialog with keyboard -> proc,chatbox_keyinput_matched
		// Go through a dialog with keyboard at least once -> Mouse click dialog interactions -> clientscript,chatbox_keyinput_clicklistener
		// Go through a dialog with keyboard at least once -> Keyboard dialog interactions -> proc,chatbox_keyinput_matched
		if (this.isRepairDialogue && postFiredScript.getScriptId() == 2153)
		{
			Widget dialogPlayerTextWidget = this.client.getWidget(ComponentID.DIALOG_PLAYER_TEXT);
			if (dialogPlayerTextWidget != null && dialogPlayerTextWidget.getText().equals(RepairDialog.REQUEST_REPAIR_PLAYER_DIALOG_TEXT))
			{
				this.repairAllPouches();
				return;
			}
			if (this.didUnlockGOTRRepair)
			{
				Widget dialogNpcTextWidget = this.client.getWidget(ComponentID.DIALOG_NPC_TEXT);
				if (dialogNpcTextWidget != null && dialogNpcTextWidget.getText().equals(RepairDialog.REQUEST_REPAIR_CORDELIA_DIALOG_TEXT))
				{
					this.repairAllPouches();
				}
			}
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
		return this.client.getItemContainer(InventoryID.INVENTORY);
	}

	private ItemContainer getEquipmentContainer()
	{
		return this.client.getItemContainer(InventoryID.EQUIPMENT);
	}

	private Collection<Integer> getItemVariants(int itemID)
	{
		return ItemVariationMapping.getVariations(itemID);
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
				this.updatePreviousInventoryDetails();
				this.essenceInInventory += numberOfEssenceEmptied;
				this.inventoryUsedSlots += numberOfEssenceEmptied;
				this.inventoryFreeSlots -= numberOfEssenceEmptied;
				// Now that the pouch has been emptied out, we can re-fill it
				PouchActionTask pouchFilLAction = new PouchActionTask(degradedPouch.getPouchType(), "Fill", this.client.getTickCount());
				onPouchActionCreated(new PouchActionCreated(pouchFilLAction));
			}
			else
			{
				// Undo the empty
				int numberOfEssenceRestored = degradedPouch.fill(this.essenceInInventory, this.shouldPreventFurtherDecay());
				degradedPouch.setRemainingEssenceBeforeDecay(degradedPouch.getRemainingEssenceBeforeDecay() - numberOfEssenceRestored);
				log.debug("Restoring {} essence to the pouch for a total of {}/{}", numberOfEssenceRestored, degradedPouch.getStoredEssence(), degradedPouch.getMaximumCapacity());
				this.updatePreviousInventoryDetails();
				this.essenceInInventory -= numberOfEssenceRestored;
				this.inventoryUsedSlots -= numberOfEssenceRestored;
				this.inventoryFreeSlots += numberOfEssenceRestored;
				// Now that the pouch has been re-filled out, we can re-fill it
				PouchActionTask pouchFilLEmpty = new PouchActionTask(degradedPouch.getPouchType(), "Empty", this.client.getTickCount());
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
			EssencePouch pouch = this.pouches.get(task.getPouchType());
			int previousRemainingEssenceBeforeDecay = pouch.getRemainingEssenceBeforeDecay();
			int previousStoredEssence = pouch.getStoredEssence();
			boolean wasSuccessful = onPouchActionCreated(new PouchActionCreated(task));
			int currentStoredEssence = pouch.getStoredEssence();
			if (wasSuccessful && task.getAction().equals(PouchActionTask.PouchAction.EMPTY))
			{
				// Successfully reverted a fill task by re-emptying it, so don't forget to also revert the remaining essence before decay
				int dx = previousStoredEssence - currentStoredEssence;
				if (pouch.isDegraded())
				{
					pouch.setRemainingEssenceBeforeDecay(0);
				}
				else
				{
					pouch.setRemainingEssenceBeforeDecay(previousRemainingEssenceBeforeDecay + dx);
				}
			}
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
		EssencePouchTrackingState trackingState = this.deserializeState(trackingStateAsJSONString);
		if (trackingState != null)
		{
			this.trackingState = trackingState;
			log.debug("loadTrackingState | Loaded Tracking State: {}", trackingState);
			for (EssencePouches pouchType : this.pouches.keySet())
			{
				EssencePouch pouchFromState = trackingState.getPouch(pouchType);
				// Check to see if we need to update the max capacity after the feature update that added the max capacity to EssencePouch due to Colossal Pouch changes
				if (pouchFromState.getMaxCapacity() == 0 || pouchFromState.getMaxDegradedCapacity() == 0)
				{
					log.debug("loadTrackingState | Current {} max capacity {} & max degrade {}. Updating the max capacity...",
						pouchType.getName(),
						pouchFromState.getMaxCapacity(),
						pouchFromState.getMaxDegradedCapacity()
					);
					pouchFromState.updateMaxCapacity(this.getRunecraftingLevel());
					pouchFromState.updateMaxDegradedCapacity(this.getRunecraftingLevel());
				}
				this.pouches.put(pouchType, pouchFromState);
			}
		}
		else
		{
			log.debug("Unable to load the tracking state due to the loaded state being null");
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
				log.debug("updateTrackingState | Updated tracking state for {}", pouch);
			}
			log.debug("Updated the tracking state");
		}
		else
		{
			log.debug("updateTrackingState | Unable to update the tracking state due to the state being null. Initializing the state.");
			this.trackingState = new EssencePouchTrackingState();
			// Initialize the pouches
			for (EssencePouches pouch : this.pouches.keySet())
			{
				EssencePouch pouchFromState = this.trackingState.getPouch(pouch);
				if (pouch.equals(EssencePouches.COLOSSAL))
				{
					// Initialize the colossal pouch with the max capacity according to the player's runecrafting level due to Colossal Pouch changes
					// Check to see if we need to update the max capacity & degraded capacity
					pouchFromState.updateMaxCapacity(this.getRunecraftingLevel());
					pouchFromState.updateMaxDegradedCapacity(this.getRunecraftingLevel());
				}
				this.pouches.put(pouch, pouchFromState);
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
		// Check to see if we need to update the max capacities after the feature update that added the max capacity to EssencePouch due to Colossal Pouch changes
		if (pouchFromState.getMaxCapacity() == 0 || pouchFromState.getMaxDegradedCapacity() == 0)
		{
			log.debug("updatePouchFromState | Current {} max capacity {} & max degrade {}. Updating the max capacity...",
				pouchFromState.getPouchType().getName(),
				pouchFromState.getMaxCapacity(),
				pouchFromState.getMaxDegradedCapacity()
			);
			pouchFromState.updateMaxCapacity(this.getRunecraftingLevel());
			pouchFromState.updateMaxDegradedCapacity(this.getRunecraftingLevel());
		}
		// A pouch in the previous saved state could be marked as non-degraded, but current got degraded. Update it.
		pouchFromState.setDegraded(pouch.isDegraded());
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

	private void resetTrackingState()
	{
		log.debug("Resetting tracking state via #updateTrackingState");
		this.trackingState = null;
		this.updateTrackingState();
	}

	private void resetPluginState()
	{
		this.previousInventory.clear();
		this.pouches.clear();
		this.pouchTaskQueue.clear();
		this.checkedPouchQueue.clear();
		this.currentInventoryItems.clear();
		this.previousEssenceInInventory = this.essenceInInventory = 0;
		this.previousInventoryFreeSlots = this.inventoryFreeSlots = 0;
		this.previousInventoryUsedSlots = this.inventoryUsedSlots = 0;
		this.pauseUntilTick = 0;
		this.isRepairDialogue = false;
		this.wasLastActionCraftRune = false;
		this.lastCraftRuneTick = -1;
		this.lastRCXP = -1;
		this.currentRCLevel = -1;
		this.didSendCheckNotification = false;
		this.delayTicksUntilLevelsInitialized = 2;
	}

	private void repairAllPouches()
	{
		boolean repaired = false;
		for (EssencePouches pouchType : EssencePouches.values())
		{
			EssencePouch pouch = this.trackingState.getPouch(pouchType);
			if (pouch.getApproximateFillsLeft() != 1.0 || pouch.isUnknownDecay())
			{
				pouch.repairPouch();
				repaired = true;
			}
		}
		if (repaired)
		{
			log.debug("Pouches have been repaired");
			this.isRepairDialogue = false; // To prevent the repair from being triggered again onGameTick
			this.saveTrackingState();
		}
	}

	private boolean hasRedwoodAbyssalLantern()
	{
		return this.hasRedwoodAbyssalLanternEquipped || this.hasRedwoodAbyssalLanternInInventory;
	}

	private boolean shouldPreventFurtherDecay()
	{
		// Prevent decay if the player has a speciality cape equipped
		// Prevent decay if the player has an abyssal lantern equipped or their inventory while lantern prevention is available
		if (this.isCapeDecayPreventionActive)
		{
			return true;
		}
		else if (this.isLanternDecayPreventionAvailable)
		{
			return this.hasRedwoodAbyssalLantern();
		}
		else
		{
			return false;
		}
	}

	// User added a colossal essence pouch into their inventory -> upgraded from regular -> reset all regular pouches
	private void upgradedToColossalPouch()
	{
		// First see if the pouch exists in our inventory and reset the state. This should technically never be non-null
		// Since an upgrade to colossal => no more regular pouches in the inventory. But just in case
		for (EssencePouches pouchType : EssencePouches.REGULAR_ESSENCE_POUCHES)
		{
			EssencePouch pouchFromInventory = this.pouches.get(pouchType);
			if (pouchFromInventory != null)
			{
				pouchFromInventory.reset();
				this.trackingState.setPouch(pouchFromInventory);
			}
			else
			{
				EssencePouch pouchFromState = this.trackingState.getPouch(pouchType);
				if (pouchFromState != null)
				{
					pouchFromState.reset();
					this.trackingState.setPouch(pouchFromState);
				}
			}
		}
		this.saveTrackingState();
	}

	// User added a regular essence pouch into their inventory -> downgraded from colossal -> reset colossal pouch
	private void downgradedToRegularPouch()
	{
		// First see if the pouch exists in our inventory and reset the state. This should technically never be non-null
		// Since a downgrade to regular => no more colossal pouch in the inventory. But just in case
		EssencePouch pouchFromInventory = this.pouches.get(EssencePouches.COLOSSAL);
		if (pouchFromInventory != null)
		{
			pouchFromInventory.reset();
			this.trackingState.setPouch(pouchFromInventory);
			this.saveTrackingState();
		}
		else
		{
			EssencePouch pouchFromState = this.trackingState.getPouch(EssencePouches.COLOSSAL);
			if (pouchFromState != null)
			{
				pouchFromState.reset();
				this.trackingState.setPouch(pouchFromState);
				this.saveTrackingState();
			}
		}
		this.saveTrackingState();
	}

	public int getRunecraftingLevel()
	{
		return this.client.getRealSkillLevel(Skill.RUNECRAFT);
	}
}
