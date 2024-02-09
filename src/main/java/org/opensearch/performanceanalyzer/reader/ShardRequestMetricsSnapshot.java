/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.performanceanalyzer.reader;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.SelectField;
import org.jooq.SelectHavingStep;
import org.jooq.impl.DSL;
import org.opensearch.performanceanalyzer.DBUtils;
import org.opensearch.performanceanalyzer.commons.metrics.AllMetrics;
import org.opensearch.performanceanalyzer.commons.metrics.MetricsConfiguration;
import org.opensearch.performanceanalyzer.metricsdb.MetricsDB;

/** Snapshot of start/end events generated by per shard operations like shardBulk/shardSearch. */
@SuppressWarnings("serial")
public class ShardRequestMetricsSnapshot implements Removable {
    private static final Logger LOG = LogManager.getLogger(ShardRequestMetricsSnapshot.class);

    private static final ArrayList<Field<?>> groupByRidOp =
            new ArrayList<Field<?>>() {
                {
                    this.add(DSL.field(DSL.name(Fields.RID.name()), String.class));
                    this.add(DSL.field(DSL.name(Fields.OPERATION.name()), String.class));
                }
            };

    private final DSLContext create;
    public final Long windowStartTime;
    private final String tableName;
    private static final Long EXPIRE_AFTER = 600000L;
    private List<Field<?>> columns;

    public enum Fields {
        SHARD_ID(AllMetrics.CommonDimension.SHARD_ID.toString()),
        INDEX_NAME(AllMetrics.CommonDimension.INDEX_NAME.toString()),
        RID(HttpRequestMetricsSnapshot.Fields.RID.toString()),
        TID("tid"),
        OPERATION(AllMetrics.CommonDimension.OPERATION.toString()),
        SHARD_ROLE(AllMetrics.CommonDimension.SHARD_ROLE.toString()),
        ST(HttpRequestMetricsSnapshot.Fields.ST.toString()),
        ET(HttpRequestMetricsSnapshot.Fields.ET.toString()),
        LAT(HttpRequestMetricsSnapshot.Fields.LAT.toString()),
        TUTIL("tUtil"),
        TTIME("ttime"),
        LATEST("latest"),
        DOC_COUNT(AllMetrics.ShardBulkMetric.DOC_COUNT.toString());

        private final String fieldValue;

        Fields(String fieldValue) {
            this.fieldValue = fieldValue;
        }

        @Override
        public String toString() {
            return fieldValue;
        }
    }

