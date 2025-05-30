package agdesigns.elevatefitness.presentation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException
import javax.inject.Inject

data class HomeState(
    val exerciseName: String = "",
    val setsDone: Int = 0,
    val reps: List<Int> = emptyList(),
    val weight: Float = 0f,
    val rest: List<Int> = emptyList(),
    val restTimestamp: ZonedDateTime? = null,
    val note: String = "",
    val currentTime: ZonedDateTime = ZonedDateTime.now(),
    val currentReps: Int = 0,
    val exerciseIncrement: Float = 0.5f,
    val nextExerciseName: String = "",
    val imageBitmap: Bitmap? = null
)

sealed class HomeEvent {
    data object ResetRest: HomeEvent()
    data class ChangeReps(val change: Int): HomeEvent()
    data class ChangeWeight(val change: Int): HomeEvent()
    data object CompleteSet: HomeEvent()
    data object ForceSync: HomeEvent()
}


@HiltViewModel
class HomeViewModel @Inject constructor(private val repository: WearRepository): ViewModel() {
    private val _state = mutableStateOf(HomeState())
    val state: State<HomeState> = _state
    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeWearWorkout().collect { workout ->
                val setsDone = (workout.setsDone ?: state.value.setsDone)
                val reps = workout.reps ?: state.value.reps
                val currentReps = reps.getOrNull(setsDone) ?: state.value.currentReps

                Log.d("HomeViewModel", "got wear workout: $workout")
                _state.value = state.value.copy(
                    exerciseName = workout.exerciseName ?: state.value.exerciseName,
                    setsDone = workout.setsDone ?: state.value.setsDone,
                    reps = reps,
                    weight = workout.weight ?: state.value.weight,
                    rest = workout.rest ?: state.value.rest,
                    note = workout.note ?: state.value.note,
                    restTimestamp = workout.restTimestamp?.let {
                        ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(it),
                            ZoneId.systemDefault()
                        ) } ?: state.value.restTimestamp,
                    currentReps = currentReps,
                    exerciseIncrement = workout.exerciseIncrement ?: state.value.exerciseIncrement, // FIXME: sometimes does not arrive
                    nextExerciseName = workout.nextExerciseName ?: state.value.nextExerciseName, // FIXME: if no more exercises, this is null
                )
            }
        }
        viewModelScope.launch {
            repository.observeWearImage().collect {
                _state.value = state.value.copy(imageBitmap = it)
            }
        }
        viewModelScope.launch {
            repository.isPhoneAlive().collect {
                // reset state
                if (!it) {
                    _state.value = HomeState()
                }
            }
        }
        viewModelScope.launch {
            repository.observeWorkoutInterrupted().collect {
                if (it) {
                    _state.value = HomeState()
                }
            }
        }
        startTimer()
        onEvent(HomeEvent.ForceSync) // request sync once
    }

    fun onEvent(event: HomeEvent){
        when (event) {
            is HomeEvent.ResetRest -> {
                viewModelScope.launch {
                    _state.value = state.value.copy(restTimestamp = ZonedDateTime.now())
                }
            }
            is HomeEvent.ChangeReps -> {
                _state.value = state.value.copy(currentReps = state.value.currentReps + event.change)
            }
            is HomeEvent.ChangeWeight -> {
                val deincrement = state.value.exerciseIncrement * event.change
                _state.value = state.value.copy(weight = state.value.weight + deincrement)
            }
            is HomeEvent.CompleteSet -> {
                viewModelScope.launch {
                    repository.completeSet(
                        state.value.exerciseName,
                        state.value.currentReps,
                        state.value.weight
                    )
                }

            }
            is HomeEvent.ForceSync -> {
                viewModelScope.launch {
                    repository.forceSync()
                }
            }

        }

    }

    // current time
    private fun startTimer(){
        timerJob?.cancel(CancellationException("Duplicate call"))
        timerJob = flow {
            var counter = 0
            while (true) {
                emit(counter++)
                delay(100)
            }
        }.onEach {
            _state.value = state.value.copy(currentTime = ZonedDateTime.now())
        }.launchIn(viewModelScope)
    }
}