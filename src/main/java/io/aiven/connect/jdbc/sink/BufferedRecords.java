/*
 * Copyright 2024 Aiven Oy and jdbc-connector-for-apache-kafka project contributors
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.connect.jdbc.sink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import io.aiven.connect.jdbc.util.TableDefinitions;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;

import io.aiven.connect.jdbc.dialect.DatabaseDialect;
import io.aiven.connect.jdbc.dialect.DatabaseDialect.StatementBinder;
import io.aiven.connect.jdbc.sink.metadata.FieldsMetadata;
import io.aiven.connect.jdbc.sink.metadata.SchemaPair;
import io.aiven.connect.jdbc.util.ColumnId;
import io.aiven.connect.jdbc.util.TableDefinition;
import io.aiven.connect.jdbc.util.TableId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.aiven.connect.jdbc.sink.JdbcSinkConfig.InsertMode.MULTI;

public class BufferedRecords {
    private static final Logger log = LoggerFactory.getLogger(BufferedRecords.class);

    private final TableId tableId;
    private final JdbcSinkConfig config;
    private final DatabaseDialect dbDialect;
    private final DbStructure dbStructure;
    private final Connection connection;
    private final List<SinkRecord> records = new ArrayList<>();
    private final List<SinkRecord> tombstoneRecords = new ArrayList<>();
    private SchemaPair currentSchemaPair;
    private FieldsMetadata fieldsMetadata;
    private TableDefinition tableDefinition;
    private TableDefinitions tableDefinitions;
    private PreparedStatement preparedStatement;
    private StatementBinder preparedStatementBinder;

    private PreparedStatement deletePreparedStatement;
    private StatementBinder deletePreparedStatementBinder;

    public BufferedRecords(
            final JdbcSinkConfig config,
            final TableId tableId,
            final DatabaseDialect dbDialect,
            final DbStructure dbStructure,
            final Connection connection
    ) {
        this.tableId = tableId;
        this.config = config;
        this.dbDialect = dbDialect;
        this.dbStructure = dbStructure;
        this.connection = connection;
    }

    public List<SinkRecord> add(final SinkRecord record) throws SQLException {
        final SchemaPair schemaPair = new SchemaPair(
                record.keySchema(),
                record.valueSchema()
        );

        log.debug("buffered records in list {}", records.size());

        if (currentSchemaPair == null) {
            reInitialize(schemaPair);
        }

        final List<SinkRecord> flushed;
        // Skip the schemaPair check for all tombstone records or the current schema pair matches
        if (record.value() == null || currentSchemaPair.equals(schemaPair)) {
            // Continue with current batch state
            if (config.deleteEnabled && isTombstone(record)) {
                tombstoneRecords.add(record);
            } else {
                records.add(record);
            }
            if (records.size() + tombstoneRecords.size() >= config.batchSize) {
                log.debug("Flushing buffered records {} and tombstone records {} "
                                + "after exceeding the configured batch size of {}.",
                        records.size(), tombstoneRecords.size(), config.batchSize);
                flushed = flush();
            } else {
                flushed = Collections.emptyList();
            }
        } else {
            // Each batch needs to have the same SchemaPair, so get the buffered records out, reset
            // state and re-attempt the add
            log.debug("Flushing buffered records after due to unequal schema pairs: "
                    + "current schemas: {}, next schemas: {}", currentSchemaPair, schemaPair);
            flushed = flush();
            currentSchemaPair = null;
            flushed.addAll(add(record));
        }
        return flushed;
    }

    private void prepareStatement() throws SQLException {
        close();
        if (!records.isEmpty()) {
            final String insertSql = config.insertMode == MULTI ? getMultiInsertSql() : getInsertSql();
            log.debug("Prepared SQL for insert mode {} with {} records: {}",
                    config.insertMode, records.size(), insertSql);
            preparedStatement = connection.prepareStatement(insertSql);
            preparedStatementBinder = dbDialect.statementBinder(
                    preparedStatement,
                    config.pkMode,
                    currentSchemaPair,
                    fieldsMetadata,
                    config.insertMode
            );
        }

        if (!tombstoneRecords.isEmpty()) {
            final String deleteSql = getDeleteSql();
            log.debug("Prepared SQL for tombstone with {} records: {}", records.size(), deleteSql);
            deletePreparedStatement = connection.prepareStatement(deleteSql);
            deletePreparedStatementBinder = dbDialect.statementBinder(
                    deletePreparedStatement,
                    config.pkMode,
                    currentSchemaPair,
                    fieldsMetadata,
                    config.insertMode
            );
        }
    }

    /**
     * Re-initialize everything that depends on the record schema
     */
    private void reInitialize(final SchemaPair schemaPair) throws SQLException {
        currentSchemaPair = schemaPair;
        fieldsMetadata = FieldsMetadata.extract(
                tableId.tableName(),
                config.pkMode,
                config.pkFields,
                config.fieldsWhitelist,
                currentSchemaPair
        );
        dbStructure.createOrAmendIfNecessary(
                config,
                connection,
                tableId,
                fieldsMetadata
        );

        tableDefinition = dbStructure.tableDefinitionFor(tableId, connection);
    }

    public List<SinkRecord> flush() throws SQLException {
        if (records.isEmpty() && tombstoneRecords.isEmpty()) {
            log.debug("Records and tombstone records are empty.");
            return new ArrayList<>();
        }

        prepareStatement();
        bindRecords();

        processBatch(records, "regular");
        processBatch(tombstoneRecords, "tombstone");

        final List<SinkRecord> flushedRecords = new ArrayList<>(records);
        flushedRecords.addAll(tombstoneRecords);

        records.clear();
        tombstoneRecords.clear();

        return flushedRecords;
    }

    private void processBatch(final List<SinkRecord> batchRecords, final String recordType) throws SQLException {
        if (batchRecords.isEmpty()) {
            log.debug("No {} records to process.", recordType);
            return;
        }

        int totalSuccessfulExecutionCount = 0;
        boolean successNoInfo = false;

        log.debug("Executing {} record batch...", recordType);
        final int[] updateCounts = recordType.equals("tombstone") ? executeDeleteBatch() : executeBatch();
        for (final int updateCount : updateCounts) {
            if (updateCount == Statement.SUCCESS_NO_INFO) {
                successNoInfo = true;
            } else {
                totalSuccessfulExecutionCount += updateCount;
            }
        }

        log.debug("Done executing {} record batch.", recordType);
        verifySuccessfulExecutions(totalSuccessfulExecutionCount, batchRecords, successNoInfo, recordType);
    }

    private void verifySuccessfulExecutions(final int totalSuccessfulExecutionCount,
                                            final List<SinkRecord> batchRecords,
                                            final boolean successNoInfo,
                                            final String recordType) {
        if (totalSuccessfulExecutionCount != batchRecords.size() && !successNoInfo) {
            switch (config.insertMode) {
                case INSERT:
                case MULTI:
                    throw new ConnectException(String.format(
                            "Update count (%d) did not sum up to total number of %s records (%d)",
                            totalSuccessfulExecutionCount,
                            recordType,
                            batchRecords.size()
                    ));
                case UPSERT:
                case UPDATE:
                    log.debug("{} {} records:{} resulting in totalSuccessfulExecutionCount:{}",
                            config.insertMode,
                            recordType,
                            batchRecords.size(),
                            totalSuccessfulExecutionCount
                    );
                    break;
                default:
                    throw new ConnectException("Unknown insert mode: " + config.insertMode);
            }
        }

        if (successNoInfo) {
            log.info("{} {} records:{} , but no count of the number of rows it affected is available",
                    config.insertMode,
                    recordType,
                    batchRecords.size()
            );
        }
    }

    private int[] executeBatch() throws SQLException {
        if (preparedStatement != null) {
            if (config.insertMode == MULTI) {
                preparedStatement.addBatch();
            }
            log.debug("Executing batch with insert mode {}", config.insertMode);
            return preparedStatement.executeBatch();
        }
        return new int[0];
    }

    private int[] executeDeleteBatch() throws SQLException {
        if (deletePreparedStatement != null) {
            log.debug("Executing batch delete");
            return deletePreparedStatement.executeBatch();
        }
        return new int[0];
    }

    private void bindRecords() throws SQLException {
        log.debug("Binding {} buffered records", records.size());
        int index = 1;
        for (final SinkRecord record : records) {
            if (config.insertMode == MULTI) {
                // All records are bound to the same prepared statement,
                // so when binding fields for record N (N > 0)
                // we need to start at the index where binding fields for record N - 1 stopped.
                index = preparedStatementBinder.bindRecord(index, record);
            } else {
                preparedStatementBinder.bindRecord(record);
            }
        }

        for (final SinkRecord tombstoneRecord : tombstoneRecords) {
            deletePreparedStatementBinder.bindTombstoneRecord(tombstoneRecord);
        }
        log.debug("Done binding records.");
    }

    private boolean isTombstone(final SinkRecord record) {
        // Tombstone records are events with a null value.
        return record.value() == null;
    }

    public void close() throws SQLException {
        log.info("Closing BufferedRecords with preparedStatement: {}", preparedStatement);
        if (preparedStatement != null) {
            preparedStatement.close();
            preparedStatement = null;
        }

        log.info("Closing BufferedRecords with deletePreparedStatement: {}", deletePreparedStatement);
        if (deletePreparedStatement != null) {
            deletePreparedStatement.close();
            deletePreparedStatement = null;
        }
    }

    private String getMultiInsertSql() {
        if (config.insertMode != MULTI) {
            throw new ConnectException(String.format(
                    "Multi-row first insert SQL unsupported by insert mode %s",
                    config.insertMode
            ));
        }
        try {
            return dbDialect.buildMultiInsertStatement(
                    tableId,
                    records.size(),
                    asColumns(fieldsMetadata.keyFieldNames),
                    asColumns(fieldsMetadata.nonKeyFieldNames)
            );
        } catch (final UnsupportedOperationException e) {
            throw new ConnectException(String.format(
                    "Write to table '%s' in MULTI mode is not supported with the %s dialect.",
                    tableId,
                    dbDialect.name()
            ));
        }
    }

    private String getInsertSql() {
        switch (config.insertMode) {
            case INSERT:
                return dbDialect.buildInsertStatement(
                        tableId,
                        tableDefinition,
                        asColumns(fieldsMetadata.keyFieldNames),
                        asColumns(fieldsMetadata.nonKeyFieldNames)
                );
            case UPSERT:
                if (fieldsMetadata.keyFieldNames.isEmpty()) {
                    throw new ConnectException(String.format(
                            "Write to table '%s' in UPSERT mode requires key field names to be known, check the"
                                    + " primary key configuration",
                            tableId
                    ));
                }
                try {
                    return dbDialect.buildUpsertQueryStatement(
                            tableId,
                            tableDefinition,
                            asColumns(fieldsMetadata.keyFieldNames),
                            asColumns(fieldsMetadata.nonKeyFieldNames)
                    );
                } catch (final UnsupportedOperationException e) {
                    throw new ConnectException(String.format(
                            "Write to table '%s' in UPSERT mode is not supported with the %s dialect.",
                            tableId,
                            dbDialect.name()
                    ));
                }
            case UPDATE:
                return dbDialect.buildUpdateStatement(
                        tableId,
                        tableDefinition,
                        asColumns(fieldsMetadata.keyFieldNames),
                        asColumns(fieldsMetadata.nonKeyFieldNames)
                );
            default:
                throw new ConnectException("Invalid insert mode");
        }
    }

    private String getDeleteSql() {
        return dbDialect.buildDeleteStatement(tableId,
                tombstoneRecords.size(),
                asColumns(fieldsMetadata.keyFieldNames));
    }

    private Collection<ColumnId> asColumns(final Collection<String> names) {
        return names.stream()
                .map(name -> new ColumnId(tableId, name))
                .collect(Collectors.toList());
    }
}
