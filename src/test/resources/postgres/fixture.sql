-- Explicit synthetic fixture, never deployed to a real database.
CREATE TABLE counting.participant_ordinal_test PARTITION OF counting.participant_ordinal FOR VALUES IN ('pg-test');
CREATE TABLE counting.participant_attribute_test PARTITION OF counting.participant_attribute FOR VALUES IN ('pg-test');
CREATE TABLE counting.event_test PARTITION OF counting.event FOR VALUES IN ('pg-test');
INSERT INTO counting.release_check
 SELECT 'pg-test', check_name,0,0,true,now() FROM unnest(ARRAY[
 'participant_count','null_status','relationship_unmapped','demography_conflict','baseline_coverage',
 'unit_inconsistent','unit_missing','quantity_value_conflict','enum_value_invalid']) AS check_name;
INSERT INTO counting.participant_ordinal VALUES
 ('pg-test',1,'p1','study:one','c1'),('pg-test',2,'p2','study:one','c1'),
 ('pg-test',3,'p3','study:one','c1'),('pg-test',4,'p4','study:one','c1'),
 ('pg-test',5,'p5','study:two','c2'),('pg-test',6,'p6','study:two','c2'),
 ('2026.10',1,'other-release','study:other','c3');
INSERT INTO counting.participant_attribute(release,ordinal,sex,ethnicity,race,age_at_index_days,age_at_baseline_days) VALUES
 ('pg-test',1,'FEMALE','HISPANIC_OR_LATINO',ARRAY['WHITE','ASIAN','WHITE'],16437,16425),
 ('pg-test',2,'MALE','NOT_HISPANIC_OR_LATINO',ARRAY['WHITE'],20089,20075),
 ('pg-test',3,NULL,NULL,ARRAY[]::text[],NULL,23725),
 ('pg-test',4,'FEMALE',NULL,NULL,29220,29200),
 ('pg-test',5,'MALE',NULL,ARRAY['BLACK_OR_AFRICAN_AMERICAN'],23742,23725),
 ('pg-test',6,'FEMALE',NULL,ARRAY['WHITE'],NULL,16425);
INSERT INTO counting.event(release,source_record_id,source_class,source_file,ordinal,age_start_slot,concept,status,relationship) VALUES
 ('pg-test','r1','Condition','test',1,'age_at_condition_start','MONDO:0005044','PRESENT','ONESELF'),
 ('pg-test','r2','Condition','test',1,'age_at_condition_start','MONDO:0005044','PRESENT','ONESELF'),
 ('pg-test','r3','Condition','test',1,'age_at_condition_start','HP:0000822','PRESENT','ONESELF'),
 ('pg-test','r4','Condition','test',2,'age_at_condition_start','MONDO:0005044','PRESENT','ONESELF'),
 ('pg-test','r5','Condition','test',2,'age_at_condition_start','HP:0000822','ABSENT','ONESELF'),
 ('pg-test','r6','Condition','test',3,'age_at_condition_start','MONDO:0005044','ABSENT','ONESELF'),
 ('pg-test','r7','Condition','test',3,'age_at_condition_start','HP:0000822','PRESENT','ONESELF'),
 ('pg-test','r8','DrugExposure','test',4,'age_at_exposure_start','MONDO:0005044','PRESENT',NULL),
 ('pg-test','r9','Condition','test',5,'age_at_condition_start','MONDO:0005044','PRESENT','NATURAL_MOTHER'),
 ('pg-test','r10','Condition','test',5,'age_at_condition_start','MONDO:0005044','ABSENT','ONESELF'),
 ('pg-test','r11','Condition','test',6,'age_at_condition_start','MONDO:0005044','PRESENT','ONESELF'),
 ('2026.10','other','Condition','test',1,'age_at_condition_start','ATC:C03','PRESENT','ONESELF');
GRANT USAGE ON SCHEMA counting TO dp_search_test;
GRANT SELECT ON counting.participant_ordinal,counting.participant_attribute,counting.event,counting.release_check TO dp_search_test;
