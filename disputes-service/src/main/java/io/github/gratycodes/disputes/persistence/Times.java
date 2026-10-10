package io.github.gratycodes.disputes.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Timestamps go over the wire as second-precision UTC, e.g. 2026-09-01T09:00:00Z. */
public final class Times {

    private Times() {}

    public static String iso(Instant t) {
        return t == null ? null : DateTimeFormatter.ISO_INSTANT.format(t.truncatedTo(ChronoUnit.SECONDS));
    }

    public static String iso(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : iso(t.toInstant());
    }

    public static OffsetDateTime parse(String iso) {
        return OffsetDateTime.parse(iso);
    }
}
