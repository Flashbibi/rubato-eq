package ch.sommermeyer.rubato.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import ch.sommermeyer.rubato.AppGraph
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.audio.EqEffectController
import ch.sommermeyer.rubato.audio.VisualizerCapture
import ch.sommermeyer.rubato.core.analysis.BandLayout
import ch.sommermeyer.rubato.core.analysis.FftBandAggregator
import ch.sommermeyer.rubato.core.analysis.SilenceGate
import ch.sommermeyer.rubato.core.analysis.Spectra
import ch.sommermeyer.rubato.core.correction.CorrectionMath
import ch.sommermeyer.rubato.core.correction.CorrectionParams
import ch.sommermeyer.rubato.core.correction.CorrectionTiming
import ch.sommermeyer.rubato.core.correction.LibraryTarget
import ch.sommermeyer.rubato.core.correction.SongAnalysis
import ch.sommermeyer.rubato.core.correction.StoredAnalysis
import ch.sommermeyer.rubato.core.correction.ToneOffset
import ch.sommermeyer.rubato.core.model.SongKey
import ch.sommermeyer.rubato.data.EqMode
import ch.sommermeyer.rubato.data.RubatoSettings
import ch.sommermeyer.rubato.data.SongEntity
import ch.sommermeyer.rubato.data.TrainingExampleEntity
import ch.sommermeyer.rubato.nowplaying.NowPlaying
import ch.sommermeyer.rubato.nowplaying.NowPlayingTracker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects now-playing detection, analysis and EQ.
 *
 * All mutable state lives on one HandlerThread: Visualizer, MediaController and AudioEffect
 * callbacks are delivered there, and the coroutines of the engine run on it as well, so no
 * locking is needed. Public methods post to that thread.
 */
class RubatoEngine(private val context: Context, private val graph: AppGraph) {
    private val bands = BandLayout.DEFAULT
    private val timing = CorrectionTiming()
    private val log = graph.log

    private val thread = HandlerThread("rubato-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher("rubato-engine")

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // Engine thread only.
    private var scope: CoroutineScope? = null
    private var stopping = false
    private var tracker: NowPlayingTracker? = null
    private var visualizer: VisualizerCapture? = null
    private var effects: EqEffectController? = null
    private val aggregator = FftBandAggregator(bands)
    private var library = LibraryTarget(bands.size)
    private var settings = RubatoSettings()
    private var settingsLoaded = false
    private var nowPlaying: NowPlaying? = null
    private var song: ActiveSong? = null
    private var pendingSongKey: SongKey? = null
    private var pendingSongJob: Job? = null
    private var paused = false
    private var lastActiveAtMs = 0L
    private var lastAccessCheckMs = 0L
    private val livePower = DoubleArray(bands.size)
    private var liveValid = false
    private var lastPublishMs = 0L

    fun start() {
        handler.post { startInternal() }
    }

    /** Fades the EQ out, saves the current song and releases everything. */
    suspend fun stop() = withContext(dispatcher) { stopInternal() }

    fun togglePause() {
        handler.post { setPausedInternal(!paused) }
    }

    fun setManualOffset(offset: ToneOffset) {
        handler.post {
            val s = song?.takeIf { it.loaded } ?: return@post
            s.manual = ToneOffset(
                bassDb = offset.bassDb.coerceIn(-MANUAL_RANGE_DB, MANUAL_RANGE_DB),
                midDb = offset.midDb.coerceIn(-MANUAL_RANGE_DB, MANUAL_RANGE_DB),
                trebleDb = offset.trebleDb.coerceIn(-MANUAL_RANGE_DB, MANUAL_RANGE_DB),
            )
            applyGains()
            publish(force = true)
        }
    }

    /** Stores the manual offset of the current song and logs it as a training example. */
    fun commitManualOffset() {
        handler.post {
            val s = song?.takeIf { it.loaded } ?: return@post
            s.trainingPending = true
            log.info(TAG, R.string.log_manual_saved, s.info.displayTitle(), s.manual.bassDb, s.manual.midDb, s.manual.trebleDb)
            persist(s)
        }
    }

    /** Deletes all songs and training examples and restarts the analysis of the current song. */
    fun resetAnalysisData() {
        handler.post {
            song = null
            pendingSongJob?.cancel()
            library.clear()
            val db = graph.database
            graph.dbWriter.submit {
                db.songs().deleteAll()
                db.trainingExamples().deleteAll()
            }
            log.info(TAG, R.string.log_data_reset)
            if (scope != null && !stopping) {
                applyGains()
                commitSong(nowPlaying, lookup = false)
            }
            publish(force = true)
        }
    }

    private fun startInternal() {
        if (scope != null) return
        val engineScope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
                log.error(TAG, R.string.log_engine_error, error = e)
            },
        )
        val nowPlayingTracker = NowPlayingTracker(context, handler)
        val capture = VisualizerCapture(log, ::onFrame)
        val eq = EqEffectController(engineScope, log, bands)
        scope = engineScope
        tracker = nowPlayingTracker
        visualizer = capture
        effects = eq
        stopping = false
        paused = false
        settingsLoaded = false
        _state.value = EngineState(running = true)
        log.info(TAG, R.string.log_engine_started)

