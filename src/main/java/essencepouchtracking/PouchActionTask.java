package essencepouchtracking;

import lombok.Data;

@Data
public class PouchActionTask
{
	private EssencePouches pouchType;
	private PouchAction action;
	private int createdAtGameTick;
	private boolean wasSuccessful;

	public enum PouchAction
	{
		FILL,
		EMPTY
	}

	public PouchActionTask(EssencePouches pouchType, String action, int createdAtGameTick)
	{
		this.pouchType = pouchType;
		this.action = action.equalsIgnoreCase("Fill") ? PouchAction.FILL : PouchAction.EMPTY;
		this.createdAtGameTick = createdAtGameTick;
		this.wasSuccessful = false;
	}

	public PouchActionTask(PouchActionTask pouchActionTask)
	{
		this.pouchType = pouchActionTask.getPouchType();
		this.action = pouchActionTask.getAction();
	}

	public boolean wasSuccessful()
	{
		return this.wasSuccessful;
	}

	public void setWasSuccessful(boolean successful)
	{
		this.wasSuccessful = successful;
	}

	@Override
	public String toString()
	{
		return this.action.name() + " " + this.pouchType.getName();
	}
}
