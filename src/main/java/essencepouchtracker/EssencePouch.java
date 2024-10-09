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
			this.pouchType.getCapacity(),
			this.pouchType.getName(),
			previousStoredEssence,
			this.pouchType.getCapacity());
	}

	/**
	 * @param totalEssenceInInventory
	 * @return left-over essence that exceeded maximum capacity
	 */
	public int fill(int totalEssenceInInventory)
	{
		int spaceAvailableInPouch = this.pouchType.getCapacity() - this.storedEssence;
		int essenceToStore = Math.min(totalEssenceInInventory, spaceAvailableInPouch);
		this.storedEssence = essenceToStore;
		if (this.shouldDegrade)
		{
			this.remainingEssenceBeforeDecay -= essenceToStore;
		}
		log.debug("Given {} essence, storing {}/{} essence into the {}. You can store approximately {} more essence until decay.",
			totalEssenceInInventory,
			essenceToStore,
			this.pouchType.getCapacity(),
			this.pouchType.getName(),
			this.remainingEssenceBeforeDecay);
		return Math.max(0, totalEssenceInInventory - this.pouchType.getCapacity());
	}

	public boolean isFilled()
	{
		return this.storedEssence == this.pouchType.getCapacity();
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
}
