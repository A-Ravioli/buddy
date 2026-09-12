package buddy.ledger

import java.security.MessageDigest

/**
 * Deterministic event ids.
 *
 * Perception sources re-deliver the same thing all the time: a notification is posted,
 * updated, and re-posted; a content observer fires twice; a sync backfills a week of
 * messages that are already in the ledger. Rather than de-duplicating downstream, an
 * event's id is a hash of the facts that make it the same event, and the ledger's
 * append is idempotent on id. The same observation twice is one row.
 *
 * The id is prefixed with the timestamp so that ordering by id is ordering by time,
 * which keeps the primary key index useful for "recent" scans.
 */
object EventId {
    private const val HASH_CHARS = 24

    fun of(ts: Long, sourceApp: String, channel: String, vararg identity: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceApp.toByteArray())
        digest.update(0)
        digest.update(channel.toByteArray())
        for (part in identity) {
            // A type marker keeps null distinct from any string, including "null".
            if (part == null) {
                digest.update(1)
            } else {
                digest.update(2)
                digest.update(part.toByteArray())
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
        return "%013d-%s".format(ts, hex)
    }
}
