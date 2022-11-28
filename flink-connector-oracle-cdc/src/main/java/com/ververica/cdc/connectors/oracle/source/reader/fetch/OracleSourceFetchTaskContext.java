/*
 * Copyright 2022 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.oracle.source.reader.fetch;

import org.apache.flink.table.types.logical.RowType;

import com.ververica.cdc.connectors.base.config.JdbcSourceConfig;
import com.ververica.cdc.connectors.base.dialect.JdbcDataSourceDialect;
import com.ververica.cdc.connectors.base.relational.JdbcSourceEventDispatcher;
import com.ververica.cdc.connectors.base.source.EmbeddedFlinkDatabaseHistory;
import com.ververica.cdc.connectors.base.source.meta.offset.Offset;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitBase;
import com.ververica.cdc.connectors.base.source.reader.external.JdbcSourceFetchTaskContext;
import com.ververica.cdc.connectors.oracle.source.config.OracleSourceConfig;
import com.ververica.cdc.connectors.oracle.source.meta.offset.RedoLogOffset;
import com.ververica.cdc.connectors.oracle.source.utils.OracleUtils;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.oracle.OracleChangeEventSourceMetricsFactory;
import io.debezium.connector.oracle.OracleConnection;
import io.debezium.connector.oracle.OracleConnectorConfig;
import io.debezium.connector.oracle.OracleDatabaseSchema;
import io.debezium.connector.oracle.OracleErrorHandler;
import io.debezium.connector.oracle.OracleOffsetContext;
import io.debezium.connector.oracle.OracleStreamingChangeEventSourceMetrics;
import io.debezium.connector.oracle.OracleTaskContext;
import io.debezium.connector.oracle.OracleTopicSelector;
import io.debezium.connector.oracle.SourceInfo;
import io.debezium.connector.oracle.logminer.LogMinerOracleOffsetContextLoader;
import io.debezium.data.Envelope;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.metrics.SnapshotChangeEventSourceMetrics;
import io.debezium.pipeline.source.spi.EventMetadataProvider;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables;
import io.debezium.schema.DataCollectionId;
import io.debezium.schema.TopicSelector;
import io.debezium.util.Collect;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/** The context for fetch task that fetching data of snapshot split from Oracle data source. */
public class OracleSourceFetchTaskContext extends JdbcSourceFetchTaskContext {

    private static final Logger LOG = LoggerFactory.getLogger(OracleSourceFetchTaskContext.class);

    private final OracleConnection connection;
    private final OracleEventMetadataProvider metadataProvider;

    private OracleDatabaseSchema databaseSchema;
    private OracleTaskContext taskContext;
    private OracleOffsetContext offsetContext;
    private SnapshotChangeEventSourceMetrics snapshotChangeEventSourceMetrics;
    private OracleStreamingChangeEventSourceMetrics streamingChangeEventSourceMetrics;
    private TopicSelector<TableId> topicSelector;
    private JdbcSourceEventDispatcher dispatcher;
    private ChangeEventQueue<DataChangeEvent> queue;
    private OracleErrorHandler errorHandler;

    public OracleSourceFetchTaskContext(
            JdbcSourceConfig sourceConfig,
            JdbcDataSourceDialect dataSourceDialect,
            OracleConnection connection) {
        super(sourceConfig, dataSourceDialect);
        this.connection = connection;
        this.metadataProvider = new OracleEventMetadataProvider();
    }

    @Override
    public void configure(SourceSplitBase sourceSplitBase) {
        // initial stateful objects
        final OracleConnectorConfig connectorConfig = getDbzConnectorConfig();
        this.topicSelector = OracleTopicSelector.defaultSelector(connectorConfig);
        EmbeddedFlinkDatabaseHistory.registerHistory(
                sourceConfig
                        .getDbzConfiguration()
                        .getString(EmbeddedFlinkDatabaseHistory.DATABASE_HISTORY_INSTANCE_NAME),
                sourceSplitBase.getTableSchemas().values());
        this.databaseSchema = OracleUtils.createOracleDatabaseSchema(connectorConfig);
        // todo logMiner or xStream
        this.offsetContext =
                loadStartingOffsetState(
                        new LogMinerOracleOffsetContextLoader(connectorConfig), sourceSplitBase);
        validateAndLoadDatabaseHistory(offsetContext, databaseSchema);

        this.taskContext = new OracleTaskContext(connectorConfig, databaseSchema);
        final int queueSize =
                sourceSplitBase.isSnapshotSplit()
                        ? Integer.MAX_VALUE
                        : getSourceConfig().getDbzConnectorConfig().getMaxQueueSize();
        this.queue =
                new ChangeEventQueue.Builder<DataChangeEvent>()
                        .pollInterval(connectorConfig.getPollInterval())
                        .maxBatchSize(connectorConfig.getMaxBatchSize())
                        .maxQueueSize(queueSize)
                        .maxQueueSizeInBytes(connectorConfig.getMaxQueueSizeInBytes())
                        .loggingContextSupplier(
                                () ->
                                        taskContext.configureLoggingContext(
                                                "oracle-cdc-connector-task"))
                        // do not buffer any element, we use signal event
                        // .buffering()
                        .build();
        this.dispatcher =
                new JdbcSourceEventDispatcher(
                        connectorConfig,
                        topicSelector,
                        databaseSchema,
                        queue,
                        connectorConfig.getTableFilters().dataCollectionFilter(),
                        DataChangeEvent::new,
                        metadataProvider,
                        schemaNameAdjuster);

        final OracleChangeEventSourceMetricsFactory changeEventSourceMetricsFactory =
                new OracleChangeEventSourceMetricsFactory(
                        new OracleStreamingChangeEventSourceMetrics(
                                taskContext, queue, metadataProvider, connectorConfig));
        this.snapshotChangeEventSourceMetrics =
                changeEventSourceMetricsFactory.getSnapshotMetrics(
                        taskContext, queue, metadataProvider);
        this.streamingChangeEventSourceMetrics =
                (OracleStreamingChangeEventSourceMetrics)
                        changeEventSourceMetricsFactory.getStreamingMetrics(
                                taskContext, queue, metadataProvider);
        this.errorHandler = new OracleErrorHandler(connectorConfig.getLogicalName(), queue);
    }

