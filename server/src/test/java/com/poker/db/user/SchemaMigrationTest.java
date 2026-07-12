package com.poker.db.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationTest {

    @Test
    void freshSchemaRunsV1AndRepeatedMigrationIsIdempotent() {
        JdbcDataSource dataSource = dataSource();
        Flyway flyway = configuredFlyway(dataSource);

        flyway.migrate();
        flyway.migrate();

        assertEquals(1, constraintCount(dataSource));
        assertEquals(1, new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from information_schema.tables where table_name = 'APP_USER'",
                Integer.class));
    }

    @Test
    void nonEmptyPreT4SchemaIsBaselinedBeforeV1Runs() {
        JdbcDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table SPRING_SESSION (PRIMARY_ID varchar(72) primary key)");

        configuredFlyway(dataSource).migrate();

        assertEquals(1, constraintCount(dataSource));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"version\" = '0' and \"success\" = true",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"version\" = '1' and \"success\" = true",
                Integer.class));
    }

    private static Flyway configuredFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    private static int constraintCount(DataSource dataSource) {
        return new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where constraint_name = 'UK_APP_USER_PROVIDER_SUBJECT'",
                Integer.class);
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
