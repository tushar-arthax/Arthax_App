package com.example.arthax.call

/**
 * Decides when the call-log reconcile may run, and makes sure a trigger is never dropped.
 *
 * Only one reconcile should be in flight at a time — two running over the same watermark
 * would fight each other. But "there is already one running, do it afterwards" has to be
 * recorded and acted on, and getting that hand-off wrong is how a call goes missing.
 *
 * The bug this exists to prevent: the running reconcile used to ask "is a reconcile
 * running?" in order to schedule its own follow-up, which was of course always yes, so the
 * follow-up was recorded as wanted and then never started. The visible symptom was two
 * calls placed back to back and only the first of them ever reaching the CRM.
 *
 * Both fields move together under one lock, so between a run finishing and the flag being
 * cleared there is no instant where a trigger can be accepted by nobody.
 */
class ReconcileGate {

    private val lock = Any()
    private var running = false
    private var rerunRequested = false

    /**
     * True when the caller should start a run now. False means one is already in flight and
     * this trigger has been remembered — [finishAndCheckRerun] will hand it back.
     */
    fun tryStart(): Boolean = synchronized(lock) {
        if (running) {
            rerunRequested = true
            false
        } else {
            running = true
            true
        }
    }

    /**
     * Called by the runner when a pass finishes. True means go round again immediately,
     * because at least one trigger arrived while that pass was working.
     */
    fun finishAndCheckRerun(): Boolean = synchronized(lock) {
        if (rerunRequested) {
            rerunRequested = false
            true
        } else {
            running = false
            false
        }
    }

    /** Releases the gate unconditionally, for a run that was abandoned rather than finished. */
    fun abandon() = synchronized(lock) {
        running = false
        rerunRequested = false
    }
}
