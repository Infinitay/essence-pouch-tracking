package essencepouchtracking;

import lombok.Data;

@Data
public class PouchActionTask
{
	private EssencePouches pouchType;
	private PouchAction action;
	private int createdAtGameTick;

	public PouchActionTask(EssencePouches pouchType, String action, int createdAtGameTick)
	{
		this.pouchType = pouchType;
		this.action = action.equalsIgnoreCase("Fill") ? PouchAction.FILL : PouchAction.EMPTY;
		this.createdAtGameTick = createdAtGameTick;
	}

	public PouchActionTask(PouchActionTask pouchActionTask)
	{
		this.pouchType = pouchActionTask.getPouchType();
		this.action = pouchActionTask.getAction();
	}

	public enum PouchAction
	{
		FILL,
		EMPTY
	}

	@Override
	public String toString()
	{
		return this.action.name() + " " + this.pouchType.getName();
	}
}