    public ShardRequestMetricsSnapshot(Connection conn, Long windowStartTime) throws Exception {
        this.create = DSL.using(conn, SQLDialect.SQLITE);
        this.windowStartTime = windowStartTime;
        this.tableName = "shard_rq_" + windowStartTime;

        // The order of names specified for bulk inserts needs to match the order of the columns
        // specified here.
        this.columns =
                new ArrayList<Field<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.RID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.TID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.ST.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.ET.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.DOC_COUNT.toString()), Long.class));
                    }
                };

        create.createTable(this.tableName).columns(this.columns).execute();
    }

    public void putStartMetric(Long startTime, Map<String, String> dimensions) {
        Map<Field<?>, String> dimensionMap = new HashMap<Field<?>, String>();
        for (Map.Entry<String, String> dimension : dimensions.entrySet()) {
            dimensionMap.put(
                    DSL.field(DSL.name(dimension.getKey()), String.class), dimension.getValue());
        }
        create.insertInto(DSL.table(this.tableName))
                .set(DSL.field(DSL.name(Fields.ST.toString()), Long.class), startTime)
                .set(dimensionMap)
                .execute();
    }

    public BatchBindStep startBatchPut() {
        List<Object> dummyValues = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            dummyValues.add(null);
        }
        return create.batch(create.insertInto(DSL.table(this.tableName)).values(dummyValues));
    }

    public void putEndMetric(Long endTime, Map<String, String> dimensions) {
        Map<Field<?>, String> dimensionMap = new HashMap<Field<?>, String>();
        for (Map.Entry<String, String> dimension : dimensions.entrySet()) {
            dimensionMap.put(
                    DSL.field(DSL.name(dimension.getKey()), String.class), dimension.getValue());
        }
        create.insertInto(DSL.table(this.tableName))
                .set(DSL.field(DSL.name(Fields.ET.toString()), Long.class), endTime)
                .set(dimensionMap)
                .execute();
    }

    public Result<Record> fetchAll() {
        return create.select().from(DSL.table(this.tableName)).fetch();
    }

    /**
     * Return per request latency.
     *
     * <p>Actual Table |shard|indexName|rid |tid |operation |role| st| et|
     * +-----+---------+-------+----+----------+----+-------------+-------------+ |0 |sonested
     * |2447782|7069|shardquery|NA | {null}|1535065340625| |0 |sonested |2447782|7069|shardquery|NA
     * |1535065340330| {null}| |0 |sonested |2447803|7069|shardfetch|NA | {null}|1535065344730| |0
     * |sonested |2447803|7069|shardfetch|NA |1535065344729| {null}| |0 |sonested
     * |2447781|7069|shardfetch|NA |1535065340227| {null}|
     *
     * <p>Latency Table |shard|indexName|rid |tid |operation |role| st| et| lat|
     * +-----+---------+-------+----+----------+----+-------------+-------------+-----+ |0 |sonested
     * |2447782|7069|shardquery|NA |1535065340330|1535065340625| 255| |0 |sonested
     * |2447803|7069|shardfetch|NA |1535065344729|1535065344730| 001|
     *
     * @return rows with latency of each shard request
     */
    public SelectHavingStep<Record> fetchLatency() {

        List<SelectField<?>> fields =
                new ArrayList<SelectField<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.RID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.TID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.ST.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.ET.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.DOC_COUNT.toString()), Double.class));
                        this.add(
                                DSL.field(Fields.ET.toString())
                                        .minus(DSL.field(Fields.ST.toString()))
                                        .as(DSL.name(Fields.LAT.toString())));
                    }
                };

        return create.select(fields)
                .from(groupByRidOpSelect())
                .where(
                        DSL.field(Fields.ET.toString())
                                .isNotNull()
                                .and(DSL.field(Fields.ST.toString()).isNotNull()));
    }

    /**
     * Return per operation latency. This is a performance optimization to avoid writing one entry
     * per request back into metricsDB. This function returns one row per operation.
     *
     * <p>Latency Table |shard|indexName|rid |tid |operation |role| st| et| lat|
     * +-----+---------+-------+----+----------+----+-------------+-------------+-----+ |0 |sonested
     * |2447782|7069|shardquery|NA |1535065340330|1535065340625| 255| |0 |sonested
     * |2447783|7069|shardquery|NA |1535065340330|1535065340635| 265| |0 |sonested
     * |2447803|7069|shardfetch|NA |1535065344729|1535065344730| 001| |0 |sonested
     * |2447804|7069|shardfetch|NA |1535065344729|1535065344732| 003|
     *
     * <p>Returned Table |shard|indexName|tid |operation |role|sum_lat|avg_lat|min_lat|max_lat|
     * +-----+---------+----+----------+----+-------------+-------------+-------+-------+-------+-------+
     * |0 |sonested |7069|shardquery|NA | 520| 260| 255| 265| |0 |sonested |7069|shardfetch|NA |
     * 004| 002| 001| 003|
     *
     * @return aggrated latency by ShardID, IndexName, Operation, and ShardRole.
     */
    public Result<Record> fetchLatencyByOp() {
        ArrayList<SelectField<?>> fields =
                new ArrayList<SelectField<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                        this.add(
                                DSL.sum(DSL.field(DSL.name(Fields.LAT.toString()), Double.class))
                                        .as(
                                                DBUtils.getAggFieldName(
                                                        Fields.LAT.toString(), MetricsDB.SUM)));
                        this.add(
                                DSL.avg(DSL.field(DSL.name(Fields.LAT.toString()), Double.class))
                                        .as(
                                                DBUtils.getAggFieldName(
                                                        Fields.LAT.toString(), MetricsDB.AVG)));
                        this.add(
                                DSL.min(DSL.field(DSL.name(Fields.LAT.toString()), Double.class))
                                        .as(
                                                DBUtils.getAggFieldName(
                                                        Fields.LAT.toString(), MetricsDB.MIN)));
                        this.add(
                                DSL.max(DSL.field(DSL.name(Fields.LAT.toString()), Double.class))
                                        .as(
                                                DBUtils.getAggFieldName(
                                                        Fields.LAT.toString(), MetricsDB.MAX)));
                        this.add(
                                DSL.count()
                                        .as(
                                                AllMetrics.ShardOperationMetric.SHARD_OP_COUNT
                                                        .toString()));
                        this.add(
                                DSL.sum(
                                                DSL.field(
                                                        DSL.name(Fields.DOC_COUNT.toString()),
                                                        Double.class))
                                        .as(AllMetrics.ShardBulkMetric.DOC_COUNT.toString()));
                    }
                };

        ArrayList<Field<?>> groupByFields =
                new ArrayList<Field<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                    }
                };

        return create.select(fields).from(fetchLatency()).groupBy(groupByFields).fetch();
    }

    /**
     * Return row per request.
     *
     * <p>Actual Table |shard|indexName|rid |tid |operation |role| st| et|
     * +-----+---------+-------+----+----------+----+-------------+-------------+ |0 |sonested
     * |2447782|7069|shardquery|NA | {null}|1535065340625| |0 |sonested |2447782|7069|shardquery|NA
     * |1535065340330| {null}| |0 |sonested |2447803|7069|shardfetch|NA | {null}|1535065344730| |0
     * |sonested |2447803|7069|shardfetch|NA |1535065344729| {null}| |0 |sonested
     * |2447781|7069|shardfetch|NA |1535065340227| {null}|
     *
     * <p>Latency Table windowStartTime = 1535065340330 endTime = 1535065345330 |shard|indexName|rid
     * |tid |operation |role| st| et|
     * +-----+---------+-------+----+----------+----+-------------+-------------+ |0 |sonested
     * |2447782|7069|shardquery|NA |1535065340330|1535065340625| |0 |sonested
     * |2447803|7069|shardfetch|NA |1535065344729|1535065344730| |0 |sonested
     * |2447781|7069|shardfetch|NA |1535065340227|1535065345330|
     *
     * @return aggregated latency rows for each shard request
     */
    public SelectHavingStep<Record> getCoalescedRequestsForTimeSpentInWindow() {
        Long endTime = this.windowStartTime + MetricsConfiguration.SAMPLING_INTERVAL;
        ArrayList<SelectField<?>> fields =
                new ArrayList<SelectField<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.RID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.TID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                    }
                };

        fields.add(
                DSL.greatest(
                                DSL.coalesce(
                                        DSL.max(DSL.field(Fields.ST.toString())),
                                        (this.windowStartTime)),
                                this.windowStartTime)
                        .as(DSL.name(Fields.ST.toString())));
        fields.add(
                DSL.least(DSL.coalesce(DSL.max(DSL.field(Fields.ET.toString())), endTime), endTime)
                        .as(DSL.name(Fields.ET.toString())));

        return create.select(fields).from(DSL.table(this.tableName)).groupBy(groupByRidOp);
    }

    public SelectHavingStep<Record> getTimeSpentPerRequest() {
        ArrayList<SelectField<?>> fields =
                new ArrayList<SelectField<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.RID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.TID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.ST.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.ET.toString()), Long.class));
                    }
                };

        fields.add(
                DSL.field(Fields.ET.toString())
                        .minus(DSL.field(Fields.ST.toString()))
                        .as(DSL.name(Fields.LAT.toString())));
        return create.select(fields).from(getCoalescedRequestsForTimeSpentInWindow());
    }

    public SelectHavingStep<Record> groupByRidOpSelect() {
        ArrayList<SelectField<?>> fields =
                new ArrayList<SelectField<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.RID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.TID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                        this.add(
                                DSL.max(DSL.field(Fields.DOC_COUNT.toString()))
                                        .as(DSL.name(Fields.DOC_COUNT.toString())));
                        this.add(
                                DSL.max(DSL.field(Fields.ST.toString()))
                                        .as(DSL.name(Fields.ST.toString())));
                        this.add(
                                DSL.max(DSL.field(Fields.ET.toString()))
                                        .as(DSL.name(Fields.ET.toString())));
                    }
                };

        return create.select(fields).from(DSL.table(this.tableName)).groupBy(groupByRidOp);
    }

    public SelectHavingStep<Record> requestsPerThreadSelect() {
        SelectHavingStep<Record> groupByRidOp = groupByRidOpSelect();
        List<SelectField<?>> fields =
                new ArrayList<SelectField<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.RID.toString()), String.class));
                        this.add(DSL.field(groupByRidOp.field(Fields.TID.toString())));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.ST.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.ET.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.DOC_COUNT.toString()), Double.class));
                        this.add(DSL.field(DSL.name(Fields.LATEST.toString()), Long.class));
                    }
                };
        SelectHavingStep<Record2<Long, String>> threadTable =
                create.select(
                                DSL.max(DSL.field(Fields.ST.toString(), Long.class))
                                        .as(Fields.LATEST.toString()),
                                DSL.field(DSL.name(Fields.TID.toString()), String.class)
                                        .as(Fields.TID.toString()))
                        .from(groupByRidOp)
                        .groupBy(DSL.field(Fields.TID.toString()));

        return create.select(fields)
                .from(groupByRidOp)
                .join(threadTable)
                .on(
                        threadTable
                                .field(DSL.field(Fields.TID.toString()))
                                .eq(groupByRidOp.field(Fields.TID.toString())));
    }

    /**
     * Fetch inflight requests, and ignore missing events. The intention of this function is to
     * identify requests that have a missing event and are no longer inflight. Once, we identify
     * such requests we simply ignore them in all metrics calculation. The key invariant of this
     * function is the fact that at any time there is a single active request on a thread. Hence, if
     * we see more than one active request on a thread we ignore all requests on that thread except
     * the latest one.
     *
     * <p>Actual Table |shard|indexName|rid |tid |operation |role| st| et|
     * +-----+---------+-------+----+----------+----+-------------+-------------+ |0 |sonested
     * |2447781|7069|shardfetch|NA |1535065340227| {null}| |0 |sonested |2447782|7069|shardquery|NA
     * | {null}|1535065340625| |0 |sonested |2447782|7069|shardquery|NA |1535065340330| {null}| |0
     * |sonested |2447803|7069|shardfetch|NA | {null}|1535065344730| |0 |sonested
     * |2447803|7069|shardfetch|NA |1535065344729| {null}|
     *
     * <p>Intermediate select |shard|indexName|rid |tid |operation |role| st| et| latest|
     * +-----+---------+-------+----+----------+----+-------------+-------------+-------------+ |0
     * |sonested |2447781|7069|shardfetch|NA |1535065340227| {null}|1535065344729| |0 |sonested
     * |2447782|7069|shardquery|NA |1535065340330|1535065340625|1535065344729| |0 |sonested
     * |2447803|7069|shardfetch|NA |1535065344729|1535065344730|1535065344729|
     *
     * <p>windowStartTime = 1535065340330 We ignore the first row as it is lower than the current
     * window and we have new requests executing on the same thread.
     *
     * <p>|shard|indexName|rid |tid |operation |role| st| et|
     * +-----+---------+-------+----+----------+----+-------------+-------------+ |0 |sonested
     * |2447782|7069|shardquery|NA |1535065340330|1535065340625| |0 |sonested
     * |2447803|7069|shardfetch|NA |1535065344729|1535065344730|
     *
     * @return fetched inflight requests
     */
    public SelectHavingStep<Record> fetchInflightSelect() {
        ArrayList<SelectField<?>> fields =
                new ArrayList<SelectField<?>>() {
                    {
                        this.add(DSL.field(DSL.name(Fields.SHARD_ID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.INDEX_NAME.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.RID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.TID.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.OPERATION.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.SHARD_ROLE.toString()), String.class));
                        this.add(DSL.field(DSL.name(Fields.ST.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.ET.toString()), Long.class));
                        this.add(DSL.field(DSL.name(Fields.DOC_COUNT.toString()), Long.class));
                    }
                };

        SelectHavingStep<Record> reqPerThread = requestsPerThreadSelect();

        return create.select(fields)
                .from(reqPerThread)
                .where(
                        DSL.field(Fields.ST.toString())
                                .isNotNull()
                                .and(
                                        DSL.field(Fields.ST.toString())
                                                .gt(this.windowStartTime)
                                                .or(
                                                        DSL.field(Fields.LATEST.toString())
                                                                .eq(
                                                                        DSL.field(
                                                                                Fields.ST
                                                                                        .toString()))))
                                .and(DSL.field(Fields.ET.toString()).isNull())
                                .and(
                                        DSL.field(Fields.ST.toString())
                                                .gt(this.windowStartTime - EXPIRE_AFTER)));
    }

    public SelectHavingStep<Record> fetchTotalTimeTable(
            SelectHavingStep<Record> timeSpentPerRequestSelect) {
        List<SelectField<?>> fields = new ArrayList<SelectField<?>>();
        fields.add(DSL.field(Fields.TID.toString()));
        fields.add(
                DSL.sum(DSL.field(Fields.LAT.toString(), Double.class))
                        .as(Fields.TTIME.toString()));
        return create.select(fields)
                .from(timeSpentPerRequestSelect)
                .groupBy(DSL.field(Fields.TID.toString()));
    }

    public Result<Record> fetchThreadUtilizationRatio() {
        return create.select().from(fetchThreadUtilizationRatioTable()).fetch();
    }

    /**
     * Calculate the percentage of time spent on a thread by each request in the current time
     * window.
     *
     * <p>Latency Table |shard|indexName|rid |tid |operation |role| st| et| lat|
     * +-----+---------+-------+----+----------+----+-------------+-------------+-----+ |0 |sonested
     * |2447782|7069|shardquery|NA |1535065340330|1535065340625| 255| |0 |sonested
     * |2447783|7069|shardquery|NA |1535065340330|1535065340635| 265| |0 |sonested
     * |2447803|7069|shardfetch|NA |1535065344729|1535065344730| 001| |0 |sonested
     * |2447804|7069|shardfetch|NA |1535065344729|1535065344732| 003|
     *
     * <p>ThreadUtilizationTable ttime = (255+265+001+003) tUtil = lat/ttime |shard|indexName|rid
     * |tid |operation |role| st| et| lat|ttime| tUtil|
     * +-----+---------+-------+----+----------+----+-------------+-------------+-----+-----+-----+
     * |0 |sonested |2447782|7069|shardquery|NA |1535065340330|1535065340625| 255| 524|0.4866| |0
     * |sonested |2447783|7069|shardquery|NA |1535065340330|1535065340635| 265| 524|0.5057| |0
     * |sonested |2447803|7069|shardfetch|NA |1535065344729|1535065344730| 001| 524|0.0019| |0
     * |sonested |2447804|7069|shardfetch|NA |1535065344729|1535065344732| 003| 524|0.0058
     *
     * @return thread utilization table
     */
    public SelectHavingStep<Record> fetchThreadUtilizationRatioTable() {
        ArrayList<SelectField<?>> requestAndTotalThreadTimeFields = new ArrayList<SelectField<?>>();
        SelectHavingStep<Record> timeSpentPerReq = getTimeSpentPerRequest();
        SelectHavingStep<Record> threadTable = fetchTotalTimeTable(timeSpentPerReq);
        requestAndTotalThreadTimeFields.addAll(Arrays.asList(timeSpentPerReq.fields()));
        requestAndTotalThreadTimeFields.add(threadTable.field(Fields.TTIME.toString()));
        SelectHavingStep<Record> requestAndTotalThreadTimeSelect =
                create.select(requestAndTotalThreadTimeFields)
                        .from(timeSpentPerReq)
                        .join(threadTable)
                        .on(
                                timeSpentPerReq
                                        .field(Fields.TID.toString(), String.class)
                                        .eq(
                                                threadTable.field(
                                                        Fields.TID.toString(), String.class)));

        ArrayList<SelectField<?>> tUtilFields = new ArrayList<SelectField<?>>();
        tUtilFields.addAll(Arrays.asList(requestAndTotalThreadTimeSelect.fields()));
        tUtilFields.add(
                requestAndTotalThreadTimeSelect
                        .field(Fields.LAT.toString())
                        .mul(DSL.val(1.0d))
                        .div(
                                requestAndTotalThreadTimeSelect.field(
                                        Fields.TTIME.toString(), Double.class))
                        .as(Fields.TUTIL.toString()));
        return create.select(tUtilFields).from(requestAndTotalThreadTimeSelect);
    }

    public String getTableName() {
        return this.tableName;
    }

    @Override
    public void remove() {
        create.dropTable(DSL.table(this.tableName)).execute();
    }

    public void rolloverInflightRequests(ShardRequestMetricsSnapshot prevSnap) {
        create.insertInto(DSL.table(this.tableName))
                .select(prevSnap.fetchInflightSelect())
                .execute();
        LOG.debug("Inflight shard requests");
        LOG.debug(() -> fetchAll());
    }
}
