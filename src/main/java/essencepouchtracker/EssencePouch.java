package essencepouchtracker;

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

	public EssencePouch(EssencePouches pouchType, int storedEssence, int remainingEssenceBeforeDecay, boolean isDegraded, boolean shouldDegrade)
	{
		this.pouchType = pouchType;
		this.storedEssence = storedEssence;
		this.remainingEssenceBeforeDecay = remainingEssenceBeforeDecay;
		this.isDegraded = isDegraded;
		this.shouldDegrade = pouchType.equals(EssencePouches.SMALL) ? false : shouldDegrade;
		log.debug("Created new Essence Pouch: {}", this);
	}

	public EssencePouch(EssencePouches pouchType, int storedEssence)
	{
		this(pouchType, storedEssence, pouchType.getMaxEssenceBeforeDecay(), false, true);
	}

	public EssencePouch(EssencePouches pouchType)
	{
		this(pouchType, 0);
	}

	public void repairPouch()
	{
		this.remainingEssenceBeforeDecay = this.pouchType.getMaxEssenceBeforeDecay();
		this.setDegraded(false);
	}

	public void empty(int essenceEmptied)
	{
		int previousStoredEssence = this.storedEssence;
		this.storedEssence -= essenceEmptied;
		log.debug("Removing {} essence, storing {}/{} essence into the {}. Previously stored {}/{} essence.",
			essenceEmptied,
			this.storedEssence,
			this.pouchType.getMaxCapacity(),
			this.pouchType.getName(),
			previousStoredEssence,
			this.pouchType.getMaxCapacity());
	}

	/**
	 * @param totalEssenceInInventory
	 * @return the number of essence that was stored given totalEssenceInInventory
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
		log.debug("Given {} essence, storing {}/{} essence into the {}. You can store approximately {} more essence until decay.",
			totalEssenceInInventory,
			essenceToStore,
			this.pouchType.getMaxCapacity(),
			this.pouchType.getName(),
			this.remainingEssenceBeforeDecay);
		// Left-over essence that exceeded maximum capacity
		// Math.max(0, totalEssenceInInventory - this.pouchType.getMaxCapacity())
		return essenceToStore;
	}

	public boolean isFilled()
	{
		return this.storedEssence == this.pouchType.getMaxCapacity();
	}

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

	public int getAvailableSpace()
	{
		return this.getMaximumCapacity() - this.storedEssence;
	}

	public boolean isEmpty()
	{
		return this.storedEssence == 0;
	}
}
