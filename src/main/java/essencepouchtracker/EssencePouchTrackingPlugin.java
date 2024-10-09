package essencepouchtracker;

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
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NpcID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
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
		int essenceInPreviousInventory = (int) previousInventory.stream().filter(itemID -> isValidEssencePouchItem(itemID)).count();

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
					}
				}
			}

			// TODO Add support for multiple essence types because you can't mix essence types when filling pouches
			// Since these essences were removed from the inventory, we need to fill the pouches with them
			int essenceRemovedFromInventory = (int) removedItems.stream().filter(removedItemID -> isValidEssencePouchItem(removedItemID)).count();
			// Since these essences were added to the inventory, we need to empty the pouches with them
			int essenceAddedToInventory = (int) addedItems.stream().filter(addedItemID -> isValidEssencePouchItem(addedItemID)).count();
			if (pouchQueue.isEmpty())
			{
				log.debug("Essence removed from inventory (To fill): " + essenceRemovedFromInventory);
				log.debug("Essence added from inventory (To empty) " + essenceAddedToInventory);
			}

			while (!pouchQueue.isEmpty())
			{
				// Get the first pouch in the queue to be filled
				log.debug("Pouch Queue Before Filling/Emptying: " + pouchQueue);
				log.debug("Essence removed from inventory (To fill): " + essenceRemovedFromInventory);
				log.debug("Essence added from inventory (To empty) " + essenceAddedToInventory);
				PouchActionTask pouchAction = pouchQueue.poll();

				EssencePouch pouch = pouches.get(pouchAction.getPouchType());
				if (pouch != null)
				{
					// List of possible tasks:
					// 1. Fill pouch
					// What could happen when we try to fill a pouch?
					//  ✓a. There is enough essence in the inventory to fill the pouch => Fill the pouch
					//  ✓b. There is not enough essence in the inventory to fill the pouch => Fill the pouch with the max amount of essence in the inventory
					//  ✓c. We don't have any essence in the inventory => Do nothing
					//    c1. EDGE CASE (Tick race-case): The event inventory change has the wrong # of items removed (added to pouch) due to multiple actions per tick.
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

					// First lets fetch more details about the pouch
					int currentPouchCapacity = pouch.getStoredEssence();
					int maxPouchCapacity = pouch.getMaximumCapacity();
					int availableSpace = pouch.getAvailableSpace();
					boolean isPouchDegraded = pouch.isDegraded();
					// User wants to fill the pouch
					if (pouchAction.getAction() == PouchActionTask.PouchAction.FILL)
					{
						// Lets do the simple thing first: If the pouch is full then we can't fill it
						if (availableSpace == 0)
						{
							log.debug("{} is already full so we can't fill it", pouch);
						}
						else
						{
							// Okay so now we know the pouch is not full and it is ready to be filled
							// Check to see if we have enough essence in the inventory to fill the pouch
							if (essenceRemovedFromInventory > 0)
							{
								int previousPouchSize = pouch.getStoredEssence();
								int usedEssence = pouch.fill(essenceRemovedFromInventory);
								int leftOverEssence = essenceRemovedFromInventory - usedEssence;
								log.debug("Chose to fill {}/{} given essence (+{}). Leftover essence to add: {}", pouch.getStoredEssence() - previousPouchSize, essenceRemovedFromInventory, usedEssence, leftOverEssence);
								essenceRemovedFromInventory = leftOverEssence;
								// We don't have to update used/free slots because
							}
							else
							{
								// c1 EDGE CASE: Tick race conditions. The event said there isn't enough essence to fill the pouch but it's just that it's being delayed to the next event.
								// So lets handle this. First, lets just check the previous inventory state just to make sure we even should delay this action.
								if (previousInventory != null && previousInventory.size() > 0)
								{
									// Lets check the previous inventory state to see if we have enough essence to fill the pouch
									int essenceInPreviousInventory = (int) previousInventory.stream().filter(itemID -> isValidEssencePouchItem(itemID)).count();
									if (essenceInPreviousInventory > 0)
									{
										int edgeCaseEssence = essenceInPreviousInventory - essenceRemovedFromInventory;
										if (edgeCaseEssence > 0)
										{
//											log.debug("Reached fill edge case for {}", pouch);
											// Add the edge case essence to our existing essenceRemovedFromInventory
											essenceRemovedFromInventory += edgeCaseEssence;
											int previousPouchSize = pouch.getStoredEssence();
											int usedEssence = pouch.fill(essenceRemovedFromInventory);
											int leftOverEssence = essenceRemovedFromInventory - usedEssence;
											log.debug("[Edge Case] Chose to fill {}/{} given essence (+{}). Leftover essence to add: {}", pouch.getStoredEssence() - previousPouchSize, essenceRemovedFromInventory, usedEssence, leftOverEssence);
											essenceRemovedFromInventory = leftOverEssence;
											// Don't forget to update used/free slots - Here, because we filled used-- and free++
											usedInventorySlots -= usedEssence;
											freeSlots += usedEssence;
											continue;
										}
									}
								}
								log.debug("We don't have any essence in the inventory to fill {}", pouch);
							}
						}
					}
					else if (pouchAction.getAction() == PouchActionTask.PouchAction.EMPTY)
					{
						// Lets handle the simple case first: If the pouch is empty then we can't empty it
						if (currentPouchCapacity == 0)
						{
							log.debug("{} is already empty so we can't empty it", pouch);
						}
						else
						{
							// The pouch isn't empty and has essence stored in it, so lets empty it now
							// Check to see if we have enough space in the inventory to empty the pouch

							// Technically when we empty a pouch, we are adding essence to the inventory therefore freeSlots decreases

							if ((freeSlots + essenceAddedToInventory) > 0)
							{
								// We have free space within the inventory to empty the pouch
								int essenceToEmpty = Math.min(currentPouchCapacity, (freeSlots + essenceAddedToInventory));
								pouch.empty(essenceToEmpty);
								essenceAddedToInventory += essenceToEmpty;
								log.debug("Chose to empty {}/{} available essence. Leftover essence to empty: {}", essenceToEmpty, currentPouchCapacity, essenceAddedToInventory);
							}
							else
							{
								int essenceInPreviousInventory = (int) previousInventory.stream().filter(itemID -> isValidEssencePouchItem(itemID)).count();
								log.debug("We don't have enough space in the inventory to empty {}", pouch);
							}
						}
					}
				}
			}
			log.debug("Pouch Queue After Filling/Emptying: " + pouchQueue);
			previousInventory = currentInventory;
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

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{

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
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 12);
					this.fakeEmptyEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 12);
					this.fakeFillEssencePouch(new EssencePouches[]{EssencePouches.GIANT}, 12);
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
					break;
				default:
					break;
			}
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
}
