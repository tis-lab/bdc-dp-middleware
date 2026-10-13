package org.biodatacatalyst.middleware.harmonized;

import java.util.List;


public record BdcVariable(String name,
                          String label,
                          String domain,
                          List<String> studies,
                          List<String> concepts) {

    /** How many studies harmonize this variable. The single most useful number for ranking. */
    public int studyCount() {
        return studies.size();
    }
}
