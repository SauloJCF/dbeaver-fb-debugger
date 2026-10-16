package dev.fbdebug.dbeaver.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbdebug.dbeaver.psql.RoutineParser;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.testing.DifferentialHarness;
import dev.fbdebug.dbeaver.testing.TestDatabase;

class RoutineSourceLoaderTest {

    private static TestDatabase database;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute(
                "CREATE DOMAIN DM_VALOR AS NUMERIC(15,2)",
                "CREATE TABLE DT_TABELA (NOME VARCHAR(15) CHARACTER SET WIN1252, QTD SMALLINT)",
                """
                CREATE OR ALTER PROCEDURE DT_TIPOS (V DM_VALOR, TV TYPE OF DM_VALOR,
                    NOME TYPE OF COLUMN DT_TABELA.NOME, FLAG BOOLEAN, GRANDE BIGINT, D DOUBLE PRECISION,
                    TXT BLOB SUB_TYPE TEXT, C CHAR(3) NOT NULL, Q SMALLINT = 4)
                RETURNS (SOMA DM_VALOR, NOME_LONGO VARCHAR(40), NEGADO BOOLEAN, METADE DOUBLE PRECISION,
                    GRANDE_MAIS BIGINT, TXT_MAIS VARCHAR(100), C_MAIS VARCHAR(10), TERCO DECIMAL(9,3))
                AS
                BEGIN
                  SOMA = V + TV;
                  NOME_LONGO = NOME || '!';
                  NEGADO = NOT FLAG;
                  METADE = D / 2;
                  GRANDE_MAIS = GRANDE + Q;
                  TXT_MAIS = TXT || ' fim';
                  C_MAIS = C || '|';
                  TERCO = V / 3;
                END""");
    }

    @Test
    void headerIsRebuiltFromMetadata() throws Exception {
        String source;
        try (Connection connection = database.connect()) {
            source = RoutineSourceLoader.loadProcedure(connection, "DT_TIPOS");
        }
        ProcedureDefinition procedure = RoutineParser.parseProcedure(source);

        assertEquals(List.of(
                "\"DM_VALOR\"",
                "TYPE OF \"DM_VALOR\"",
                "TYPE OF COLUMN \"DT_TABELA\".\"NOME\"",
                "BOOLEAN",
                "BIGINT",
                "DOUBLE PRECISION",
                "BLOB SUB_TYPE 1 CHARACTER SET UTF8",
                "CHAR(3) CHARACTER SET UTF8",
                "SMALLINT"), procedure.inputs().stream().map(ParameterDeclaration::type).toList());
        assertTrue(procedure.inputs().get(7).notNull());
        assertEquals("4", procedure.inputs().get(8).defaultValue().text());
        assertEquals("DECIMAL(9,3)", procedure.outputs().get(7).type());
        assertTrue(source.contains("\nAS\nBEGIN\n  SOMA = V + TV;\n"), source);
    }

    @Test
    void domainsAndTypeOfBehaveLikeTheServer() throws Exception {
        new DifferentialHarness(database).assertSameOutputs("DT_TIPOS", new BigDecimal("10.555"),
                new BigDecimal("0.01"), "ação", true, 9_000_000_000L, 5.5, "texto", "ab");
    }
}
