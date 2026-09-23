package com.agentsflex.graph.neo4j.transaction;

import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import com.agentsflex.graph.transaction.GraphTransaction;

import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 验证 Neo4j 显式事务的提交、回滚、关闭幂等性和终态保护。
 */
public class Neo4jGraphTransactionContractTest {
    @Test
    public void commitShouldCloseTransactionAndSessionExactlyOnce() {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger transactionCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        GraphTransaction transaction = transaction(commits, transactionCloses, sessionCloses);

        transaction.commit();
        transaction.close();

        assertEquals(1, commits.get());
        assertEquals(1, transactionCloses.get());
        assertEquals(1, sessionCloses.get());
        try {
            transaction.query();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("closed transaction must reject query access");
    }

    @Test
    public void rollbackShouldBeIdempotentAndCloseResources() {
        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger transactionCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        GraphTransaction transaction = transaction(rollbacks, transactionCloses, sessionCloses);

        transaction.rollback();
        transaction.rollback();

        assertEquals(1, rollbacks.get());
        assertEquals(1, transactionCloses.get());
        assertEquals(1, sessionCloses.get());
    }

    @Test
    public void commitFailureShouldBeMappedAndStillReleaseResources() {
        AtomicInteger transactionCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        GraphTransaction transaction = new Neo4jGraphTransaction(session(sessionCloses),
            failingTransaction("commit", transactionCloses), new Neo4jGraphStoreConfig());
        try {
            transaction.commit();
        } catch (GraphException error) {
            assertEquals(GraphErrorCode.TRANSACTION_FAILED, error.getCode());
            assertEquals(1, transactionCloses.get());
            assertEquals(1, sessionCloses.get());
            return;
        }
        throw new AssertionError("commit failure must be mapped to TRANSACTION_FAILED");
    }

    @Test
    public void rollbackFailureShouldBeMappedAndStillReleaseResources() {
        AtomicInteger transactionCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        GraphTransaction transaction = new Neo4jGraphTransaction(session(sessionCloses),
            failingTransaction("rollback", transactionCloses), new Neo4jGraphStoreConfig());
        try {
            transaction.rollback();
        } catch (GraphException error) {
            assertEquals(GraphErrorCode.TRANSACTION_FAILED, error.getCode());
            assertEquals(1, transactionCloses.get());
            assertEquals(1, sessionCloses.get());
            return;
        }
        throw new AssertionError("rollback failure must be mapped to TRANSACTION_FAILED");
    }

    private static GraphTransaction transaction(final AtomicInteger action,
                                                final AtomicInteger transactionCloses,
                                                final AtomicInteger sessionCloses) {
        Transaction driverTransaction = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
            new Class<?>[]{Transaction.class}, (proxy, method, args) -> {
                if ("commit".equals(method.getName()) || "rollback".equals(method.getName())) action.incrementAndGet();
                if ("close".equals(method.getName())) transactionCloses.incrementAndGet();
                return null;
            });
        return new Neo4jGraphTransaction(session(sessionCloses), driverTransaction,
            new Neo4jGraphStoreConfig());
    }

    private static Transaction failingTransaction(final String operation, final AtomicInteger closes) {
        return (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
            new Class<?>[]{Transaction.class}, (proxy, method, args) -> {
                if (operation.equals(method.getName())) throw new RuntimeException("driver failure");
                if ("close".equals(method.getName())) closes.incrementAndGet();
                return null;
            });
    }

    private static Session session(final AtomicInteger closes) {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
            new Class<?>[]{Session.class}, (proxy, method, args) -> {
                if ("close".equals(method.getName())) closes.incrementAndGet();
                return null;
            });
    }
}
