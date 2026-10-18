package io.github.dcfaight.platformlab.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dcfaight.platformlab.environment.EnvironmentRepository;
import io.github.dcfaight.platformlab.environment.EnvironmentService;
import io.github.dcfaight.platformlab.environment.CreateEnvironmentRequest;
import io.github.dcfaight.platformlab.kubernetes.EnvironmentProvisioner;
import io.github.dcfaight.platformlab.kubernetes.ProvisioningException;
import io.github.dcfaight.platformlab.operation.OperationRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class WorkerTests {
    @Container
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        io.github.dcfaight.platformlab.TestDatabase.migrate(POSTGRES);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerRepository repository;
    @Autowired OperationRepository operations;
    @Autowired EnvironmentRepository environments;
    @Autowired EnvironmentService service;
    private EnvironmentProvisioner provisioner;
    private ProvisioningWorker worker;
    private final WorkerProperties properties = new WorkerProperties(Duration.ofSeconds(30), 3,
            Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMinutes(5));

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE audit_events, idempotency_requests, operation_events, operations, environments CASCADE");
        provisioner = mock(EnvironmentProvisioner.class);
        worker = new ProvisioningWorker(repository, environments, provisioner, properties);
    }

    @Test
    void simultaneousClaimersOnlyOneOwnsOperation() throws Exception {
        UUID id = create();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var tasks = List.of(
                    pool.submit(() -> { start.await(); return repository.claim(properties); }),
                    pool.submit(() -> { start.await(); return repository.claim(properties); }));
            var first = tasks.get(0).get(10, TimeUnit.SECONDS);
            var second = tasks.get(1).get(10, TimeUnit.SECONDS);
            assertNotEquals(first.isPresent(), second.isPresent());
            assertEquals(id, first.orElseGet(second::orElseThrow).id());
            assertEquals(1, operations.find(id).orElseThrow().attemptCount());
            assertEquals(List.of("CLAIMED"), eventTypes(id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredOwnerCannotWriteEvenBeforeLeaseIsReclaimed() {
        UUID id = create();
        var stale = repository.claim(properties).orElseThrow();
        expire(id);
        assertFalse(repository.provisioned(stale));
        assertFalse(repository.waiting(stale, Duration.ZERO));
        assertFalse(repository.retry(stale, "TRANSIENT", "Temporary failure.", Duration.ZERO));
        assertFalse(repository.fail(stale, "FAILED", "Failure."));
        assertFalse(repository.succeed(stale));
        assertEquals("PROVISIONING", environmentStatus(id));
        assertEquals(List.of("CLAIMED"), eventTypes(id));

        var recovered = repository.claim(properties).orElseThrow();
        assertNotEquals(stale.leaseToken(), recovered.leaseToken());
        assertEquals(2, operations.find(id).orElseThrow().attemptCount());
        assertFalse(repository.succeed(stale));
        assertTrue(repository.succeed(recovered));
        assertEquals("READY", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "CLAIMED", "SUCCEEDED"), eventTypes(id));
    }

    @Test
    void successfulReadinessCompletesAndEventsAreDurable() {
        UUID id = create();
        when(provisioner.isReady(any())).thenReturn(true);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals("READY", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "PROGRESS", "PROVISIONED", "SUCCEEDED"), eventTypes(id));
        assertEquals(List.of("CLAIMED", "PROVISIONING_STARTED", "PROVISIONED", "ENVIRONMENT_READY"), eventCodes(id));
        assertTrue(operations.events(id).stream().allMatch(event -> event.createdAt() != null));
        worker.poll();
        verify(provisioner, times(1)).provision(any(), any(), any());
    }

    @Test
    void queuedOperationSurvivesRestartWithItsScheduleAndIdentity() {
        UUID id = create();
        jdbc.update("UPDATE operations SET next_attempt_at = clock_timestamp() + interval '1 hour' WHERE id = ?", id);
        var queued = operations.find(id).orElseThrow();
        worker = new ProvisioningWorker(new WorkerRepository(jdbc), environments, provisioner, properties);
        worker.poll();
        assertEquals(queued, operations.find(id).orElseThrow());
        verifyNoInteractions(provisioner);

        when(provisioner.isReady(any())).thenReturn(true);
        due(id);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals(1, operations.count(queued.environmentId()));
    }

    @Test
    void restartWaitsForClaimExpiryThenResumesWithADurableRecoveryEvent() {
        UUID id = create();
        var abandoned = repository.claim(properties).orElseThrow();
        var started = operations.find(id).orElseThrow().startedAt();
        worker = new ProvisioningWorker(new WorkerRepository(jdbc), environments, provisioner, properties);
        worker.poll();
        verifyNoInteractions(provisioner);
        assertEquals(1, operations.events(id).size());

        expire(id);
        when(provisioner.isReady(any())).thenReturn(true);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals(started, operations.find(id).orElseThrow().startedAt());
        assertEquals("LEASE_RECOVERED", operations.events(id).get(1).code());
        assertTrue(operations.events(id).get(1).message().contains("previous lease expired"));
        assertFalse(repository.owns(abandoned));
        assertFalse(repository.fail(abandoned, "FAILED", "Stale failure."));
        assertEquals("READY", environmentStatus(id));
    }

    @Test
    void restartAfterLastAcceptedProvisioningContinuesWithoutAnotherWrite() {
        UUID id = create();
        for (int i = 0; i < properties.maxAttempts() - 1; i++) {
            repository.claim(properties).orElseThrow();
            expire(id);
        }
        doAnswer(invocation -> {
            assertTrue(((java.util.function.BooleanSupplier) invocation.getArgument(2)).getAsBoolean());
            throw new Error("Simulated process termination after Kubernetes accepted resources");
        }).when(provisioner).provision(any(), any(), any());
        assertThrows(Error.class, worker::poll);
        assertEquals("RUNNING", operations.find(id).orElseThrow().status());
        assertNull(jdbc.queryForObject("SELECT provisioned_at FROM operations WHERE id = ?",
                java.sql.Timestamp.class, id));

        expire(id);
        // Matching resources need no mutation callback, just like a terminating/absent namespace on DELETE.
        doNothing().when(provisioner).provision(any(), any(), any());
        when(provisioner.isReady(any())).thenReturn(true);
        worker = new ProvisioningWorker(new WorkerRepository(jdbc), environments, provisioner, properties);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals("READY", environmentStatus(id));
        assertEquals(properties.maxAttempts() + 1, operations.find(id).orElseThrow().attemptCount());
        assertEquals("LEASE_RECOVERED", operations.events(id).get(properties.maxAttempts() + 1).code());
        assertEquals(1, eventCodes(id).stream().filter("PROVISIONING_STARTED"::equals).count());
        worker.poll();
        verify(provisioner, times(2)).provision(any(), eq(id), any());
    }

    @Test
    void provisionerMilestonesAreRecordedInOrderOnceAndTraceToTheOperation() {
        UUID id = create();
        doAnswer(invocation -> {
            var progress = (io.github.dcfaight.platformlab.kubernetes.OperationProgress) invocation.getArgument(2);
            progress.milestone(io.github.dcfaight.platformlab.kubernetes.OperationProgress.Milestone.NAMESPACE_CREATED);
            progress.milestone(io.github.dcfaight.platformlab.kubernetes.OperationProgress.Milestone.NAMESPACE_CREATED);
            progress.milestone(io.github.dcfaight.platformlab.kubernetes.OperationProgress.Milestone.WORKLOAD_CREATED);
            return null;
        }).when(provisioner).provision(any(), any(), any());
        when(provisioner.isReady(any())).thenReturn(true);
        worker.poll();
        assertEquals(List.of("CLAIMED", "PROVISIONING_STARTED", "NAMESPACE_CREATED", "WORKLOAD_CREATED",
                "PROVISIONED", "ENVIRONMENT_READY"), eventCodes(id));
        var correlation = operations.find(id).orElseThrow().correlationId();
        var events = operations.events(id);
        for (int i = 1; i < events.size(); i++) {
            assertFalse(events.get(i).createdAt().isBefore(events.get(i - 1).createdAt()));
        }
        assertTrue(events.stream().allMatch(event -> java.util.Objects.equals(correlation, event.correlationId())));
        assertEquals("Kubernetes accepted the environment namespace.", events.get(2).message());
    }

    @Test
    void milestonesAreFencedByTheLeaseAndOncePerOperation() {
        UUID id = create();
        var stale = repository.claim(properties).orElseThrow();
        var milestone = io.github.dcfaight.platformlab.kubernetes.OperationProgress.Milestone.PROVISIONING_STARTED;
        assertTrue(repository.progress(stale, milestone));
        assertFalse(repository.progress(stale, milestone));
        expire(id);
        assertFalse(repository.progress(stale, io.github.dcfaight.platformlab.kubernetes.OperationProgress
                .Milestone.NAMESPACE_CREATED));
        var recovered = repository.claim(properties).orElseThrow();
        assertFalse(repository.progress(recovered, milestone));
        assertEquals(List.of("CLAIMED", "PROVISIONING_STARTED", "LEASE_RECOVERED"), eventCodes(id));
    }

    @Test
    void milestoneRecordingFailureDoesNotDisturbProvisioning() {
        UUID id = create();
        var failing = spy(new WorkerRepository(jdbc));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .when(failing).progress(any(), any());
        when(provisioner.isReady(any())).thenReturn(true);
        worker = new ProvisioningWorker(failing, environments, provisioner, properties);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals(List.of("CLAIMED", "PROVISIONED", "SUCCEEDED"), eventTypes(id));
    }

    @Test
    void deletingOperationCascadesDurableEvents() {
        UUID id = create();
        worker.poll();
        assertFalse(operations.events(id).isEmpty());
        jdbc.update("DELETE FROM audit_events WHERE operation_id = ?", id);
        jdbc.update("DELETE FROM idempotency_requests WHERE operation_id = ?", id);
        jdbc.update("DELETE FROM operations WHERE id = ?", id);
        assertTrue(operations.events(id).isEmpty());
    }

    @Test
    void waitingDoesNotMarkReadyOrBurnProvisioningAttemptsAndSurvivesRestart() {
        UUID id = create();
        worker.poll();
        assertEquals("PENDING", operations.find(id).orElseThrow().status());
        assertEquals("WAITING", operations.find(id).orElseThrow().progressStep());
        assertEquals("PROVISIONING", environmentStatus(id));
        worker = new ProvisioningWorker(new WorkerRepository(jdbc), environments, provisioner, properties);
        for (int i = 0; i < 6; i++) {
            due(id);
            worker.poll();
        }
        assertEquals(1, operations.find(id).orElseThrow().attemptCount());
        verify(provisioner, times(1)).provision(any(), any(), any());
        when(provisioner.isReady(any())).thenReturn(true);
        due(id);
        worker.poll();
        assertEquals("READY", environmentStatus(id));
    }

    @Test
    void neverReadyFailsAtDurableDeadline() {
        UUID id = create();
        worker.poll();
        jdbc.update("UPDATE operations SET started_at = clock_timestamp() - interval '6 minutes' WHERE id = ?", id);
        due(id);
        worker.poll();
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals("READINESS_TIMEOUT", operations.find(id).orElseThrow().failureCode());
        assertEquals("The environment did not become ready before the provisioning deadline.",
                operations.find(id).orElseThrow().failureMessage());
        assertEquals("FAILED", environmentStatus(id));
        assertEquals("FAILED", eventTypes(id).get(eventTypes(id).size() - 1));
    }

    @Test
    void transientFailuresUseDatabaseBackoffAndAreBounded() {
        UUID id = create();
        var failure = mock(ProvisioningException.class);
        when(failure.retryable()).thenReturn(true);
        when(failure.code()).thenReturn("KUBERNETES_UNAVAILABLE");
        when(failure.getMessage()).thenReturn("Kubernetes is unavailable.");
        doThrow(failure).when(provisioner).provision(any(), any(), any());
        worker.poll();
        assertEquals("PENDING", operations.find(id).orElseThrow().status());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                SELECT next_attempt_at > updated_at FROM operations WHERE id = ?
                """, Boolean.class, id));
        for (int i = 0; i < 2; i++) {
            due(id);
            worker.poll();
        }
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals(3, operations.find(id).orElseThrow().attemptCount());
        assertEquals("KUBERNETES_UNAVAILABLE", operations.find(id).orElseThrow().failureCode());
        assertEquals(List.of("CLAIMED", "PROGRESS", "RETRY", "CLAIMED", "RETRY", "CLAIMED", "FAILED"), eventTypes(id));
    }

    @Test
    void permanentFailureDoesNotRetry() {
        UUID id = create();
        var failure = mock(ProvisioningException.class);
        when(failure.retryable()).thenReturn(false);
        when(failure.code()).thenReturn("KUBERNETES_FORBIDDEN");
        when(failure.getMessage()).thenReturn("Kubernetes access was denied.");
        doThrow(failure).when(provisioner).provision(any(), any(), any());
        worker.poll();
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals("FAILED", environmentStatus(id));
        assertTrue(repository.claim(properties).isEmpty());
        assertEquals(List.of("CLAIMED", "PROGRESS", "FAILED"), eventTypes(id));
    }

    @Test
    void readinessTransientErrorsAreBoundedWithoutReprovisioning() {
        UUID id = create();
        var failure = mock(ProvisioningException.class);
        when(failure.retryable()).thenReturn(true);
        when(failure.code()).thenReturn("KUBERNETES_UNAVAILABLE");
        when(failure.getMessage()).thenReturn("Kubernetes is unavailable.");
        when(provisioner.isReady(any())).thenThrow(failure);
        for (int i = 0; i < properties.maxAttempts(); i++) {
            due(id);
            worker.poll();
        }
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals(1, operations.find(id).orElseThrow().attemptCount());
        verify(provisioner, times(1)).provision(any(), any(), any());
        verify(provisioner, times(3)).isReady(any());
    }

    @Test
    void leaseExpiringDuringHttpCannotCompleteOrAppendEvents() {
        UUID id = create();
        when(provisioner.isReady(any())).thenAnswer(invocation -> {
            expire(id);
            return true;
        });
        worker.poll();
        assertEquals("RUNNING", operations.find(id).orElseThrow().status());
        assertEquals("PROVISIONING", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "PROGRESS", "PROVISIONED"), eventTypes(id));
        when(provisioner.isReady(any())).thenReturn(true);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals("READY", environmentStatus(id));
        verify(provisioner, times(1)).provision(any(), any(), any());
    }

    @Test
    void crashOnLastAttemptFailsAtomicallyAfterRestart() {
        UUID id = create();
        for (int i = 0; i < properties.maxAttempts(); i++) {
            assertEquals("RUNNING", repository.claim(properties).orElseThrow().status());
            expire(id);
        }
        var restart = new WorkerRepository(jdbc);
        doAnswer(invocation -> {
            ((java.util.function.BooleanSupplier) invocation.getArgument(2)).getAsBoolean();
            fail("An exhausted reclaim must not issue a Kubernetes write");
            return null;
        }).when(provisioner).provision(any(), any(), any());
        worker = new ProvisioningWorker(restart, environments, provisioner, properties);
        worker.poll();
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals(4, operations.find(id).orElseThrow().attemptCount());
        assertEquals("ATTEMPTS_EXHAUSTED", operations.find(id).orElseThrow().failureCode());
        assertEquals("Provisioning did not complete within its attempt limits.",
                operations.find(id).orElseThrow().failureMessage());
        assertEquals("FAILED", environmentStatus(id));
        assertTrue(restart.claim(properties).isEmpty());
    }

    @Test
    void unexpectedExceptionIsSanitizedPersistedAndNotSwallowed() {
        UUID id = create();
        doThrow(new IllegalStateException("secret-token-response-body")).when(provisioner).provision(any(), any(), any());
        var thrown = assertThrows(IllegalStateException.class, worker::poll);
        assertFalse(thrown.getMessage().contains("secret"));
        assertNull(thrown.getCause());
        var operation = operations.find(id).orElseThrow();
        assertEquals("FAILED", operation.status());
        assertEquals("INTERNAL_ERROR", operation.failureCode());
        assertEquals("An internal provisioning error occurred.", operation.failureMessage());
        assertEquals(List.of("CLAIMED", "PROGRESS", "FAILED"), eventTypes(id));
    }

    @Test
    void deletionWaitsForAbsenceAndNeverReprovisions() {
        UUID id = delete();
        var createId = jdbc.queryForObject("""
                SELECT id FROM operations WHERE environment_id = ? AND type = 'CREATE_ENVIRONMENT'
                """, UUID.class, operations.find(id).orElseThrow().environmentId());
        when(provisioner.delete(any(), eq(createId), any())).thenReturn(false, false, true);
        for (int i = 0; i < 2; i++) {
            due(id);
            worker.poll();
            assertEquals("PENDING", operations.find(id).orElseThrow().status());
            assertEquals("DELETING", environmentStatus(id));
            var events = operations.events(id);
            assertEquals("Waiting for the environment namespace to disappear.",
                    events.get(events.size() - 1).message());
        }

        due(id);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals("DELETED", operations.find(id).orElseThrow().progressStep());
        assertEquals("DELETED", environmentStatus(id));
        verify(provisioner, never()).provision(any(), any(), any());
        verify(provisioner, times(3)).delete(any(), eq(createId), any());
        assertEquals(1, operations.find(id).orElseThrow().attemptCount());
        assertEquals(1, eventCodes(id).stream().filter("DELETION_STARTED"::equals).count());
        assertEquals("DELETION_STARTED", eventCodes(id).get(1));
        assertEquals("ENVIRONMENT_DELETED", eventCodes(id).get(eventCodes(id).size() - 1));
    }

    @Test
    void deletionCrashExhaustionDoesNotAllowAnotherDestructiveRequest() {
        UUID id = delete();
        for (int i = 0; i < properties.maxAttempts(); i++) {
            repository.claim(properties).orElseThrow();
            expire(id);
        }
        when(provisioner.delete(any(), any(), any())).thenAnswer(invocation -> {
            ((java.util.function.BooleanSupplier) invocation.getArgument(2)).getAsBoolean();
            fail("An exhausted reclaim must not issue a new DELETE");
            return false;
        });
        worker.poll();
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals("ATTEMPTS_EXHAUSTED", operations.find(id).orElseThrow().failureCode());
        assertEquals("Deletion did not complete within its attempt limits.",
                operations.find(id).orElseThrow().failureMessage());
        assertEquals("DELETE_FAILED", environmentStatus(id));
    }

    @Test
    void deletionCrashExhaustionStillAllowsTerminatingNamespaceToFinish() {
        UUID id = delete();
        for (int i = 0; i < properties.maxAttempts(); i++) {
            repository.claim(properties).orElseThrow();
            expire(id);
        }
        // A terminating namespace does not invoke the destructive-request lease callback.
        when(provisioner.delete(any(), any(), any())).thenReturn(false);
        for (int i = 0; i < properties.maxAttempts() + 2; i++) {
            due(id);
            worker.poll();
            assertEquals("PENDING", operations.find(id).orElseThrow().status());
            assertEquals("DELETING", environmentStatus(id));
        }
        assertEquals(properties.maxAttempts() + 1, operations.find(id).orElseThrow().attemptCount());
        doReturn(true).when(provisioner).delete(any(), any(), any());
        due(id);
        worker.poll();
        assertEquals("DELETED", environmentStatus(id));
    }

    @Test
    void expiredCreateLeaseBeforeProvisioningDoesNotStartExternalWork() {
        UUID id = create();
        UUID environmentId = operations.find(id).orElseThrow().environmentId();
        var expiring = spy(new EnvironmentRepository(jdbc));
        doAnswer(invocation -> {
            expire(id);
            return invocation.callRealMethod();
        }).when(expiring).find(environmentId);
        worker = new ProvisioningWorker(repository, expiring, provisioner, properties);
        worker.poll();
        verify(provisioner, never()).provision(any(), any(), any());
        assertEquals("RUNNING", operations.find(id).orElseThrow().status());
        assertEquals(List.of("CLAIMED"), eventTypes(id));
    }

    @Test
    void createLeaseCallbackIsFencedAfterFailureAndDeleteAcceptance() {
        UUID id = create();
        var stale = repository.claim(properties).orElseThrow();
        java.util.function.BooleanSupplier leaseValid = () -> repository.owns(stale);
        assertTrue(leaseValid.getAsBoolean());
        expire(id);
        var recovered = repository.claim(properties).orElseThrow();
        assertTrue(repository.fail(recovered, "FAILED", "Creation failed."));
        var accepted = service.delete(stale.environmentId(), operator());
        assertEquals("DELETING", accepted.environmentStatus());
        assertFalse(leaseValid.getAsBoolean());
        assertFalse(repository.provisioned(stale));
        assertFalse(repository.succeed(stale));
    }

    @Test
    void originalCreateOperationIsUniqueAndDeletionClaimsItsExactId() {
        UUID id = delete();
        UUID environmentId = operations.find(id).orElseThrow().environmentId();
        UUID original = jdbc.queryForObject("""
                SELECT id FROM operations WHERE environment_id = ? AND type = 'CREATE_ENVIRONMENT'
                """, UUID.class, environmentId);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO operations (id, environment_id, type, status,
                            next_attempt_at, created_at, updated_at)
                        VALUES (?, ?, 'CREATE_ENVIRONMENT', 'SUCCEEDED', now(), now(), now())
                        """, UUID.randomUUID(), environmentId));
        assertEquals(original, repository.claim(properties).orElseThrow().createOperationId());
    }

    @Test
    void deletionTransientFailuresAreBoundedAndRetryCreatesNewHistory() {
        UUID id = delete();
        when(provisioner.delete(any(), any(), any())).thenThrow(
                new ProvisioningException("KUBERNETES_UNAVAILABLE", "Kubernetes is unavailable.", true));
        for (int i = 0; i < properties.maxAttempts(); i++) {
            due(id);
            worker.poll();
        }
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals("DELETE_FAILED", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "PROGRESS", "RETRY", "CLAIMED", "RETRY", "CLAIMED", "FAILED"), eventTypes(id));
        UUID environmentId = operations.find(id).orElseThrow().environmentId();
        var retry = service.delete(environmentId, operator());
        assertNotEquals(id, retry.operationId());
        doReturn(true).when(provisioner).delete(any(), any(), any());
        worker.poll();
        assertEquals("DELETED", environmentStatus(id));
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals(3, operations.count(environmentId));
    }

    @Test
    void deletionOwnershipFailureIsTerminal() {
        UUID id = delete();
        when(provisioner.delete(any(), any(), any())).thenThrow(
                new ProvisioningException("KUBERNETES_OWNERSHIP_CONFLICT", "Namespace is not owned.", false));
        worker.poll();
        assertEquals("DELETE_FAILED", environmentStatus(id));
        assertEquals("KUBERNETES_OWNERSHIP_CONFLICT", operations.find(id).orElseThrow().failureCode());
        assertTrue(repository.claim(properties).isEmpty());
    }

    @Test
    void unexpectedDeletionFailureUsesSanitizedDeletionMessage() {
        UUID id = delete();
        when(provisioner.delete(any(), any(), any())).thenThrow(new IllegalStateException("secret-response"));
        var failure = assertThrows(IllegalStateException.class, worker::poll);
        assertTrue(failure.getMessage().contains("environment operation"));
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
        var operation = operations.find(id).orElseThrow();
        assertEquals("INTERNAL_ERROR", operation.failureCode());
        assertEquals("An internal deletion error occurred.", operation.failureMessage());
        assertEquals("DELETE_FAILED", environmentStatus(id));
    }

    @Test
    void deletionReclaimsEvenLastCrashedAttemptAndFencesStaleOwner() {
        UUID id = delete();
        var stale = repository.claim(properties).orElseThrow();
        assertTrue(stale.deleting());
        expire(id);
        assertFalse(repository.owns(stale));
        assertFalse(repository.succeed(stale));
        assertFalse(repository.fail(stale, "FAILED", "Failed."));
        assertFalse(repository.waiting(stale, Duration.ZERO));
        for (int i = 1; i < properties.maxAttempts(); i++) {
            var recovered = repository.claim(properties).orElseThrow();
            assertNotEquals(stale.leaseToken(), recovered.leaseToken());
            assertFalse(repository.succeed(stale));
            expire(id);
        }
        // A DELETE may have been accepted before a crash, even on the last attempt.
        // Re-read Kubernetes instead of failing before discovering that it is now absent.
        doReturn(true).when(provisioner).delete(any(), any(), any());
        worker = new ProvisioningWorker(new WorkerRepository(jdbc), environments, provisioner, properties);
        worker.poll();
        assertEquals("DELETED", environmentStatus(id));
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
    }

    @Test
    void deletionExpiredDuringHttpCannotPublishAndRecoveryObservesAbsence() {
        UUID id = delete();
        when(provisioner.delete(any(), any(), any())).thenAnswer(invocation -> {
            assertTrue(((java.util.function.BooleanSupplier) invocation.getArgument(2)).getAsBoolean());
            expire(id);
            assertFalse(((java.util.function.BooleanSupplier) invocation.getArgument(2)).getAsBoolean());
            return true;
        });
        worker.poll();
        assertEquals("RUNNING", operations.find(id).orElseThrow().status());
        assertEquals("DELETING", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "PROGRESS"), eventTypes(id));
        doReturn(true).when(provisioner).delete(any(), any(), any());
        worker.poll();
        assertEquals("DELETED", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "PROGRESS", "CLAIMED", "SUCCEEDED"), eventTypes(id));
        assertEquals(List.of("CLAIMED", "DELETION_STARTED", "LEASE_RECOVERED", "ENVIRONMENT_DELETED"), eventCodes(id));
    }

    @Test
    void terminatingNamespaceEventuallyFailsAtDurableDeadline() {
        UUID id = delete();
        worker.poll();
        jdbc.update("UPDATE operations SET started_at = clock_timestamp() - interval '6 minutes' WHERE id = ?", id);
        due(id);
        worker.poll();
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals("DELETION_TIMEOUT", operations.find(id).orElseThrow().failureCode());
        assertEquals("The Kubernetes namespace did not disappear before the deletion deadline.",
                operations.find(id).orElseThrow().failureMessage());
        assertEquals("DELETE_FAILED", environmentStatus(id));
        verify(provisioner, times(1)).delete(any(), any(), any());
    }

    @Test
    void simultaneousDeletionClaimersHaveOnlyOneLease() throws Exception {
        UUID id = delete();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var first = pool.submit(() -> { start.await(); return repository.claim(properties); });
            var second = pool.submit(() -> { start.await(); return repository.claim(properties); });
            var one = first.get(10, TimeUnit.SECONDS);
            var two = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(one.isPresent(), two.isPresent());
            assertEquals(id, one.orElseGet(two::orElseThrow).id());
            assertEquals(List.of("CLAIMED"), eventTypes(id));
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID delete() {
        UUID createId = create();
        when(provisioner.isReady(any())).thenReturn(true);
        worker.poll();
        UUID environmentId = operations.find(createId).orElseThrow().environmentId();
        clearInvocations(provisioner);
        return service.delete(environmentId, operator()).operationId();
    }

    private UUID create() {
        return service.create(UUID.randomUUID().toString(),
                new CreateEnvironmentRequest("Worker test", "sample-service", 1), operator()).operationId();
    }

    private org.springframework.security.core.Authentication operator() {
        return new org.springframework.security.authentication.TestingAuthenticationToken(
                "worker-test", null, "ROLE_OPERATOR");
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE operations SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
    }

    private void due(UUID id) {
        jdbc.update("UPDATE operations SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
    }

    private String environmentStatus(UUID id) {
        return environments.find(operations.find(id).orElseThrow().environmentId()).orElseThrow().status();
    }

    private List<String> eventTypes(UUID id) {
        return operations.events(id).stream().map(OperationRepository.OperationEvent::type).toList();
    }

    private List<String> eventCodes(UUID id) {
        return operations.events(id).stream().map(OperationRepository.OperationEvent::code).toList();
    }
}
