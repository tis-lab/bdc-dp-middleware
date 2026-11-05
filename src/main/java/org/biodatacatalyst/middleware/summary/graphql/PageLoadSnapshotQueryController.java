package org.biodatacatalyst.middleware.summary.graphql;

import org.springframework.stereotype.Controller;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.biodatacatalyst.middleware.summary.PageLoadSnapshotService;

@Controller
public class PageLoadSnapshotQueryController {
    private final PageLoadSnapshotService snapshots;
    public PageLoadSnapshotQueryController(PageLoadSnapshotService snapshots) { this.snapshots=snapshots; }
    @QueryMapping public PageLoadSnapshotService.Snapshot pageLoadSnapshot() { return snapshots.pageLoadSnapshot(); }
}
