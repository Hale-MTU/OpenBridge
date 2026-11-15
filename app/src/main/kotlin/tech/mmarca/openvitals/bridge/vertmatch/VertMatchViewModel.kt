package tech.mmarca.openvitals.bridge.vertmatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.bridge.BridgeApp
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsBuild
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsGroup
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsPaths
import tech.mmarca.openvitals.bridge.openvitals.ReadResult
import tech.mmarca.openvitals.bridge.openvitals.Table

/** A recorded workout as OpenVitals lists it. */
internal data class WorkoutRow(val sessionId: String, val start: Instant, val end: Instant, val type: String, val title: String?)

internal data class VertMatchWorkoutsState(
    val builds: List<OpenVitalsBuild> = emptyList(),
    val build: OpenVitalsBuild? = null,
    val workoutsGranted: Boolean = false,
    val routesGranted: Boolean = false,
    val workouts: List<WorkoutRow> = emptyList(),
    /** Why the list may be short or empty: `sharing_off`, `denied`, … Null when it was read whole. */
    val problem: String? = null,
    val loading: Boolean = false,
    /** A workout without a distance that waits for the runner to confirm sending it. */
    val confirmNoDistance: String? = null,
)

/** The workouts OpenVitals shares, for the VertMatch screen to offer. */
internal class VertMatchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BridgeApp
    private val reader = app.reader
    private val connection = app.vertMatch

    private val _state = MutableStateFlow(VertMatchWorkoutsState())
    val state: StateFlow<VertMatchWorkoutsState> = _state.asStateFlow()

    /** On resume: a grant, the sharing switch or the workouts themselves may have changed. */
    fun refresh() {
        val builds = reader.installedBuilds()
        val build = _state.value.build?.takeIf { it in builds } ?: builds.firstOrNull()
        _state.update {
            it.copy(
                builds = builds,
                build = build,
                workoutsGranted = build != null && reader.isGranted(build, OpenVitalsGroup.WORKOUTS),
                routesGranted = build != null && reader.isGranted(build, OpenVitalsGroup.ROUTES),
            )
        }
        if (build == null || !_state.value.workoutsGranted) {
            _state.update { it.copy(workouts = emptyList(), problem = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val today = LocalDate.now()
            val answer = reader.read(build, OpenVitalsPaths.sessions(OpenVitalsGroup.WORKOUTS, today.minusDays(ListDays - 1), today))
            val table = (answer as? ReadResult.Read)?.table
            _state.update {
                it.copy(
                    loading = false,
                    workouts = table?.recordedWorkouts().orEmpty(),
                    problem = when (answer) {
                        is ReadResult.Read -> answer.table.status?.takeIf { status -> status != "ok" }
                        is ReadResult.Denied -> "denied"
                        is ReadResult.Failed -> "failed"
                        ReadResult.Unavailable -> "unavailable"
                    },
                )
            }
            // A workout sent earlier and deleted in OpenVitals since is deleted on VertMatch too.
            connection.reconcileDeletes(build)
        }
    }

    fun selectBuild(build: OpenVitalsBuild) {
        _state.update { it.copy(build = build, workouts = emptyList(), confirmNoDistance = null) }
        refresh()
    }

    fun permissions(vararg groups: OpenVitalsGroup): Array<String> =
        _state.value.build?.let { reader.permissions(it, groups.toList()) } ?: emptyArray()

    /**
     * Sends a workout. VertMatch finds a copy it already has from Health Connect by start time and
     * distance, so a workout without a distance is sent only once the runner has read that it may
     * show up twice ([confirmed]).
     */
    fun send(sessionId: String, confirmed: Boolean = false) {
        val build = _state.value.build ?: return
        viewModelScope.launch {
            if (!confirmed && !hasDistance(build, sessionId)) {
                _state.update { it.copy(confirmNoDistance = sessionId) }
                return@launch
            }
            _state.update { it.copy(confirmNoDistance = null) }
            connection.send(build, sessionId)
            connection.reconcileDeletes(build)
        }
    }

    fun cancelSend() = _state.update { it.copy(confirmNoDistance = null) }

    fun remove(sessionId: String) {
        viewModelScope.launch { connection.remove(sessionId) }
    }

    /** True unless OpenVitals answered with the workout's summary and no distance in it. */
    private suspend fun hasDistance(build: OpenVitalsBuild, sessionId: String): Boolean {
        val answer = reader.read(build, OpenVitalsPaths.session(OpenVitalsGroup.WORKOUTS, sessionId))
        val table = (answer as? ReadResult.Read)?.table ?: return true
        if (table.rows.isEmpty()) return true
        return table.rows.any { row ->
            table.column(row, "metric") == "distance" && ((table.column(row, "value") as? Double) ?: 0.0) > 0.0
        }
    }

    private fun Table.recordedWorkouts(): List<WorkoutRow> = rows
        .filter { column(it, "kind") == "recorded" }
        .mapNotNull { row ->
            WorkoutRow(
                sessionId = column(row, "session_id") as? String ?: return@mapNotNull null,
                start = (column(row, "start_time") as? Long)?.let(Instant::ofEpochMilli) ?: return@mapNotNull null,
                end = (column(row, "end_time") as? Long)?.let(Instant::ofEpochMilli) ?: return@mapNotNull null,
                type = column(row, "type") as? String ?: "workout",
                title = column(row, "title") as? String,
            )
        }
        .sortedByDescending { it.start }

    private companion object {
        const val ListDays = 30L
    }
}
