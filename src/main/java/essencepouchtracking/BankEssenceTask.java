package essencepouchtracking;

import javax.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import net.runelite.client.game.ItemManager;

@Getter
public class BankEssenceTask
{
	@Inject
	private ItemManager itemManager;

	private BankEssenceAction action;
	private String itemName;
	private int itemID;
	@Setter
	private int quantity;

	public BankEssenceTask(String action, String itemName, int itemId, int quantity)
	{
		this.action = action.toLowerCase().startsWith("withdraw") ? BankEssenceAction.WITHDRAW : BankEssenceAction.DEPOSIT;
		this.itemName = itemName;
		this.itemID = itemId;
		this.quantity = quantity;
	}

	public enum BankEssenceAction
	{
		WITHDRAW,
		DEPOSIT
	}

	@Override
	public String toString()
	{
		return this.action.name() + " " + this.itemName + " x" + this.quantity;
	}
}
