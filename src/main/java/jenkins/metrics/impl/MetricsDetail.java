package jenkins.metrics.impl;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Util;
import hudson.model.Actionable;
import jenkins.metrics.api.Messages;
import jenkins.model.details.Detail;

public class MetricsDetail extends Detail {
    public MetricsDetail(Actionable object) {
        super(object);
    }

    @Nullable
    @Override
    public String getIconClassName() {
        return "symbol-hourglass-outline plugin-ionicons-api";
    }

    @Nullable
    @Override
    public String getDisplayName() {
        TimeInQueueAction timeInQueueAction = getObject().getAction(TimeInQueueAction.class);
        return Messages.queued(Util.getTimeSpanString(timeInQueueAction.getQueuingDurationMillis()));
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 2;
    }
}
