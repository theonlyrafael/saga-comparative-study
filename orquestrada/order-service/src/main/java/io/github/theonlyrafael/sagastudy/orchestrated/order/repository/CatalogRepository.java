package io.github.theonlyrafael.sagastudy.orchestrated.order.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.CatalogAsset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CatalogAsset> find(String id) {
        return jdbc
                .query(
                        "SELECT asset_id, catalog_version, unit_price FROM asset_catalog WHERE"
                            + " asset_id = ?",
                        (row, n) ->
                                new CatalogAsset(
                                        row.getString(1), row.getString(2), row.getBigDecimal(3)),
                        id)
                .stream()
                .findFirst();
    }
}
