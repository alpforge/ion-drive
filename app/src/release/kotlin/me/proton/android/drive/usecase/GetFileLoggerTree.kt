package me.proton.android.drive.usecase

import timber.log.Timber
import javax.inject.Inject

// Fork: release counterpart of the debug-only GetFileLoggerTree, so the release variant compiles.
// Never called in release (LoggerInitializer only uses it for debug/alpha builds).
class GetFileLoggerTree @Inject constructor() {
    operator fun invoke(): Result<Timber.Tree> =
        Result.failure(UnsupportedOperationException("File logging is debug only"))
}
