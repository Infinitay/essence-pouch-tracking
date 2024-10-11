package essencepouchtracking;

import lombok.EqualsAndHashCode;
import lombok.Value;

@Value
@EqualsAndHashCode
public class PouchActionCreated
{
	PouchActionTask pouchActionTask;
}
