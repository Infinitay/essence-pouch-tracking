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
		smallPouch = new EssencePouch(EssencePouches.SMALL);
		mediumPouch = new EssencePouch(EssencePouches.MEDIUM);
		largePouch = new EssencePouch(EssencePouches.LARGE);
		giantPouch = new EssencePouch(EssencePouches.GIANT);
		colossalPouch = new EssencePouch(EssencePouches.COLOSSAL);
	}

	public EssencePouch getPouch(EssencePouches pouch)
	{
		switch (pouch)
		{
			case SMALL:
				return smallPouch;
			case MEDIUM:
				return mediumPouch;
			case LARGE:
				return largePouch;
			case GIANT:
				return giantPouch;
			case COLOSSAL:
				return colossalPouch;
		}
		return null;
	}

	public EssencePouch getPouch(EssencePouch pouch)
	{
		return this.getPouch(pouch.getPouchType());
	}

	public void setPouch(EssencePouch pouch)
	{
		switch (pouch.getPouchType())
		{
			case SMALL:
				smallPouch = pouch;
				break;
			case MEDIUM:
				mediumPouch = pouch;
				break;
			case LARGE:
				largePouch = pouch;
				break;
			case GIANT:
				giantPouch = pouch;
				break;
			case COLOSSAL:
				colossalPouch = pouch;
				break;
		}
	}
}
