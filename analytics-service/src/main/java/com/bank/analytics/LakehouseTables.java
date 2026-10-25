package com.bank.analytics;

import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.types.Types;

import java.util.Map;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;

/** Iceberg table definitions. Both tables are partitioned by day of {@code occurred_at}. */
public final class LakehouseTables {

    public static final String EVENTS = "events";
    public static final String TRANSACTIONS = "transactions";

    /** Every money-related event, as published (audit / replay / ad-hoc analysis). */
    public static final Schema EVENTS_SCHEMA = new Schema(
            required(1, "event_id", Types.StringType.get()),
            required(2, "event_type", Types.StringType.get()),
            required(3, "aggregate_type", Types.StringType.get()),
            required(4, "aggregate_id", Types.StringType.get()),
            optional(5, "producer", Types.StringType.get()),
            optional(6, "correlation_id", Types.StringType.get()),
            required(7, "topic", Types.StringType.get()),
            required(8, "kafka_partition", Types.IntegerType.get()),
            required(9, "kafka_offset", Types.LongType.get()),
            required(10, "occurred_at", Types.TimestampType.withZone()),
            required(11, "payload", Types.StringType.get()));

    /**
     * One row per money-movement event (event-sourced fact table). A transfer that changes state has several rows,
     * e.g. an interbank transfer has PENDING then SETTLED or REFUNDED; the latest row per {@code reference_id} is its status.
     */
    public static final Schema TRANSACTIONS_SCHEMA = new Schema(
            required(1, "event_id", Types.StringType.get()),
            required(2, "reference_id", Types.StringType.get()),
            required(3, "event_type", Types.StringType.get()),
            required(4, "transaction_type", Types.StringType.get()),
            required(5, "status", Types.StringType.get()),
            optional(6, "rail", Types.StringType.get()),
            optional(7, "customer_id", Types.StringType.get()),
            optional(8, "from_account", Types.StringType.get()),
            optional(9, "to_account", Types.StringType.get()),
            optional(10, "counterparty_ifsc", Types.StringType.get()),
            required(11, "amount", Types.DecimalType.of(19, 2)),
            required(12, "currency", Types.StringType.get()),
            optional(13, "utr", Types.StringType.get()),
            optional(14, "failure_code", Types.StringType.get()),
            optional(15, "correlation_id", Types.StringType.get()),
            required(16, "occurred_at", Types.TimestampType.withZone()),
            required(17, "business_date", Types.DateType.get()));

    private LakehouseTables() {
    }

    public static Table loadOrCreate(Catalog catalog, String namespace, String name, Schema schema) {
        TableIdentifier id = TableIdentifier.of(Namespace.of(namespace), name);
        if (catalog instanceof SupportsNamespaces namespaces && !namespaces.namespaceExists(Namespace.of(namespace))) {
            try {
                namespaces.createNamespace(Namespace.of(namespace));
            } catch (AlreadyExistsException ignored) {
                // created concurrently by another replica
            }
        }
        if (catalog.tableExists(id)) {
            return catalog.loadTable(id);
        }
        PartitionSpec spec = PartitionSpec.builderFor(schema).day("occurred_at").build();
        try {
            return catalog.createTable(id, schema, spec, Map.of(
                    TableProperties.FORMAT_VERSION, "2",
                    TableProperties.DEFAULT_FILE_FORMAT, "parquet",
                    TableProperties.PARQUET_COMPRESSION, "zstd",
                    TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED, "true",
                    TableProperties.METADATA_PREVIOUS_VERSIONS_MAX, "50"));
        } catch (AlreadyExistsException e) {
            return catalog.loadTable(id);
        }
    }
}
