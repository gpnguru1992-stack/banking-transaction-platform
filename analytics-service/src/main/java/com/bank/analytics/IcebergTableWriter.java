package com.bank.analytics;

import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.kafka.common.TopicPartition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Buffers rows for one Iceberg table and appends them in one commit per flush. The last Kafka offset per partition
 * is written into the same snapshot's summary, so data and position commit atomically: after a crash the service
 * resumes after the last committed offset and rows are neither lost nor duplicated.
 */
public class IcebergTableWriter {

    static final String OFFSET_PREFIX = "banking.kafka-offset.";

    private final Table table;
    private final String consumerGroup;
    private final List<Record> buffer = new ArrayList<>();
    private final Map<TopicPartition, Long> bufferedOffsets = new HashMap<>();
    private final Map<TopicPartition, Long> committedOffsets;

    public IcebergTableWriter(Table table, String consumerGroup) {
        this.table = table;
        this.consumerGroup = consumerGroup;
        this.committedOffsets = readCommittedOffsets(table, consumerGroup);
    }

    /** False when this offset is already in the table (replay after restart or rebalance). */
    public boolean add(Record row, TopicPartition partition, long offset) {
        if (offset <= committedOffsets.getOrDefault(partition, -1L)) {
            return false;
        }
        if (row != null) {
            buffer.add(row);
        }
        bufferedOffsets.merge(partition, offset, Math::max);
        return true;
    }

    public int bufferedRows() {
        return buffer.size();
    }

    public boolean hasPendingOffsets() {
        return !bufferedOffsets.isEmpty();
    }

    public Map<TopicPartition, Long> committedOffsets() {
        return Map.copyOf(committedOffsets);
    }

    /** Writes buffered rows as Parquet (one file per day partition) and commits them with the offsets. Returns rows written. */
    public int flush() {
        if (bufferedOffsets.isEmpty()) {
            return 0;
        }
        table.refresh();
        AppendFiles append = table.newAppend();
        for (DataFile file : writeDataFiles()) {
            append.appendFile(file);
        }
        bufferedOffsets.forEach((tp, offset) -> append.set(key(consumerGroup, tp), Long.toString(offset)));
        append.commit();
        int rows = buffer.size();
        committedOffsets.putAll(bufferedOffsets);
        buffer.clear();
        bufferedOffsets.clear();
        return rows;
    }

    /** Drops buffered rows of revoked partitions; the next owner re-reads them from the committed offset. */
    public void discard() {
        buffer.clear();
        bufferedOffsets.clear();
    }

    private List<DataFile> writeDataFiles() {
        PartitionKey key = new PartitionKey(table.spec(), table.schema());
        InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
        Map<PartitionKey, List<Record>> byPartition = new LinkedHashMap<>();
        for (Record row : buffer) {
            key.partition(wrapper.wrap(row));
            byPartition.computeIfAbsent(key.copy(), k -> new ArrayList<>()).add(row);
        }
        List<DataFile> files = new ArrayList<>();
        for (Map.Entry<PartitionKey, List<Record>> entry : byPartition.entrySet()) {
            String fileName = FileFormat.PARQUET.addExtension(UUID.randomUUID().toString());
            OutputFile out = table.io().newOutputFile(
                    table.locationProvider().newDataLocation(table.spec(), entry.getKey(), fileName));
            DataWriter<Record> writer;
            try {
                writer = Parquet.writeData(out)
                        .schema(table.schema())
                        .createWriterFunc(GenericParquetWriter::buildWriter)
                        .withSpec(table.spec())
                        .withPartition(entry.getKey())
                        .overwrite()
                        .build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try (writer) {
                entry.getValue().forEach(writer::write);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.add(writer.toDataFile());
        }
        return files;
    }

    /** Newest offset per partition, walking back through snapshots (other replicas commit other partitions). */
    static Map<TopicPartition, Long> readCommittedOffsets(Table table, String consumerGroup) {
        Map<TopicPartition, Long> offsets = new HashMap<>();
        String prefix = OFFSET_PREFIX + consumerGroup + ".";
        Snapshot snapshot = table.currentSnapshot();
        while (snapshot != null) {
            for (Map.Entry<String, String> e : snapshot.summary().entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    String tp = e.getKey().substring(prefix.length());
                    int dash = tp.lastIndexOf('-');
                    TopicPartition partition = new TopicPartition(tp.substring(0, dash), Integer.parseInt(tp.substring(dash + 1)));
                    offsets.putIfAbsent(partition, Long.parseLong(e.getValue()));
                }
            }
            snapshot = snapshot.parentId() == null ? null : table.snapshot(snapshot.parentId());
        }
        return offsets;
    }

    private static String key(String consumerGroup, TopicPartition tp) {
        return OFFSET_PREFIX + consumerGroup + "." + tp.topic() + "-" + tp.partition();
    }
}
