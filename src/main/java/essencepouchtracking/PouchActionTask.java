package essencepouchtracking;

import lombok.Data;

@Data
public class PouchActionTask
{
	private EssencePouches pouchType;
	private PouchAction action;

	public PouchActionTask(EssencePouches pouchType, String action)
	{
		this.pouchType = pouchType;
		this.action = action.equalsIgnoreCase("Fill") ? PouchAction.FILL : PouchAction.EMPTY;
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
