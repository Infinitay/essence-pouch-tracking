package essencepouchtracking;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class EssencePouch
{
	private final EssencePouches pouchType;
	private int storedEssence;
	private int remainingEssenceBeforeDecay;
	private boolean isDegraded;
	private boolean shouldDegrade;
	private boolean unknownStored;
	private boolean unknownDecay;

	public EssencePouch(EssencePouches pouchType, int storedEssence, int remainingEssenceBeforeDecay, boolean isDegraded, boolean shouldDegrade, boolean unknownState, boolean unknownDecay)
	{
		this.pouchType = pouchType;
		this.storedEssence = storedEssence;
		this.remainingEssenceBeforeDecay = remainingEssenceBeforeDecay;
		this.isDegraded = isDegraded;
		this.shouldDegrade = pouchType.equals(EssencePouches.SMALL) ? false : shouldDegrade;
		this.unknownStored = unknownState;
		this.unknownDecay = unknownDecay;
		log.debug("Created new Essence Pouch: {}", this);
	}

	public EssencePouch(EssencePouches pouchType, int storedEssence, boolean unknownState, boolean unknownDecay)
	{
		this(pouchType, storedEssence, pouchType.getMaxEssenceBeforeDecay(), false, true, unknownState, unknownDecay);
	}

	public EssencePouch(EssencePouches pouchType)
	{
		this(pouchType, 0, true, true);
	}

	/**
	 * Repairs the pouch by resetting the essence remaining before decay and resets the degradation state
	 */
	public void repairPouch()
	{
		this.remainingEssenceBeforeDecay = this.pouchType.getMaxEssenceBeforeDecay();
		this.setDegraded(false);
		this.setUnknownDecay(false);
	}

	/**
	 * Empties the specified amount of essence from the pouch
	 *
	 * @param essenceToRemove the number of essence to remove from the pouch
	 */
	public int empty(int essenceToRemove)
	{
		int previousStoredEssence = this.storedEssence;
		int essenceRemoved = Math.min(essenceToRemove, this.storedEssence);
		this.storedEssence -= essenceRemoved;
		this.setUnknownStored(false);
		log.debug("Asked to remove {} essence, removing {}, storing {}/{} essence into the {}. Previously stored {}/{} essence.",
			essenceToRemove,
			essenceRemoved,
			this.storedEssence,
			this.pouchType.getMaxCapacity(),
			this.pouchType.getName(),
			previousStoredEssence,
			this.pouchType.getMaxCapacity()
		);
		return essenceRemoved;
	}

	/**
	 * Fills the pouch with essence until it's full or as much essence that was given
	 *
	 * @param totalEssenceInInventory
	 * @return the number of essence that stored within the pouch
	 * Example: totalEssenceInInventory=10, storedEssence=3, getMaximumCapacity()=12 => return 1 (10+3 = 13 - 12 = 1 left over)
	 */
	public int fill(int totalEssenceInInventory)
	{
		int essenceToStore = Math.min(totalEssenceInInventory, this.getAvailableSpace());
		this.storedEssence += essenceToStore;
		if (this.shouldDegrade)
		{
			this.remainingEssenceBeforeDecay -= essenceToStore;
		}
		this.setUnknownStored(false);
		log.debug("Given {} essence, storing {}/{} essence into the {}. You can store approximately {} more essence until decay.",
			totalEssenceInInventory,
			essenceToStore,
			this.pouchType.getMaxCapacity(),
			this.pouchType.getName(),
			this.remainingEssenceBeforeDecay
		);
		// Left-over essence that exceeded maximum capacity
		// Math.max(0, totalEssenceInInventory - this.pouchType.getMaxCapacity())
		return essenceToStore;
	}

	/**
	 * @return whether the pouch is fully filled or not
	 */
	public boolean isFilled()
	{
		return this.storedEssence == this.getMaximumCapacity();
	}

	/**
	 * @return the ratio (percentage) of fill usages left for the pouch
	 */
	public double getApproximateFillsLeft()
	{
		if (this.shouldDegrade)
		{
			return this.remainingEssenceBeforeDecay / this.pouchType.getMaxEssenceBeforeDecay();
		}
		else
		{
			return 1;
		}
	}

	/**
	 * Gets the maximum essence capacity based on whether it's a regular or degraded pouch
	 *
	 * @return the maximum amount of essence the pouch can fill given the state (degraded or not)
	 */
	public int getMaximumCapacity()
	{
		if (this.isDegraded)
		{
			return this.pouchType.getMaxInitialDegradedCapacity();
		}
		else
		{
			return this.pouchType.getMaxCapacity();
		}
	}

	/**
	 * @return the amount of essence that can be stored in the pouch
	 */
	public int getAvailableSpace()
	{
		return this.getMaximumCapacity() - this.storedEssence;
	}

	/**
	 * @return whether the pouch is empty or not
	 */
	public boolean isEmpty()
	{
		return this.storedEssence == 0;
	}
}
