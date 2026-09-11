package io.github.psd2live.workflow

import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class WorkflowProgressTest {
    @Test fun onlyMeasuredTransfersHavePercentages() {
        val initial = WorkflowProgress("decompose", startedNanos = 0)
        val upload = initial.advance(WorkflowProgressUpdate(WorkflowPhase.UPLOADING, 25, 100), 1)
        assertEquals(0.25f, upload.fraction)
        val inference = upload.advance(WorkflowProgressUpdate(WorkflowPhase.GENERATING), 2)
        assertNull(inference.fraction)
        assertEquals(0L, inference.bytes)
        assertEquals(JsonNull, inference.toJson()["fraction"])
        assertNull(inference.advance(WorkflowProgressUpdate(WorkflowPhase.DOWNLOADING, 50), 3).fraction)
        assertEquals(1f, inference.advance(WorkflowProgressUpdate(WorkflowPhase.DOWNLOADING, 110, 100), 3).fraction)
    }

    @Test fun clockAndOutcomeSurviveCompletionFailureAndCancellation() {
        for (outcome in listOf(WorkflowOutcome.COMPLETED, WorkflowOutcome.FAILED, WorkflowOutcome.STOPPED)) {
            val running = WorkflowProgress("resume", startedNanos = 0)
                .advance(WorkflowProgressUpdate(WorkflowPhase.GENERATING), 2_000_000_000)
            assertEquals(12L, running.elapsedSeconds(12_000_000_000))
            assertEquals(10L, running.quietSeconds(12_000_000_000))
            val finished = running.finish(outcome, 15_000_000_000)
            assertEquals(15L, finished.elapsedSeconds(99_000_000_000))
            assertEquals(outcome, finished.outcome)
            assertSame(finished, finished.advance(WorkflowProgressUpdate(WorkflowPhase.DOWNLOADING, 10, 10)))
        }
    }

    @Test fun throttlesTransferUpdatesButKeepsFinalByteAndStageChanges() {
        val upload = WorkflowProgress("decompose", startedNanos = 0)
            .advance(WorkflowProgressUpdate(WorkflowPhase.UPLOADING, 1, 100), 0)
        assertSame(upload, upload.advance(WorkflowProgressUpdate(WorkflowPhase.UPLOADING, 5, 100), 1))
        assertEquals(100L, upload.advance(WorkflowProgressUpdate(WorkflowPhase.UPLOADING, 100, 100), 2).bytes)
        assertEquals(WorkflowPhase.SUBMITTING, upload.advance(WorkflowProgressUpdate(WorkflowPhase.SUBMITTING), 3).phase)
    }
}
