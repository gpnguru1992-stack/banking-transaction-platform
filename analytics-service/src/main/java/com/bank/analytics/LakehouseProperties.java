package com.bank.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * {@code catalog} is passed unchanged to Iceberg's {@code CatalogUtil.buildIcebergCatalog}
 * (catalog-impl, uri, warehouse, io-impl, s3.*), so the same service can write to Nessie, AWS Glue or a REST catalog.
 * A flush happens when {@code flushRows} rows are buffered or the oldest buffered row is {@code flushInterval} old.
 */
@ConfigurationProperties(prefix = "banking.lakehouse")
public record LakehouseProperties(
        String catalogName,
        String namespace,
        Map<String, String> catalog,
        Integer flushRows,
        Duration flushInterval) {

    public LakehouseProperties {
        catalogName = catalogName == null ? "lakehouse" : catalogName;
        namespace = namespace == null ? "banking" : namespace;
        catalog = catalog == null ? Map.of() : catalog;
        flushRows = flushRows == null ? 1000 : flushRows;
        flushInterval = flushInterval == null ? Duration.ofSeconds(10) : flushInterval;
    }
}
