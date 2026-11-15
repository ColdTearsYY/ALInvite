package com.alinvite.manager;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GateClaimSessionTest {
    @Test void initialAdmissionIsExclusiveUntilHiddenRegistrationDecisionFinishes() {
        var gate = new GateClaimSession(100);
        assertEquals(0, gate.beginLookup(1));
        long initial = gate.beginInitialLookup(1);
        assertNotEquals(0, initial);
        assertEquals(0, gate.beginInitialLookup(2));
        gate.finish(initial);
        assertEquals(0, gate.beginLookup(3));
        gate.ready();
        assertNotEquals(0, gate.beginLookup(4));
    }

    @Test void lookupAndRepeatedReadyNeverExtendOriginalDeadline() {
        var gate = new GateClaimSession(100);
        gate.ready();
        long lookup = gate.beginLookup(99);
        gate.ready();
        assertTrue(gate.expire(100));
        assertFalse(gate.claim(lookup, 100));
        assertFalse(gate.accepts(100));
        assertEquals(0, gate.beginLookup(101));
    }

    @Test void transactionCannotBeAcceptedAtTheExactDeadlineEvenBeforeTimeoutTick() {
        var gate = new GateClaimSession(100);
        gate.ready();
        long lookup = gate.beginLookup(99);
        assertFalse(gate.claim(lookup, 100));
        gate.finish(lookup);
        assertTrue(gate.expire(100));
    }

    @Test void acceptedTransactionMayFinishAfterDeadlineAndRetryItsDatabaseTransaction() {
        var gate = new GateClaimSession(100);
        gate.ready();
        long lookup = gate.beginLookup(99);
        assertTrue(gate.claim(lookup, 99));
        assertFalse(gate.expire(101));
        assertTrue(gate.claim(lookup, 102));
        gate.finish(lookup);
        assertTrue(gate.expire(103));
    }

    @Test void staleCompletionCannotClearOrAcceptANewAttempt() {
        var gate = new GateClaimSession(100);
        gate.ready();
        long old = gate.beginLookup(1);
        gate.finish(old);
        long next = gate.beginLookup(2);
        assertNotEquals(old, next);
        gate.finish(old);
        assertFalse(gate.claim(old, 3));
        assertTrue(gate.claim(next, 3));
        assertEquals(0, gate.beginLookup(4));
    }

    @Test void disconnectOrReloadRevokesAcceptedPermitAndCannotBeReopened() {
        var gate = new GateClaimSession(100);
        gate.ready();
        long token = gate.beginLookup(1);
        assertTrue(gate.claim(token, 2));
        gate.close();
        gate.ready();
        gate.finish(token);
        assertFalse(gate.claim(token, 3));
        assertEquals(0, gate.beginInitialLookup(4));
        assertEquals(0, gate.beginLookup(4));
        var replacement = new GateClaimSession(200);
        replacement.ready();
        assertTrue(replacement.claim(replacement.beginLookup(5), 5));
    }

    @Test void concurrentWakeupsStartOnlyOneLookup() throws Exception {
        var gate = new GateClaimSession(100);
        gate.ready();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<Callable<Long>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> gate.beginLookup(1));
            int started = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get() != 0) started++;
            assertEquals(1, started);
        }
    }

    @Test void failedLookupAllowsRetryWithoutResettingTheWindow() {
        var gate = new GateClaimSession(100);
        gate.ready();
        long first = gate.beginLookup(1);
        gate.finish(first);
        assertNotEquals(0, gate.beginLookup(99));
        assertTrue(gate.expire(100));
    }
}
