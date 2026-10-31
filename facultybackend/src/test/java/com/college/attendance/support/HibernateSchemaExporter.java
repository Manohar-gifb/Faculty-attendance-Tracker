package com.college.attendance.support;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyHbmImpl;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.hibernate.tool.schema.internal.exec.GenerationTargetToScript;
import org.hibernate.tool.schema.internal.exec.ScriptTargetOutputToFile;

import jakarta.persistence.Entity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Exports the REAL MySQL 8 DDL that Hibernate generates for the current
 * entity model, without any database connection. Used to derive the Flyway
 * V1 baseline from authoritative schema definitions (06-01) so the committed
 * baseline can never drift from the entities by hand-typing mistakes.
 */
public final class HibernateSchemaExporter {

    private HibernateSchemaExporter() {
    }

    /**
     * Write Hibernate's MySQL 8 schema export to target/schema-export/mysql.sql
     * and return the complete DDL text.
     */
    public static String exportMySqlDdl() throws IOException {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(Environment.DIALECT, org.hibernate.dialect.MySQLDialect.class)
                .applySetting(Environment.PHYSICAL_NAMING_STRATEGY, PhysicalNamingStrategyStandardImpl.class.getName())
                .disableAutoClose()
                .build();

        MetadataSources sources = new MetadataSources(registry);
        // Explicit registration keeps the exporter independent of scanning quirks.
        registerEntity(sources, "com.college.attendance.entity.User");
        registerEntity(sources, "com.college.attendance.entity.Department");
        registerEntity(sources, "com.college.attendance.entity.AttendanceLog");
        registerEntity(sources, "com.college.attendance.entity.LeaveRequest");
        registerEntity(sources, "com.college.attendance.entity.AuditLog");
        Metadata metadata = sources.buildMetadata();

        Path outDir = Paths.get("target", "schema-export");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("mysql.sql");
        Files.deleteIfExists(outFile);

        ScriptTargetOutputToFile scriptTarget = new ScriptTargetOutputToFile(outFile.toFile(), "UTF-8");
        GenerationTargetToScript target = new GenerationTargetToScript(scriptTarget, ";");
        target.prepare();
        try {
            SchemaCreatorImpl creator = new SchemaCreatorImpl(registry);
            creator.doCreation(metadata, false, target);
        } finally {
            target.release();
        }

        byte[] bytes = Files.readAllBytes(outFile);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void registerEntity(MetadataSources sources, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            if (clazz.isAnnotationPresent(Entity.class)) {
                sources.addAnnotatedClass(clazz);
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot register entity for schema export: " + className, e);
        }
    }
}
