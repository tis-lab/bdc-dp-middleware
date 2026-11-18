package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.BAD_USER_INPUT;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryModels.SummaryResponse;

/** Loads reviewed examples once. Never calculates or approximates filtered counts. */
@Service
public class SummaryService {
    private record Entry(Map<String,Object> filter,List<String> focus,String file) {}
    private record Scenario(Map<String,Object> filter,List<String> focus,SummaryResponse response) {}
    private final List<Scenario> scenarios;

    public SummaryService(@Value("${summary.scenarios}") Resource manifest) throws java.io.IOException {
        var json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var yaml=YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        List<Scenario> loaded=new ArrayList<>();
        try(var input=manifest.getInputStream()) {
            for(var entry:json.readValue(input,Entry[].class)) {
                if(entry.file()==null || !entry.file().matches("SYNTHETIC[.][A-Za-z0-9.-]+[.]yaml"))
                    throw new IllegalArgumentException("Invalid synthetic summary filename.");
                try(var file=manifest.createRelative(entry.file()).getInputStream()) {
                    var document = yaml.readTree(file);
                    if (!(document instanceof com.fasterxml.jackson.databind.node.ObjectNode aggregate))
                        throw new IllegalArgumentException("Summary document must be an object.");
                    aggregate.remove("records");
                    var response = yaml.treeToValue(aggregate, SummaryResponse.class);
                    if(response.release()==null || response.min_count()==null || response.min_count().signum()<1)
                        throw new IllegalArgumentException("Invalid summary metadata.");
                    var focus=entry.focus()==null?List.<String>of():List.copyOf(entry.focus());
                    if(!response.focus().equals(focus)) throw new IllegalArgumentException("Scenario focus differs from response.");
                    List<String> expected=entry.filter()==null?List.of():List.of((String)entry.filter().get("summaryId"));
                    if(!response.filter().equals(expected)) throw new IllegalArgumentException("Scenario filter differs from response.");
                    loaded.add(new Scenario(entry.filter(),focus,response));
                }
            }
        }
        if(loaded.isEmpty()) throw new IllegalArgumentException("No summary scenarios configured.");
        scenarios=List.copyOf(loaded);
    }
    public SummaryResponse summary(Map<String,Object> filter,List<String> focus) {
        var requested=focus==null?List.<String>of():focus;
        return scenarios.stream().filter(s->Objects.equals(s.filter(),filter)&&s.focus().equals(requested))
                .findFirst().map(Scenario::response).orElseThrow(()->new ApiException(BAD_USER_INPUT,
                        "This deployment serves fixed synthetic summaries only. The requested filter/focus has no packaged scenario; no filtering was executed."));
    }
}
