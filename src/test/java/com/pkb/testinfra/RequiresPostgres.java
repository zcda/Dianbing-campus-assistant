package com.pkb.testinfra;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.DriverManager;

/** Checks infrastructure before Spring starts, so offline test runs remain meaningful. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresPostgres.Condition.class)
public @interface RequiresPostgres {
    final class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            String url = System.getenv().getOrDefault("PKB_DB_URL", "jdbc:postgresql://localhost:5432/pkb");
            String user = System.getenv().getOrDefault("PKB_DB_USER", "pkb");
            String password = System.getenv().getOrDefault("PKB_DB_PASSWORD", "pkb123");
            try (var connection = DriverManager.getConnection(url + (url.contains("?") ? "&" : "?")
                    + "connectTimeout=2", user, password)) {
                return ConditionEvaluationResult.enabled("PostgreSQL available");
            } catch (Exception ex) {
                if (Boolean.parseBoolean(System.getenv("PKB_REQUIRE_POSTGRES"))) {
                    // CI 的数据库作业必须真实执行 SQL 测试，不能把连接失败记为跳过。
                    return ConditionEvaluationResult.enabled("PostgreSQL required; connection failure must fail the test context");
                }
                return ConditionEvaluationResult.disabled("PostgreSQL unavailable; start docker compose: "
                        + ex.getClass().getSimpleName());
            }
        }
    }
}