    @Override
    public OracleSourceConfig getSourceConfig() {
        return (OracleSourceConfig) sourceConfig;
    }

    public OracleConnection getConnection() {
        return connection;
    }

    @Override
    public OracleConnectorConfig getDbzConnectorConfig() {
        return (OracleConnectorConfig) super.getDbzConnectorConfig();
    }

    @Override
    public OracleOffsetContext getOffsetContext() {
        return offsetContext;
    }

    public SnapshotChangeEventSourceMetrics getSnapshotChangeEventSourceMetrics() {
        return snapshotChangeEventSourceMetrics;
    }

    public OracleStreamingChangeEventSourceMetrics getStreamingChangeEventSourceMetrics() {
        return streamingChangeEventSourceMetrics;
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    @Override
    public OracleDatabaseSchema getDatabaseSchema() {
        return databaseSchema;
    }

    @Override
    public RowType getSplitType(Table table) {
        return OracleUtils.getSplitType(table);
    }

    @Override
    public JdbcSourceEventDispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public ChangeEventQueue<DataChangeEvent> getQueue() {
        return queue;
    }

    @Override
    public Tables.TableFilter getTableFilter() {
        return getDbzConnectorConfig().getTableFilters().dataCollectionFilter();
    }

    @Override
    public Offset getStreamOffset(SourceRecord sourceRecord) {
        return OracleUtils.getRedoLogPosition(sourceRecord);
    }

    /** Loads the connector's persistent offset (if present) via the given loader. */
    private OracleOffsetContext loadStartingOffsetState(
            OffsetContext.Loader loader, SourceSplitBase oracleSplit) {
        Offset offset =
                oracleSplit.isSnapshotSplit()
                        ? RedoLogOffset.INITIAL_OFFSET
                        : oracleSplit.asStreamSplit().getStartingOffset();

        OracleOffsetContext oracleOffsetContext =
                (OracleOffsetContext) loader.load(offset.getOffset());

        return oracleOffsetContext;
    }

    private void validateAndLoadDatabaseHistory(
            OracleOffsetContext offset, OracleDatabaseSchema schema) {
        schema.initializeStorage();
        schema.recover(offset);
    }

    /** Copied from debezium for accessing here. */
    public static class OracleEventMetadataProvider implements EventMetadataProvider {
        @Override
        public Instant getEventTimestamp(
                DataCollectionId source, OffsetContext offset, Object key, Struct value) {
            if (value == null) {
                return null;
            }
            final Struct sourceInfo = value.getStruct(Envelope.FieldName.SOURCE);
            if (source == null) {
                return null;
            }
            final Long timestamp = sourceInfo.getInt64(SourceInfo.TIMESTAMP_KEY);
            return timestamp == null ? null : Instant.ofEpochMilli(timestamp);
        }

        @Override
        public Map<String, String> getEventSourcePosition(
                DataCollectionId source, OffsetContext offset, Object key, Struct value) {
            if (value == null) {
                return null;
            }
            final Struct sourceInfo = value.getStruct(Envelope.FieldName.SOURCE);
            if (source == null) {
                return null;
            }
            final String scn = sourceInfo.getString(SourceInfo.SCN_KEY);
            return Collect.hashMapOf(SourceInfo.SCN_KEY, scn == null ? "null" : scn);
        }

        @Override
        public String getTransactionId(
                DataCollectionId source, OffsetContext offset, Object key, Struct value) {
            if (value == null) {
                return null;
            }
            final Struct sourceInfo = value.getStruct(Envelope.FieldName.SOURCE);
            if (source == null) {
                return null;
            }
            return sourceInfo.getString(SourceInfo.TXID_KEY);
        }
    }
}
