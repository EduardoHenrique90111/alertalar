package com.alertalar.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Connection string do Neon colada numa variável só (DATABASE_URL). */
class ConexaoDatabaseUrlTest {

    @Test
    @DisplayName("Converte a connection string do Neon para JDBC, usuário e senha")
    void neon() {
        Map<String, Object> p = ConexaoDatabaseUrl.converter(
                "postgresql://neondb_owner:npg_Ab%40cd+1@ep-cool-123456-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
        assertEquals("jdbc:postgresql://ep-cool-123456-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require", p.get("DB_URL"));
        assertEquals("neondb_owner", p.get("DB_USERNAME"));
        assertEquals("npg_Ab@cd+1", p.get("DB_PASSWORD"));
    }

    @Test
    @DisplayName("Aceita o comando psql copiado, aspas, porta e o formato JDBC; ignora texto que não é conexão")
    void variacoes() {
        Map<String, Object> psql = ConexaoDatabaseUrl.converter("psql 'postgres://u:s@db.exemplo.com:6543/app'");
        assertEquals("jdbc:postgresql://db.exemplo.com:6543/app?sslmode=require", psql.get("DB_URL"));
        assertEquals("s", psql.get("DB_PASSWORD"));
        assertEquals(Map.of("DB_URL", "jdbc:postgresql://h/db?sslmode=require"),
                ConexaoDatabaseUrl.converter("jdbc:postgresql://h/db?sslmode=require"));
        assertTrue(ConexaoDatabaseUrl.converter("minha senha").isEmpty());
        assertTrue(ConexaoDatabaseUrl.converter("mysql://u:s@h/db").isEmpty());
    }

    @Test
    @DisplayName("No ambiente: DATABASE_URL vira DB_URL/DB_USERNAME/DB_PASSWORD, que continuam valendo se definidas")
    void ambiente() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("teste", Map.of(
                "DATABASE_URL", "postgresql://u:segredo@h.neon.tech/neondb?sslmode=require")));
        new ConexaoDatabaseUrl().postProcessEnvironment(env, null);
        assertEquals("jdbc:postgresql://h.neon.tech/neondb?sslmode=require", env.getProperty("DB_URL"));
        assertEquals("segredo", env.getProperty("DB_PASSWORD"));

        StandardEnvironment comDbUrl = new StandardEnvironment();
        comDbUrl.getPropertySources().addFirst(new MapPropertySource("teste", Map.of(
                "DATABASE_URL", "postgresql://u:segredo@h.neon.tech/neondb", "DB_URL", "jdbc:postgresql://outro/db")));
        new ConexaoDatabaseUrl().postProcessEnvironment(comDbUrl, null);
        assertEquals("jdbc:postgresql://outro/db", comDbUrl.getProperty("DB_URL"));

        StandardEnvironment sem = new StandardEnvironment();
        new ConexaoDatabaseUrl().postProcessEnvironment(sem, null);
        assertNull(sem.getPropertySources().get(ConexaoDatabaseUrl.FONTE));
    }
}
