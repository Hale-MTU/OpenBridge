package tech.mmarca.openvitals.bridge.vertmatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsBuild
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchSummary

/** What the screen can ask its host to do. Workouts are named by their session id. */
internal class VertMatchActions(
    val onBack: () -> Unit,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onReloadSummary: () -> Unit,
    val onSelectBuild: (OpenVitalsBuild) -> Unit,
    val onAskWorkouts: () -> Unit,
    val onAskRoutes: () -> Unit,
    val onSend: (String) -> Unit,
    val onSendAnyway: (String) -> Unit,
    val onCancelSend: () -> Unit,
    val onRemove: (String) -> Unit,
)

@Composable
internal fun VertMatchScreen(
    connection: VertMatchUiState,
    workouts: VertMatchWorkoutsState,
    canReadSummary: Boolean,
    actions: VertMatchActions,
) {
    val connected = connection.status == VertMatchStatus.Connected
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = actions.onBack) { Text("Back") }
                Text("VertMatch", style = MaterialTheme.typography.headlineSmall)
                if (connection.busy || workouts.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        item { AboutCard() }
        item { ConnectionCard(connection, actions) }
        if (connected && canReadSummary) {
            item { SummaryCard(connection.summary, connection.busy, actions.onReloadSummary) }
        }
        if (connection.status != VertMatchStatus.NotConfigured) {
            item { WorkoutsHeader(workouts, actions) }
            items(workouts.workouts, key = { it.sessionId }) { workout ->
                WorkoutCard(workout, connection, workouts.confirmNoDistance == workout.sessionId, actions)
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Section("What this does") {
        Text(
            "Send a workout from OpenVitals to your VertMatch training, and see your VertMatch training summary " +
                "here. Deleting a sent workout in OpenVitals deletes it on VertMatch too, the next time you open " +
                "this screen.",
        )
        Text(
            "Sent to VertMatch: the workout you choose, as a FIT file with its time, heart rate, speed, cadence " +
                "and, if you allow workout routes, its route. Received from VertMatch: your goal race and your " +
                "training load for the last seven days. Nothing else is sent, and nothing is sent until you tap " +
                "Send. Disconnecting deletes the sign-in from this phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionCard(state: VertMatchUiState, actions: VertMatchActions) {
    Section("Connection") {
        Text(statusText(state.status))
        state.message?.let { Text(it) }
        when (state.status) {
            VertMatchStatus.Connected ->
                OutlinedButton(onClick = actions.onDisconnect, enabled = !state.busy) { Text("Disconnect VertMatch") }
            VertMatchStatus.Disconnected, VertMatchStatus.AppStopped ->
                Button(onClick = actions.onConnect, enabled = !state.busy) { Text("Connect VertMatch") }
            VertMatchStatus.NotConfigured -> Unit
        }
    }
}

@Composable
private fun SummaryCard(summary: VertMatchSummary?, busy: Boolean, onReload: () -> Unit) {
    Section("Training summary") {
        if (summary == null) {
            Text("No summary loaded yet.")
        } else {
            val race = summary.targetRace
            Text(
                if (race?.name != null) "Goal race: ${race.name}, ${race.date.orEmpty()}" else "No goal race set on VertMatch.",
            )
            val week = summary.week
            Text(
                if (week == null) {
                    "Training load is part of VertMatch Premium."
                } else {
                    "Last 7 days: load ${week.load.formatted(0)}, fitness ${week.ctl.formatted(1)}, " +
                        "fatigue ${week.atl.formatted(1)}"
                },
            )
        }
        OutlinedButton(onClick = onReload, enabled = !busy) { Text("Reload summary") }
    }
}

@Composable
private fun WorkoutsHeader(state: VertMatchWorkoutsState, actions: VertMatchActions) {
    Section("Workouts") {
        if (state.build == null) {
            Text("No OpenVitals that shares data is installed.")
            return@Section
        }
        if (state.builds.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.builds.forEach { build ->
                    FilterChip(
                        selected = build == state.build,
                        onClick = { actions.onSelectBuild(build) },
                        label = { Text(build.name.lowercase()) },
                    )
                }
            }
        }
        if (!state.workoutsGranted) {
            Text("OpenBridge may not read your workouts from OpenVitals yet.")
            Button(onClick = actions.onAskWorkouts) { Text("Allow workouts") }
            return@Section
        }
        if (!state.routesGranted) {
            Text("Workouts are sent without their route. Allow workout routes to send the route too.")
            OutlinedButton(onClick = actions.onAskRoutes) { Text("Allow workout routes") }
        }
        problemText(state.problem)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.workouts.isEmpty() && !state.loading && state.problem == null) {
            Text("No workouts in the last 30 days.")
        }
    }
}

@Composable
private fun WorkoutCard(workout: WorkoutRow, state: VertMatchUiState, confirmNoDistance: Boolean, actions: VertMatchActions) {
    val externalId = VertMatchConnection.externalIdFor(workout.sessionId)
    val connected = state.status == VertMatchStatus.Connected
    val sent = externalId != null && externalId in state.sent
    val deletedThere = externalId != null && externalId in state.known
    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(workout.title ?: workout.type.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatter.format(workout.start.atZone(ZoneId.systemDefault()))} · " +
                    "${java.time.Duration.between(workout.start, workout.end).toMinutes()} min",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                externalId == null -> Text("This workout cannot be sent.")
                deletedThere -> Text(VertMatchConnection.Messages.KNOWN)
                !connected -> Text("Connect VertMatch to send this workout.")
                confirmNoDistance -> {
                    Text(
                        "This workout has no distance, so VertMatch cannot match it with a copy it may already have " +
                            "from Health Connect. If the VertMatch app also reads Health Connect, the workout can " +
                            "show up there twice.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.onSendAnyway(workout.sessionId) }, enabled = !state.busy) {
                            Text("Send anyway")
                        }
                        TextButton(onClick = actions.onCancelSend) { Text("Cancel") }
                    }
                }
                sent -> {
                    Text("This workout is on VertMatch. Deleting it in OpenVitals deletes it there too.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { actions.onSend(workout.sessionId) }, enabled = !state.busy) {
                            Text("Send again")
                        }
                        TextButton(onClick = { actions.onRemove(workout.sessionId) }, enabled = !state.busy) {
                            Text("Remove from VertMatch")
                        }
                    }
                }
                else -> Button(onClick = { actions.onSend(workout.sessionId) }, enabled = !state.busy) {
                    Text("Send to VertMatch")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

internal fun statusText(status: VertMatchStatus): String = when (status) {
    VertMatchStatus.NotConfigured -> "This build of OpenBridge is not set up for VertMatch."
    VertMatchStatus.Disconnected -> "Not connected."
    VertMatchStatus.Connected -> "Connected. Workouts are only sent when you tap Send on one."
    VertMatchStatus.AppStopped ->
        "VertMatch has switched off access for OpenBridge. Nothing is sent until it is switched on again."
}

/** Why the workouts list may be missing rows, in words. Null when it was read whole. */
private fun problemText(problem: String?): String? = when (problem) {
    null -> null
    "sharing_off" -> VertMatchConnection.Messages.SHARING_OFF
    "background_read_missing" -> VertMatchConnection.Messages.BACKGROUND_READ_MISSING
    "rate_limited" -> VertMatchConnection.Messages.OPENVITALS_BUSY
    "denied" -> VertMatchConnection.Messages.NOT_ALLOWED
    "unavailable" -> "This OpenVitals does not share data."
    else -> "OpenVitals could not read every workout. Some may be missing."
}

/** A dash, never a zero, for a value VertMatch left out: its document says null is "not available". */
private fun Double?.formatted(decimals: Int): String = this?.let { "%.${decimals}f".format(it) } ?: "-"
