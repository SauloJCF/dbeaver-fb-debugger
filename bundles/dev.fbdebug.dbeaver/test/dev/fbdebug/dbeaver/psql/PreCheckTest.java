package dev.fbdebug.dbeaver.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.ast.UnsupportedConstruct;

/** Pré-check (issue 08): uma Rotina por construção recusada, com a posição de cada uma. */
class PreCheckTest {

    @Test
    void routineWithOnlySupportedConstructsHasNoFindings() {
        assertEquals(List.of(), PreCheck.check("""
                CREATE PROCEDURE P (A INTEGER)
                RETURNS (R VARCHAR(20))
                AS
                DECLARE VARIABLE I INTEGER = 0;
                BEGIN
                  L: WHILE (I < A) DO
                  BEGIN
                    I = I + 1;
                    IF (I = 2) THEN CONTINUE L;
                    UPDATE T SET X = :I WHERE CURRENT_DATE > '2020-01-01';
                    IF (ROW_COUNT = 0) THEN LEAVE;
                  END
                  SELECT NOME FROM T WHERE ID = :A INTO :R;
                  INSERT INTO T (ID) VALUES (:A) RETURNING ID INTO :I;
                  MERGE INTO T USING RDB$DATABASE ON T.ID = :A WHEN NOT MATCHED THEN INSERT (ID) VALUES (:A);
                  UPDATE OR INSERT INTO T (ID) VALUES (:A);
                  DELETE FROM T WHERE ID = :A;
                  EXECUTE STATEMENT ('SELECT 1 FROM RDB$DATABASE') WITH COMMON TRANSACTION INTO :I;
                  EXIT;
                END"""));
    }

    @Test
    void whereCurrentOf() {
        // O cursor viria de um FOR SELECT ... AS CURSOR (issue 05); aqui só a Fatia DML importa.
        assertRefused(UnsupportedConstruct.WHERE_CURRENT_OF, new SourcePosition(4, 5), """
                CREATE PROCEDURE P AS
                BEGIN
                  UPDATE T SET X = 1
                    WHERE /* posicionado */ CURRENT OF C;
                END""");
    }

    @Test
    void executeStatementOnExternal() {
        assertRefused(UnsupportedConstruct.EXECUTE_STATEMENT_ON_EXTERNAL, new SourcePosition(5, 5), """
                CREATE PROCEDURE P RETURNS (R INTEGER) AS
                BEGIN
                  EXECUTE STATEMENT 'SELECT 1 FROM RDB$DATABASE'
                    WITH COMMON TRANSACTION
                    ON EXTERNAL DATA SOURCE 'localhost:outro.fdb'
                    INTO :R;
                END""");
    }

    @Test
    void executeStatementWithAutonomousTransaction() {
        assertRefused(UnsupportedConstruct.EXECUTE_STATEMENT_AUTONOMOUS, new SourcePosition(3, 39), """
                CREATE PROCEDURE P AS
                BEGIN
                  EXECUTE STATEMENT ('DELETE FROM T') WITH AUTONOMOUS TRANSACTION;
                END""");
    }

    @Test
    void executeStatementAsUser() {
        assertRefused(UnsupportedConstruct.EXECUTE_STATEMENT_AS_USER, new SourcePosition(3, 37), """
                CREATE PROCEDURE P AS
                BEGIN
                  EXECUTE STATEMENT 'DELETE FROM T' AS USER 'OUTRO' PASSWORD 'x' ROLE 'R';
                END""");
    }

    @Test
    void inAutonomousTransaction() {
        assertRefused(UnsupportedConstruct.IN_AUTONOMOUS_TRANSACTION, new SourcePosition(4, 5), """
                CREATE PROCEDURE P AS
                BEGIN
                  IF (1 = 1) THEN
                    IN AUTONOMOUS TRANSACTION DO
                      INSERT INTO LOG (TEXTO) VALUES ('fora da transação');
                END""");
    }

    @Test
    void rowCountInsideDml() {
        assertRefused(UnsupportedConstruct.ROW_COUNT_IN_DML, new SourcePosition(4, 31), """
                CREATE PROCEDURE P AS
                BEGIN
                  UPDATE T SET X = 1;
                  INSERT INTO LOG (N) VALUES (ROW_COUNT);
                END""");
    }

    @Test
    void everyRefusedConstructIsListedInOrder() {
        List<PreCheck.Finding> findings = PreCheck.check("""
                CREATE PROCEDURE P AS
                BEGIN
                  IN AUTONOMOUS TRANSACTION DO
                  BEGIN
                    EXECUTE STATEMENT 'X' ON EXTERNAL 'outro.fdb' AS USER 'U';
                  END
                  DELETE FROM T WHERE CURRENT OF C;
                END""");

        assertEquals(List.of(new SourcePosition(3, 3), new SourcePosition(5, 27), new SourcePosition(5, 51),
                new SourcePosition(7, 17)), findings.stream().map(PreCheck.Finding::position).toList());
    }

    @Test
    void textThatDoesNotParseIsRefusedAtTheErrorPosition() {
        List<PreCheck.Finding> findings = PreCheck.check("""
                CREATE PROCEDURE P AS
                DECLARE X INTEGER;
                BEGIN
                  X = 1;
                  FOR SELECT ID FROM T INTO :X DO
                    X = X + 1;
                END""");

        assertEquals(1, findings.size());
        PreCheck.Finding finding = findings.get(0);
        assertEquals(new SourcePosition(5, 7), finding.position());
        assertTrue(finding.description().contains("'FOR' (linha 5, coluna 3)"), finding.description());
    }

    private static void assertRefused(UnsupportedConstruct construct, SourcePosition position, String source) {
        assertEquals(List.of(new PreCheck.Finding(position, construct.syntax() + " não é suportado pelo debugger")),
                PreCheck.check(source));
    }
}
