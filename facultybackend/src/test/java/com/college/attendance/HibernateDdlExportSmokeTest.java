package com.college.attendance;

import com.college.attendance.support.HibernateSchemaExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helper/util test, gated behind -Dddlgen.enabled=true so it never runs in
 * the normal suite. Regenerates target/schema-export/mysql.sql from the
 * current entity model exactly the way Hibernate 6 / MySQLDialect would build
 * it during ddl-auto runs — the authoritative source for the Flyway V1
 * baseline (06-01).
 *
 * Run once with:
 *   ./mvnw test -Dtest=HibernateDdlExportSmokeTest -Dddlgen.enabled=true
 */
@EnabledIfSystemProperty(named = "ddlgen.enabled", matches = "true")
class HibernateDdlExportSmokeTest {

    @Test
    void exportsMySqlDdlForCurrentEntities() throws Exception {
        String ddl = HibernateSchemaExporter.exportMySqlDdl();
        assertTrue(ddl.contains("create table users"),
                "the export must contain the users table");
        assertTrue(ddl.contains("create table attendance_logs"),
                "the export must contain the attendance_logs table");
        System.out.println("[ddl-export] wrote target/schema-export/mysql.sql (" + ddl.length() + " chars)");
        Path out = Paths.get("target", "schema-export", "mysql.sql");
        assertTrue(java.nio.file.Files.exists(out));
    }
}
