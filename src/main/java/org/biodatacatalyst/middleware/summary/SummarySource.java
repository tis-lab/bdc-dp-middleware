package org.biodatacatalyst.middleware.summary;

import java.util.List;
import java.util.Optional;
import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;

public interface SummarySource {
    Optional<SummaryResponse> find(String sourceId);
    List<String> sourceIds();
}
