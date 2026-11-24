package com.danieldk.splatevpn.data

/** Keep displayed measurements stable until the user refreshes; eligibility stays live. */
internal data class QuickServerListSnapshot(val candidates: List<QuickServerCandidate>) {
    fun append(eligible: List<QuickServerCandidate>, existingIds: Set<String> = (candidates + eligible).mapTo(hashSetOf()) { it.server.id }): QuickServerListSnapshot {
        val shown = candidates.mapTo(hashSetOf()) { it.server.id }
        return copy(candidates = (visible(existingIds) + eligible.filter { it.server.id !in shown }).take(10))
    }
    fun visible(existingIds: Set<String>) = candidates.filter { it.server.id in existingIds }

    fun available(eligible: List<QuickServerCandidate>): Set<String> {
        val current = eligible.associateBy { it.server.id }
        return candidates.filter { shown -> current[shown.server.id]?.let {
            QuickServerSelection.profile(shown.server) == QuickServerSelection.profile(it.server)
        } == true }.mapTo(hashSetOf()) { it.server.id }
    }

    companion object {
        fun publish(previous: QuickServerListSnapshot?, eligible: List<QuickServerCandidate>, existingIds: Set<String>,
            resultRevision: Int, requestedRevision: Int): QuickServerListSnapshot? {
            if (resultRevision != requestedRevision) return previous
            return if (previous == null && eligible.isNotEmpty()) capture(eligible) else previous?.append(eligible, existingIds)
        }
        fun capture(eligible: List<QuickServerCandidate>) = QuickServerListSnapshot(eligible.take(10))
    }
}
