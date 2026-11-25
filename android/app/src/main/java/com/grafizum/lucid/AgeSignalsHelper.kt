package com.grafizum.lucid

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature

/**
 * Google Play's age signals, called for compliance only.
 *
 * Lucid is an all-ages app: nothing here gates a feature, changes a screen or
 * makes the app behave differently for one age band. The signals are logged and
 * then dropped — no copy is written to preferences, a database or anywhere else,
 * which is also what the Play Console data-safety answers say.
 *
 * Every call is wrapped, because a phone without Play services, an old Play
 * Store or a region where the API does not exist must never lose the lens.
 */
object AgeSignalsHelper {

    private const val TAG = "LucidAgeSignals"

    /**
     * Ask Play whether this install may receive age signals, and read them if
     * the answer is SHARED. Safe to call on every open; the SDK caches the
     * user's decision. Nothing is awaited and nothing is drawn.
     */
    fun requestAgeSignals(activity: Activity) {
        val manager = manager(activity.applicationContext) ?: return

        val request = runCatching {
            manager.requestAgeSignalsAccess(
                AgeSignalsAccessRequest.builder().setActivity(activity).build(),
            )
        }.onFailure {
            Log.d(TAG, "Age signals access could not be requested: ${it.javaClass.simpleName}")
        }.getOrNull() ?: return

        request
            .addOnSuccessListener { access ->
                when (access.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> {
                        Log.d(TAG, "Age signals: SHARED — reading the range")
                        read(manager)
                    }
                    AgeSignalsStatus.NOT_SHARED ->
                        Log.d(TAG, "Age signals: NOT_SHARED — nothing to do")
                    AgeSignalsStatus.VERIFICATION_REQUIRED ->
                        Log.d(TAG, "Age signals: VERIFICATION_REQUIRED — Play handles it at the store")
                    else ->
                        Log.d(TAG, "Age signals: status ${access.ageSignalsStatus()}")
                }
            }
            .addOnFailureListener {
                Log.d(TAG, "Age signals access failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
            }
    }

    /**
     * Whether mandatory age sharing applies in this region. Exposed for later
     * use; the app does not call it, because Lucid has nothing to gate.
     */
    fun isMandatorySharing(context: Context): Task<Boolean>? {
        val manager = manager(context) ?: return null
        return runCatching {
            manager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build())
                .continueWith { task ->
                    val mandatory = task.isSuccessful &&
                        (task.result?.features()?.contains(ApplicableAgeFeature.MANDATORY_SHARING) == true)
                    Log.d(TAG, "Age signals: mandatory sharing = $mandatory")
                    mandatory
                }
        }.onFailure {
            Log.d(TAG, "Age features could not be read: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** The range itself. Logged, then forgotten — never returned, never kept. */
    private fun read(manager: AgeSignalsManager) {
        val check = runCatching {
            manager.checkAgeSignals(AgeSignalsRequest.builder().build())
        }.onFailure {
            Log.d(TAG, "Age signals check could not start: ${it.javaClass.simpleName}")
        }.getOrNull() ?: return

        check
            .addOnSuccessListener { result ->
                Log.d(
                    TAG,
                    "Age signals: ageLower=${result.ageLower()} ageUpper=${result.ageUpper()} " +
                        "ageRangeSource=${source(result.ageRangeSource())}",
                )
            }
            .addOnFailureListener {
                Log.d(TAG, "Age signals check failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
            }
    }

    /** The manager, or null where Play cannot give one. */
    private fun manager(context: Context): AgeSignalsManager? =
        runCatching { AgeSignalsManagerFactory.create(context) }
            .onFailure { Log.d(TAG, "Age signals unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()

    /** TIER_A … TIER_D as their names, so the log line reads like the docs. */
    private fun source(value: Int?): String = when (value) {
        AgeRangeSource.TIER_A -> "TIER_A"
        AgeRangeSource.TIER_B -> "TIER_B"
        AgeRangeSource.TIER_C -> "TIER_C"
        AgeRangeSource.TIER_D -> "TIER_D"
        else -> value?.toString() ?: "none"
    }
}
