package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.graphql;

import org.springframework.stereotype.Controller;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.PageLoadSnapshotService;

@Controller
public class PageLoadSnapshotQueryController {
    private final PageLoadSnapshotService snapshots;
    public PageLoadSnapshotQueryController(PageLoadSnapshotService snapshots) { this.snapshots=snapshots; }
    @QueryMapping public PageLoadSnapshotService.Snapshot pageLoadSnapshot() { return snapshots.pageLoadSnapshot(); }
}
