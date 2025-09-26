package jenkins.metrics.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import jenkins.model.details.Detail;
import jenkins.model.details.DetailFactory;

import java.util.List;

@Extension
public final class MetricsDetailFactory extends DetailFactory<Run> {

    @Override
    public Class<Run> type() {
        return Run.class;
    }

    @NonNull
    @Override
    public List<? extends Detail> createFor(@NonNull Run target) {
        return List.of(new MetricsDetail(target));
    }
}
