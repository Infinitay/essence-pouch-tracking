# [Essence Pouch Tracker](https://runelite.net/plugin-hub/show/essence-pouch-tracking)

![image](https://img.shields.io/endpoint?url=https://api.runelite.net/pluginhub/shields/installs/plugin/essence-pouch-tracking)
![image](https://img.shields.io/endpoint?url=https://api.runelite.net/pluginhub/shields/rank/plugin/essence-pouch-tracking)

A RuneLite plugin that tracks both the amount of essence stored in your essence pouches and the amount of essence until decay.

[!Preview](https://github.com/user-attachments/assets/4e704127-92ea-4daf-976c-ae24dc29d8da)

## Features
- Supports all essence kinds of essence and essence pouches (including colossal pouch pre-85 Runecrafting)
- Shows the amount of essence stored in the essence pouch
- Shows the amount of essence until the decay
- Tracks repairing pouches via [Dark Mage](https://oldschool.runescape.wiki/w/Dark_Mage_(Abyss)) (Abyss or NPC Contact) or GOTR's [Apprentice Cordelia](https://oldschool.runescape.wiki/w/Apprentice_Cordelia#Raiments_of_the_Eye)
- Supports [Guardians of the Rift](https://oldschool.runescape.wiki/w/Guardians_of_the_Rift) (GOTR)
- Supports not decaying pouches when wearing [special equipment](https://oldschool.runescape.wiki/w/Essence_pouch#:~:text=Pouches%20degrade%20on,90%20Firemaking.)

## How to Use the Plugin
1. Install the plugin from the RuneLite [Plugin Hub](https://runelite.net/plugin-hub/) by searching for "[Essence Pouch Tracker](https://runelite.net/plugin-hub/show/essence-pouch-tracking)"
2. Check your essence pouch to initialize the pouch's current essence count
3. Attempt to repair your essence pouches with any method you prefer to initialize the pouches' remaining decay count

If you have any troubles, try to reset the plugin and re-check and repair your pouches once more. If you still have any problems, please feel free to open an issue on the [GitHub repository](https://github.com/Infinitay/essence-pouch-tracker/issues?q=sort%3Aupdated-desc+is%3Aissue+is%3Aopen) and be as descriptive as possible.
## Known Issues
### Most issues should resolvable by rechecking your pouches and potentially moving around an item within your inventory. If that doesn't help, then attempt to reset the plugin within the plugin settings.

 - _Stored count for an essence pouch **could** be wrong_
   - There were some rare instances in which the stored count **could** be wrong. After hours of testing, I've faced it perhaps once or twice, and one of them was in a 4+ hour session.
   - For example, when you have more essence in your inventory than **plugin thinks you can store into a pouch**, and especially if you do multiple of the same actions on that incorrect pouch.
     - [Click here to view an example video](https://github.com/user-attachments/assets/24be6edf-f9f7-48ca-8680-bd6f1e50fb52). Take note at the numbers after "COLOSSAL" the first is the stored amount, followed by the max allowed and max decay amounts.
       - When I first fill the pouch, I double-clicked to fill it. So it's trying to fill 15 essence into a pouch that can hold 8. However, the game lets us store more and so the pouch state is incorrect. So all the essence is in the pouch but the state shows the maximum of 8 still. The second click is still registered and so it subtracts what it thinks as 7 essence in our inventory (15-8=7) and tries to add it into our pouch again, however the max allowed is still 8 so it'll "subtract" the essence from our inventory so now we'll have 0 essence. That way when we empty the pouch, the incorrect stored amount of 8 will be subtracted from the pouch amount and instead added to the essence count within our inventory.
       - In the second example, I only click to fill it once. You can see the same incorrect behavior happening. However, you can also clearly see that the plugin state thinks that the essence within the inventory is 7 even though there is no essence in the inventory. That way, when the playe empties the pouch, it'll subtract the incorrect amount of 8 and add it to the inventory (8+7), and the state will be correct again.
 - Different essence types are not tracked separately
   - This is a feature I'm working on supporting in the future (See [#12](https://github.com/Infinitay/essence-pouch-tracker/issues/12))
 - Consecutive pouch degradations that result in further reduction of storable essence won't be tracked properly. For example, if your pouch degrades twice and you lose a total extra space of 10, it'll only recognize the initial degradation and think you only have 5 less available.

## What's Different?

The main reason I started working on this plugin was because there wasn't a plugin that more accurately tracked essence pouches. I won't claim that this is 100% accurate, but I will say that I've spent more hours testing the plugin on each iteration. I wanted to be sure that I've fixed most if not every edge case problem.

What makes this plugin different primarily is that it handles multiple actions done in one game tick. Here are some examples of considered and tested cases:

- When emptying/filling one or more pouches within a single game tick.
- When emptying/filling multiple times on a pouch within a single game tick
  - I'm not fast enough to test doing this on multiple pouches at the same time within a single game tick, but I'm confident that it should work
- When quickly emptying pouches and crafting runes
- When quickly filling pouches at a bank

This plugin also supports the GOTR minigame such as by supporting [Guardian essence](https://oldschool.runescape.wiki/w/Guardian_essence) and by clearing pouches on new matches or leaving the area

If you'd like more information, I wrote a reasoning/comparison between this plugin and the other essence pouch plugins when I finished the P.O.C. which you can read more about [here](https://gist.github.com/Infinitay/9ff647e746985d8d2e4ec8e3b183c33e).