        engineScope.launch {
            loadLibrary()
            launch { graph.settings.settings.collect { onSettings(it) } }
            launch { graph.audioSessions.sessions.collect { eq.onSessionsChanged(it) } }
            launch { capture.status.collect { status -> _state.update { it.copy(visualizer = status) } } }
            launch { eq.status.collect { status -> _state.update { it.copy(effect = status) } } }
            launch { nowPlayingTracker.nowPlaying.collect { onNowPlaying(it) } }
            launch { graph.listenerConnections.collect { ensureTracking() } }
            launch {
                while (isActive) {
                    tick()
                    delay(TICK_MS)
                }
            }
        }
    }

    private suspend fun stopInternal() {
        val engineScope = scope ?: return
        if (stopping) return
        // From here on tick(), frames and media callbacks must not restart anything.
        stopping = true
        song?.let(::persist)
        song = null
        pendingSongJob?.cancel()
        pendingSongKey = null
        tracker?.stop()
        nowPlaying = null
        visualizer?.stop()
        effects?.shutdown(fade = true)
        engineScope.cancel()
        scope = null
        tracker = null
        visualizer = null
        effects = null
        nowPlaying = null
        liveValid = false
        paused = false
        _state.value = EngineState(running = false)
        log.info(TAG, R.string.log_engine_stopped)
    }

    private suspend fun loadLibrary() {
        val spectra = try {
            graph.database.songs().librarySongs()
                .map { it.spectrumDb.toDoubleArray() }
                .filter { it.size == bands.size }
        } catch (e: Exception) {
            log.error(TAG, R.string.log_db_read_failed, error = e)
            emptyList()
        }
        library = LibraryTarget.of(bands.size, spectra)
        log.info(TAG, R.string.log_library_loaded, library.songCount)
    }

    private fun onSettings(new: RubatoSettings) {
        val old = settings
        settings = new
        val eq = effects ?: return
        if (!settingsLoaded || old.eqMode != new.eqMode) {
            if (settingsLoaded) log.info(TAG, R.string.log_mode_changed, context.getString(modeLabel(new.eqMode)))
            eq.setMode(new.eqMode)
        }
        if (settingsLoaded && old.compensation != new.compensation) {
            log.info(TAG, if (new.compensation) R.string.log_compensation_on else R.string.log_compensation_off)
        }
        val correctionChanged = !settingsLoaded ||
            old.strength != new.strength ||
            old.maxCorrectionDb != new.maxCorrectionDb ||
            old.tasteBassDb != new.tasteBassDb ||
            old.tasteTrebleDb != new.tasteTrebleDb
        settingsLoaded = true
        if (correctionChanged) {
            val s = song
            if (s != null && s.loaded && s.analysis.evaluate(correctionParams(), force = true) != null) applyGains()
        }
        publish(force = true)
    }

    private fun onNowPlaying(np: NowPlaying?) {
        if (stopping) return
        nowPlaying = np
        if (np?.isActive == true) lastActiveAtMs = now()
        val key = np?.songKey
        val current = song
        when {
            current != null && key == current.key -> {
                pendingSongJob?.cancel()
                pendingSongKey = null
                current.info = np
            }
            current == null && key == null -> {
                pendingSongJob?.cancel()
                pendingSongKey = null
            }
            key != pendingSongKey || pendingSongJob?.isActive != true -> {
                // Players often publish title, artist and duration in several steps: wait until the
                // metadata is stable before treating it as a new song.
                pendingSongKey = key
                pendingSongJob?.cancel()
                pendingSongJob = scope?.launch {
                    delay(SONG_CHANGE_DEBOUNCE_MS)
                    pendingSongKey = null
                    commitSong(nowPlaying, lookup = true)
                }
            }
        }
        if (!paused && np?.isActive == true) visualizer?.start(now())
        publish(force = true)
    }

    private fun commitSong(np: NowPlaying?, lookup: Boolean) {
        song?.let { previous ->
            persist(previous)
            log.info(TAG, R.string.log_song_finished, previous.info.displayTitle(), previous.analysis.sessionAnalyzedMs / 1000)
        }
        val key = np?.songKey
        if (np == null || key == null) {
            song = null
            applyGains()
            publish(force = true)
            return
        }
        val active = ActiveSong(key, np, SongAnalysis(bands.size, timing), startedAtMs = now())
        song = active
        log.info(TAG, R.string.log_song_started, np.displayTitle(), np.appLabel)
        if (!lookup) {
            active.loaded = true
            applyGains()
            publish(force = true)
            return
        }
        publish(force = true)
        scope?.launch {
            val stored = try {
                graph.database.songs().find(key.value)
            } catch (e: Exception) {
                log.error(TAG, R.string.log_db_read_failed, error = e)
                active.persistable = false // never overwrite a row we could not read
                null
            }
            if (song !== active) return@launch
            if (stored != null) {
                active.restoreFrom(stored, bands.size)
                if (active.known) log.info(TAG, R.string.log_song_known, stored.playCount)
            }
            active.loaded = true
            applyGains()
            publish(force = true)
        }
    }

    private fun onFrame(fft: ByteArray, samplingRateHz: Double, rmsDbfs: Double?) {
        if (stopping) return
        val eq = effects ?: return
        val capture = visualizer ?: return
        val power = aggregator.aggregate(fft, samplingRateHz)
        if (SilenceGate.isSilent(power, rmsDbfs)) return
        val applied = if (settings.compensation) eq.appliedGainsDb() else null
        val dry = if (applied == null) {
            power
        } else {
            DoubleArray(power.size) { power[it] / Spectra.dbToPowerRatio(applied[it]) }
        }
        updateLive(dry)
        val s = song
        val analysing = !paused && pendingSongJob?.isActive != true && nowPlaying?.isActive == true
        if (s != null && analysing) {
            s.analysis.addFrame(power, capture.frameDurationMs, now() - s.startedAtMs, applied)
        }
        publish()
    }

    private fun tick() {
        if (stopping) return
        val capture = visualizer ?: return
        val t = now()
        val playing = nowPlaying?.isActive == true
        if (playing) lastActiveAtMs = t
        if (!paused && playing) {
            capture.start(t)
        } else if (capture.isRunning && (paused || t - lastActiveAtMs > CAPTURE_IDLE_STOP_MS)) {
            capture.stop()
            liveValid = false
        }
        capture.checkHealth(t, expectFrames = !paused && playing)
        if (tracker?.isListening != true && t - lastAccessCheckMs > ACCESS_RETRY_MS) ensureTracking()

        val s = song
        if (s != null && s.loaded && !paused && playing) {
            val hadCorrection = s.analysis.currentCorrection != null
            if (s.analysis.evaluate(correctionParams()) != null) {
                if (!hadCorrection) log.info(TAG, R.string.log_first_correction, s.info.displayTitle())
                applyGains()
                persist(s)
            }
        }
        publish()
    }

    private fun setPausedInternal(value: Boolean) {
        if (scope == null || stopping || paused == value) return
        paused = value
        effects?.setBypassed(value)
        if (value) {
            visualizer?.stop()
            liveValid = false
        } else if (nowPlaying?.isActive == true) {
            visualizer?.start(now())
        }
        log.info(TAG, if (value) R.string.log_paused else R.string.log_resumed)
        publish(force = true)
    }

    private fun ensureTracking() {
        if (stopping) return
        val t = tracker ?: return
        lastAccessCheckMs = now()
        val ok = t.start()
        if (!ok && _state.value.notificationAccess) log.warn(TAG, R.string.log_no_notification_access)
        if (ok != _state.value.notificationAccess) _state.update { it.copy(notificationAccess = ok) }
    }

    private fun applyGains() {
        val eq = effects ?: return
        val s = song
        val auto = s?.analysis?.currentCorrection ?: DoubleArray(bands.size)
        val manual = s?.manual?.toBands(bands) ?: DoubleArray(bands.size)
        eq.setTarget(CorrectionMath.add(auto, manual))
    }

    private fun correctionParams(): CorrectionParams = CorrectionParams.from(
        library = library,
        strength = settings.strength.toDouble(),
        maxDb = settings.maxCorrectionDb.toDouble(),
        taste = settings.taste,
        bands = bands,
    )

    private fun persist(s: ActiveSong) {
        if (!s.loaded || !s.persistable) return
        val mean = s.analysis.meanPower()
        val spectrum = s.analysis.spectrumDb()
        if (mean == null && s.manual.isNeutral && !s.known) return

        if (spectrum != null && s.analysis.isLibraryReady && s.info.isLibraryCandidate()) {
            val previous = s.libraryContribution
            if (previous == null) {
                library.add(spectrum)
                log.info(TAG, R.string.log_library_added, s.info.displayTitle(), library.songCount)
            } else {
                library.replace(previous, spectrum)
            }
            s.libraryContribution = spectrum
            s.inLibrary = true
        }

        val auto = s.analysis.currentCorrection
        val applied = CorrectionMath.add(auto ?: DoubleArray(bands.size), s.manual.toBands(bands))
        val entity = SongEntity(
            songKey = s.key.value,
            title = s.info.title.orEmpty(),
            artist = s.info.artist.orEmpty(),
            album = s.info.album,
            durationMs = s.info.durationMs ?: 0,
            sourcePackage = s.info.packageName,
            firstPlayedAt = s.firstPlayedAt,
            lastPlayedAt = System.currentTimeMillis(),
            playCount = s.playCount,
            analyzedMs = s.analysis.totalAnalyzedMs,
            frameCount = s.analysis.frameCount,
            bandPowerMean = mean?.toList().orEmpty(),
            spectrumDb = spectrum?.toList().orEmpty(),
            autoCorrectionDb = auto?.toList().orEmpty(),
            appliedCorrectionDb = applied.toList(),
            manualBassDb = s.manual.bassDb,
            manualMidDb = s.manual.midDb,
            manualTrebleDb = s.manual.trebleDb,
            inLibrary = s.inLibrary,
            compensated = settings.compensation,
            eqMode = settings.eqMode.name,
        )
        val example = if (s.trainingPending && spectrum != null) {
            s.trainingPending = false
            trainingExample(s, spectrum, auto)
        } else {
            null
        }
        val db = graph.database
        graph.dbWriter.submit {
            db.songs().upsert(entity)
            if (example != null) db.trainingExamples().insert(example)
        }
        if (example != null) log.info(TAG, R.string.log_training_logged, s.info.displayTitle())
    }

    private fun trainingExample(s: ActiveSong, spectrum: DoubleArray, auto: DoubleArray?) = TrainingExampleEntity(
        createdAt = System.currentTimeMillis(),
        songKey = s.key.value,
        title = s.info.title.orEmpty(),
        artist = s.info.artist.orEmpty(),
        durationMs = s.info.durationMs ?: 0,
        sourcePackage = s.info.packageName,
        analyzedMs = s.analysis.totalAnalyzedMs,
        spectrumDb = spectrum.toList(),
        targetDb = correctionParams().target?.toList().orEmpty(),
        autoCorrectionDb = auto?.toList().orEmpty(),
        manualBassDb = s.manual.bassDb,
        manualMidDb = s.manual.midDb,
        manualTrebleDb = s.manual.trebleDb,
        strength = settings.strength.toDouble(),
        maxCorrectionDb = settings.maxCorrectionDb.toDouble(),
        tasteBassDb = settings.tasteBassDb.toDouble(),
        tasteTrebleDb = settings.tasteTrebleDb.toDouble(),
        librarySongs = library.songCount,
    )

    private fun updateLive(power: DoubleArray) {
        if (!liveValid) {
            power.copyInto(livePower)
            liveValid = true
        } else {
            for (b in livePower.indices) livePower[b] += LIVE_SMOOTHING * (power[b] - livePower[b])
        }
    }

    private fun publish(force: Boolean = false) {
        val t = now()
        if (!force && t - lastPublishMs < PUBLISH_INTERVAL_MS) return
        lastPublishMs = t
        val params = correctionParams()
        val applied = effects?.appliedGainsDb() ?: DoubleArray(bands.size)
        _state.update {
            it.copy(
                running = scope != null,
                paused = paused,
                nowPlaying = nowPlaying,
                song = song?.toState(),
                liveSpectrumDb = if (liveValid) Spectra.normalizedDb(livePower).toList() else null,
                targetDb = params.target?.toList(),
                appliedGainsDb = applied.toList(),
                librarySongs = library.songCount,
                effectiveStrength = params.effectiveStrength,
                compensation = settings.compensation,
            )
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private class ActiveSong(
        val key: SongKey,
        var info: NowPlaying,
        val analysis: SongAnalysis,
        val startedAtMs: Long,
    ) {
        var loaded = false
        var persistable = true
        var known = false
        var manual = ToneOffset.NEUTRAL
        var firstPlayedAt = System.currentTimeMillis()
        var playCount = 1
        var inLibrary = false
        var libraryContribution: DoubleArray? = null
        var trainingPending = false

        fun restoreFrom(stored: SongEntity, bandCount: Int) {
            manual = ToneOffset(stored.manualBassDb, stored.manualMidDb, stored.manualTrebleDb)
            firstPlayedAt = stored.firstPlayedAt
            playCount = stored.playCount + 1
            val auto = stored.autoCorrectionDb.takeIf { it.size == bandCount }?.toDoubleArray()
            val mean = stored.bandPowerMean.takeIf { it.size == bandCount }?.toDoubleArray()
            if (mean != null && stored.frameCount > 0) {
                analysis.restore(StoredAnalysis(mean, stored.frameCount, stored.analyzedMs, auto))
            }
            inLibrary = stored.inLibrary && stored.spectrumDb.size == bandCount
            libraryContribution = if (inLibrary) stored.spectrumDb.toDoubleArray() else null
            known = analysis.currentCorrection != null || !manual.isNeutral
        }

        fun toState() = SongState(
            key = key.value,
            title = info.title.orEmpty(),
            artist = info.artist.orEmpty(),
            known = known,
            loaded = loaded,
            sessionAnalyzedMs = analysis.sessionAnalyzedMs,
            totalAnalyzedMs = analysis.totalAnalyzedMs,
            hasCorrection = analysis.currentCorrection != null,
            spectrumDb = analysis.spectrumDb()?.toList(),
            autoCorrectionDb = analysis.currentCorrection?.toList(),
            manual = manual,
            inLibrary = inLibrary,
        )
    }

    companion object {
        const val MANUAL_RANGE_DB = 6.0
        private const val TAG = "engine"
        private const val TICK_MS = 500L
        private const val SONG_CHANGE_DEBOUNCE_MS = 700L
        private const val CAPTURE_IDLE_STOP_MS = 15_000L
        private const val ACCESS_RETRY_MS = 5_000L
        private const val PUBLISH_INTERVAL_MS = 100L
        private const val LIVE_SMOOTHING = 0.15
        private const val MIN_LIBRARY_DURATION_MS = 30_000L

        fun modeLabel(mode: EqMode): Int = when (mode) {
            EqMode.GLOBAL -> R.string.mode_global
            EqMode.SESSION -> R.string.mode_session
        }

        private fun NowPlaying.displayTitle(): String =
            listOfNotNull(title, artist).joinToString(" – ").ifEmpty { appLabel }

        private fun NowPlaying.isLibraryCandidate(): Boolean =
            durationMs == null || durationMs >= MIN_LIBRARY_DURATION_MS
    }
}
