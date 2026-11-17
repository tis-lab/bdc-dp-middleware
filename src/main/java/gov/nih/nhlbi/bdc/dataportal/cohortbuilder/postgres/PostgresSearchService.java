package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.postgres;

import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.*;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryModels.*;

@Service
@EnableConfigurationProperties(PostgresProperties.class)
public class PostgresSearchService {
    private final PostgresProperties config;
    private static final List<String> CHECKS = List.of("participant_count", "null_status", "relationship_unmapped",
            "demography_conflict", "baseline_coverage", "unit_inconsistent", "unit_missing",
            "quantity_value_conflict", "enum_value_invalid");
    private static final Set<String> RACES = Set.of("AMERICAN_INDIAN_OR_ALASKA_NATIVE", "ASIAN", "BLACK_OR_AFRICAN_AMERICAN",
            "MIDDLE_EASTERN_OR_NORTH_AFRICAN", "NATIVE_HAWAIIAN_OR_OTHER_PACIFIC_ISLANDER", "UNKNOWN", "WHITE", "MORE_THAN_ONE_RACE");
    public PostgresSearchService(PostgresProperties config) { this.config = config; }

    public SummaryResponse search(Map<String,Object> filter, List<String> requestedFocus) {
        var selection = PostgresFilter.compile(filter);
        var focus = PostgresFilter.focus(requestedFocus);
        if (!config.enabled()) throw new ApiException(UPSTREAM_UNAVAILABLE, "PostgreSQL search is disabled.");
        var connectionProperties = new Properties();
        connectionProperties.setProperty("user", config.username());
        connectionProperties.setProperty("password", config.password());
        connectionProperties.setProperty("connectTimeout", "5");
        connectionProperties.setProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds() + 5));
        try (Connection connection = DriverManager.getConnection(config.url(), connectionProperties)) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                checkRelease(connection);
                SummaryResponse response = aggregate(connection, selection, focus);
                connection.commit();
                return response;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) {
            // Never return SQL, connection URLs, credentials or participant data to clients.
            boolean timeout = failure instanceof SQLTimeoutException || "57014".equals(failure.getSQLState());
            throw new ApiException(timeout ? UPSTREAM_TIMEOUT : UPSTREAM_UNAVAILABLE,
                    timeout ? "PostgreSQL search timed out." : "PostgreSQL search could not complete; check connection, grants and schema readiness.");
        }
    }
    private void checkRelease(Connection connection) throws SQLException {
        Set<String> passed = new HashSet<>();
        rows(connection, "SELECT check_name, passed FROM counting.release_check WHERE release=?", List.of(config.release()), r -> {
            if (!r.getBoolean("passed")) throw new ApiException(UPSTREAM_UNAVAILABLE, "PostgreSQL release has failed validation checks.");
            passed.add(r.getString("check_name"));
        });
        if (!passed.containsAll(CHECKS)) throw new ApiException(UPSTREAM_UNAVAILABLE, "PostgreSQL release validation is incomplete.");
    }
    private SummaryResponse aggregate(Connection c, PostgresFilter.Compiled filter, List<String> focus) throws SQLException {
        String cohort = "WITH selected AS (SELECT p.release,p.ordinal,p.research_study_id FROM counting.participant_ordinal p WHERE p.release=? AND " + filter.sql() + ") ";
        List<Object> args = new ArrayList<>(); args.add(config.release()); args.addAll(filter.parameters());
        Map<String,Study> studies = new TreeMap<>();
        rows(c, cohort + "SELECT research_study_id, count(*) AS n FROM selected GROUP BY research_study_id ORDER BY research_study_id", args, r -> {
            BigInteger n = count(r, "n");
            String id = r.getString("research_study_id");
            if (id == null || id.isBlank()) throw invalid("Missing study ID.");
            if (visible(n)) studies.put(id, new Study(id, n));
        });
        String focused = focus.isEmpty() ? "" : " AND e.concept IN (" + String.join(",", Collections.nCopies(focus.size(), "?")) + ")";
        List<Object> conditionArgs = new ArrayList<>(args); conditionArgs.addAll(focus);
        rows(c, cohort + "SELECT s.research_study_id,e.concept,e.relationship,e.status, grouping(e.status) AS root, count(DISTINCT s.ordinal) AS n "
                + "FROM selected s JOIN counting.event e ON e.release=s.release AND e.ordinal=s.ordinal WHERE e.source_class='Condition'" + focused
                + " GROUP BY GROUPING SETS ((s.research_study_id,e.concept),(s.research_study_id,e.concept,e.relationship,e.status))"
                + " ORDER BY s.research_study_id,e.concept,root DESC,e.relationship,e.status", conditionArgs, r -> {
            Study study = studies.get(r.getString("research_study_id")); if (study == null) return;
            String concept = r.getString("concept");
            if (concept == null || concept.isBlank()) throw invalid("Condition concept is missing.");
            BigInteger n = bounded(count(r,"n"),study.total);
            if (r.getInt("root") == 1) { if (visible(n)) study.conditions.put(concept, new Condition(concept,n)); return; }
            String relation = r.getString("relationship"), status = r.getString("status");
            if (relation == null || !PostgresFilter.RELATIONS.contains(relation) || status == null || !Set.of("PRESENT","ABSENT","UNKNOWN","HISTORICAL").contains(status))
                throw invalid("Condition status or relationship is missing or invalid.");
            Condition parent = study.conditions.get(concept);
            if (parent != null && visible(n)) parent.values.add(new ConditionSummary(PostgresFilter.id("condition",concept,relation,status), concept,status,relation,n,null,List.of()));
        });
        for (String slot : List.of("sex", "race", "ethnicity")) {
            String values = slot.equals("race") ? "unnest(a.race)" : "unnest(ARRAY[a."+slot+"])";
            rows(c, cohort + "SELECT s.research_study_id,v.value,grouping(v.value) AS root,count(DISTINCT s.ordinal) AS n "
                    + "FROM selected s JOIN counting.participant_attribute a ON a.release=s.release AND a.ordinal=s.ordinal "
                    + "CROSS JOIN LATERAL " + values + " AS v(value) WHERE v.value IS NOT NULL "
                    + "GROUP BY GROUPING SETS ((s.research_study_id),(s.research_study_id,v.value)) ORDER BY s.research_study_id,root DESC,v.value", args, r -> {
                Study study=studies.get(r.getString("research_study_id")); if (study==null) return;
                BigInteger n=bounded(count(r,"n"),study.total);
                if (r.getInt("root")==1) { if (visible(n)) study.demographics.put(slot,new Demography(slot,n)); return; }
                String value=r.getString("value");
                Set<String> allowed=switch(slot) {
                    case "sex" -> Set.of("MALE","FEMALE","UNKNOWN");
                    case "ethnicity" -> Set.of("HISPANIC_OR_LATINO","NOT_HISPANIC_OR_LATINO");
                    default -> RACES;
                };
                if (!allowed.contains(value)) throw invalid("Invalid demographic enum value.");
                Demography parent=study.demographics.get(slot);
                if(parent!=null && visible(n)) parent.values.add(new DemographySummary(PostgresFilter.id("demography",slot,value),slot,n,null,
                        slot.equals("sex")?value:null,slot.equals("race")?List.of(value):List.of(),slot.equals("ethnicity")?value:null,List.of()));
            });
        }
        // Match the current summary contract's INDEX age semantics, never substitute baseline age.
        rows(c, cohort + "SELECT s.research_study_id,count(a.age_at_index_days) AS n,"
                + "count(*) FILTER (WHERE a.age_at_index_days<0) AS invalid,"
                + "count(*) FILTER (WHERE a.age_at_index_days>=16437 AND a.age_at_index_days<20089) AS b0,"
                + "count(*) FILTER (WHERE a.age_at_index_days>=20089 AND a.age_at_index_days<23742) AS b1,"
                + "count(*) FILTER (WHERE a.age_at_index_days>=23742 AND a.age_at_index_days<29220) AS b2 "
                + "FROM selected s LEFT JOIN counting.participant_attribute a ON a.release=s.release AND a.ordinal=s.ordinal GROUP BY s.research_study_id",args,r -> {
            Study study=studies.get(r.getString("research_study_id")); if(study==null) return;
            if(r.getLong("invalid")!=0) throw invalid("Negative index age.");
            BigInteger n=bounded(count(r,"n"),study.total); if(!visible(n)) return;
            int[][] bands={{45,55},{55,65},{65,80}}; List<ParticipantSummary> bins=new ArrayList<>();
            for(int i=0;i<bands.length;i++) {
                BigInteger value=bounded(count(r,"b"+i),n); if(!visible(value)) continue;
                int low=bands[i][0],high=bands[i][1];
                bins.add(new ParticipantSummary(PostgresFilter.id("participant","age_at_index",low+"-"+high,"a"),"age_at_index",value,null,
                        BigDecimal.valueOf(low),BigDecimal.valueOf(high),"a",List.of()));
            }
            study.ages.add(new ParticipantSummary(PostgresFilter.id("participant","age_at_index"),"age_at_index",n,null,null,null,null,bins));
        });
        Set<String> terms=new TreeSet<>(focus);
        studies.values().forEach(s -> terms.addAll(s.conditions.keySet()));
        return new SummaryResponse(config.release(),focus,filter.requiredIds(),BigInteger.valueOf(config.minCount()),
                terms.stream().map(t -> new SummaryTerm(t,null,null,List.of())).toList(),List.of(),List.of(),
                studies.values().stream().map(Study::response).toList());
    }
    @FunctionalInterface private interface Row { void accept(ResultSet result) throws SQLException; }
    private void rows(Connection c,String sql,List<Object> args,Row row) throws SQLException {
        try(PreparedStatement statement=c.prepareStatement(sql+" LIMIT "+(config.maxAggregateRows()+1))) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            for(int i=0;i<args.size();i++) statement.setObject(i+1,args.get(i));
            try(ResultSet result=statement.executeQuery()) {
                int n=0;
                while(result.next()) {
                    if(++n>config.maxAggregateRows()) throw invalid("Aggregate result exceeds configured limit; narrow the query.");
                    row.accept(result);
                }
            }
        }
    }
    private boolean visible(BigInteger n) { return n.compareTo(BigInteger.valueOf(config.minCount()))>=0; }
    private static BigInteger count(ResultSet r,String column) throws SQLException {
        BigDecimal value=r.getBigDecimal(column);
        if(value==null || value.signum()<0) throw invalid("Invalid aggregate count.");
        return value.toBigIntegerExact();
    }
    private static BigInteger bounded(BigInteger n,BigInteger parent) {
        if(n.compareTo(parent)>0) throw invalid("Aggregate exceeds its parent."); return n;
    }
    private static ApiException invalid(String message) { return new ApiException(UPSTREAM_INVALID_RESPONSE,message); }
    private static final class Condition {
        final String concept; final BigInteger count; final List<ConditionSummary> values=new ArrayList<>();
        Condition(String concept,BigInteger count){this.concept=concept;this.count=count;}
        ConditionSummary response(){return new ConditionSummary(PostgresFilter.id("condition",concept),concept,null,null,count,null,values);}
    }
    private static final class Demography {
        final String slot; final BigInteger count; final List<DemographySummary> values=new ArrayList<>();
        Demography(String slot,BigInteger count){this.slot=slot;this.count=count;}
        DemographySummary response(){return new DemographySummary(PostgresFilter.id("demography",slot),slot,count,null,null,List.of(),null,values);}
    }
    private static final class Study {
        final String id; final BigInteger total; final Map<String,Condition> conditions=new TreeMap<>();
        final Map<String,Demography> demographics=new LinkedHashMap<>(); final List<ParticipantSummary> ages=new ArrayList<>();
        Study(String id,BigInteger total){this.id=id;this.total=total;}
        StudySummary response(){return new StudySummary(id,total,ages,demographics.values().stream().map(Demography::response).toList(),
                conditions.values().stream().map(Condition::response).toList(),List.of(),List.of(),List.of());}
    }
}
