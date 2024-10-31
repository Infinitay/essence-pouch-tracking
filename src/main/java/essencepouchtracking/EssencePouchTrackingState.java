package essencepouchtracking;

import lombok.Data;

@Data
public class EssencePouchTrackingState
{
	EssencePouch smallPouch;
	EssencePouch mediumPouch;
	EssencePouch largePouch;
	EssencePouch giantPouch;
	EssencePouch colossalPouch;

	public EssencePouchTrackingState()
	{
		this.smallPouch = new EssencePouch(EssencePouches.SMALL);
		this.mediumPouch = new EssencePouch(EssencePouches.MEDIUM);
		this.largePouch = new EssencePouch(EssencePouches.LARGE);
		this.giantPouch = new EssencePouch(EssencePouches.GIANT);
		this.colossalPouch = new EssencePouch(EssencePouches.COLOSSAL);
	}

	public EssencePouch getPouch(EssencePouches pouch)
	{
		if (pouch == null)
		{
			return null;
		}

		switch (pouch)
		{
			case SMALL:
				return this.smallPouch;
			case MEDIUM:
				return this.mediumPouch;
			case LARGE:
				return this.largePouch;
			case GIANT:
				return this.giantPouch;
			case COLOSSAL:
				return this.colossalPouch;
			default:
				return null;
		}
	}

	public EssencePouch getPouch(EssencePouch pouch)
	{
		return this.getPouch(pouch.getPouchType());
	}

	public EssencePouch getPouch(int itemID)
	{
		return this.getPouch(EssencePouches.getPouch(itemID));
	}

	public void setPouch(EssencePouch pouch)
	{
		switch (pouch.getPouchType())
		{
			case SMALL:
				this.smallPouch = pouch;
				break;
			case MEDIUM:
				this.mediumPouch = pouch;
				break;
			case LARGE:
				this.largePouch = pouch;
				break;
			case GIANT:
				this.giantPouch = pouch;
				break;
			case COLOSSAL:
				this.colossalPouch = pouch;
				break;
		}
	}
}
