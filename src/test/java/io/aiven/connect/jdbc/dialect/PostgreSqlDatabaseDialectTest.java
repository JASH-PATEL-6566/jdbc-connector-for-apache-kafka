/*
 * Copyright 2019 Aiven Oy
 * Copyright 2017 Confluent Inc.
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

package io.aiven.connect.jdbc.dialect;

import java.util.List;

import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;

import io.aiven.connect.jdbc.util.TableId;

import org.junit.Test;

public class PostgreSqlDatabaseDialectTest extends BaseDialectTest<PostgreSqlDatabaseDialect> {

    @Override
    protected PostgreSqlDatabaseDialect createDialect() {
        return new PostgreSqlDatabaseDialect(sourceConfigWithUrl("jdbc:postgresql://something"));
    }

    @Test
    public void shouldMapPrimitiveSchemaTypeToSqlTypes() {
        assertPrimitiveMapping(Type.INT8, "SMALLINT");
        assertPrimitiveMapping(Type.INT16, "SMALLINT");
        assertPrimitiveMapping(Type.INT32, "INT");
        assertPrimitiveMapping(Type.INT64, "BIGINT");
        assertPrimitiveMapping(Type.FLOAT32, "REAL");
        assertPrimitiveMapping(Type.FLOAT64, "DOUBLE PRECISION");
        assertPrimitiveMapping(Type.BOOLEAN, "BOOLEAN");
        assertPrimitiveMapping(Type.BYTES, "BYTEA");
        assertPrimitiveMapping(Type.STRING, "TEXT");
    }

    @Test
    public void shouldMapDecimalSchemaTypeToDecimalSqlType() {
        assertDecimalMapping(0, "DECIMAL");
        assertDecimalMapping(3, "DECIMAL");
        assertDecimalMapping(4, "DECIMAL");
        assertDecimalMapping(5, "DECIMAL");
    }

    @Test
    public void shouldMapDataTypes() {
        verifyDataTypeMapping("SMALLINT", Schema.INT8_SCHEMA);
        verifyDataTypeMapping("SMALLINT", Schema.INT16_SCHEMA);
        verifyDataTypeMapping("INT", Schema.INT32_SCHEMA);
        verifyDataTypeMapping("BIGINT", Schema.INT64_SCHEMA);
        verifyDataTypeMapping("REAL", Schema.FLOAT32_SCHEMA);
        verifyDataTypeMapping("DOUBLE PRECISION", Schema.FLOAT64_SCHEMA);
        verifyDataTypeMapping("BOOLEAN", Schema.BOOLEAN_SCHEMA);
        verifyDataTypeMapping("TEXT", Schema.STRING_SCHEMA);
        verifyDataTypeMapping("BYTEA", Schema.BYTES_SCHEMA);
        verifyDataTypeMapping("DECIMAL", Decimal.schema(0));
        verifyDataTypeMapping("DATE", Date.SCHEMA);
        verifyDataTypeMapping("TIME", Time.SCHEMA);
        verifyDataTypeMapping("TIMESTAMP", Timestamp.SCHEMA);
    }

    @Test
    public void shouldMapDateSchemaTypeToDateSqlType() {
        assertDateMapping("DATE");
    }

    @Test
    public void shouldMapTimeSchemaTypeToTimeSqlType() {
        assertTimeMapping("TIME");
    }

    @Test
    public void shouldMapTimestampSchemaTypeToTimestampSqlType() {
        assertTimestampMapping("TIMESTAMP");
    }

    @Test
    public void shouldBuildCreateQueryStatement() {
        final String expected = readQueryResourceForThisTest("create_table");
        final String actual = dialect.buildCreateTableStatement(tableId, sinkRecordFields);
        assertQueryEquals(expected, actual);
    }

    @Test
    public void shouldBuildAlterTableStatement() {
        final String[] expected = {
            readQueryResourceForThisTest("alter_table")
        };
        final List<String> actual = dialect.buildAlterTable(tableId, sinkRecordFields);
        assertStatements(expected, actual);
    }

    @Test
    public void shouldBuildUpsertStatement() {
        final String expected = readQueryResourceForThisTest("upsert0");
        final String actual = dialect.buildUpsertQueryStatement(tableId, pkColumns, columnsAtoD);
        assertQueryEquals(expected, actual);
    }

    @Test
    public void createOneColNoPk() {
        final String expected = readQueryResourceForThisTest("create_table_one_col_no_pk");
        verifyCreateOneColNoPk(expected);
    }

    @Test
    public void createOneColOnePk() {
        final String expected = readQueryResourceForThisTest("create_table_one_col_one_pk");
        verifyCreateOneColOnePk(expected);
    }

    @Test
    public void createThreeColTwoPk() {
        final String expected = readQueryResourceForThisTest("create_table_three_cols_two_pks");
        verifyCreateThreeColTwoPk(expected);
    }

    @Test
    public void alterAddOneCol() {
        final String expected = readQueryResourceForThisTest("alter_add_one_col");
        verifyAlterAddOneCol(expected);
    }

    @Test
    public void alterAddTwoCol() {
        final String expected = readQueryResourceForThisTest("alter_add_two_cols");
        verifyAlterAddTwoCols(expected);
    }

    @Test
    public void upsert() {
        final String expected = readQueryResourceForThisTest("upsert1");
        final TableId customer = tableId("Customer");
        final String actual = dialect.buildUpsertQueryStatement(
            customer,
            columns(customer, "id"),
            columns(customer, "name", "salary", "address")
        );
        assertQueryEquals(expected, actual);
    }

    @Test
    public void shouldSanitizeUrlWithoutCredentialsInProperties() {
        assertSanitizedUrl(
            "jdbc:postgresql://localhost/test?user=fred&ssl=true",
            "jdbc:postgresql://localhost/test?user=fred&ssl=true"
        );
    }

    @Test
    public void shouldSanitizeUrlWithCredentialsInUrlProperties() {
        assertSanitizedUrl(
            "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true",
            "jdbc:postgresql://localhost/test?user=fred&password=****&ssl=true"
        );
    }
}
