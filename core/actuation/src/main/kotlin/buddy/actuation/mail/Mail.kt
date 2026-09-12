package buddy.actuation.mail

import buddy.actuation.Actions
import buddy.actuation.Connector
import buddy.actuation.Outcome
import buddy.policy.Proposal
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

/** IMAP and SMTP settings for one account. Gmail works with an app password or XOAUTH2. */
data class MailAccount(
    val address: String,
    val displayName: String?,
    val imapHost: String = "imap.gmail.com",
    val imapPort: Int = 993,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val username: String = address,
    val password: String,
    /** Folder to move archived mail to. Gmail archives by removing from INBOX; "[Gmail]/All Mail" keeps it. */
    val archiveFolder: String = "[Gmail]/All Mail",
)

/** The parts of a reply that can be built and tested without a server. */
data class ReplyDraft(
    val to: List<String>,
    val subject: String,
    val inReplyTo: String?,
    val references: String?,
    val body: String,
)

object MailMessages {
    /** Builds reply headers from the original's headers, the way mail clients thread. */
    fun reply(
        originalFrom: String,
        originalReplyTo: String?,
        originalSubject: String?,
        originalMessageId: String?,
        originalReferences: String?,
        body: String,
    ): ReplyDraft {
        val to = listOf((originalReplyTo ?: originalFrom).trim())
        val subject = originalSubject?.trim().orEmpty().let { if (it.lowercase().startsWith("re:")) it else "Re: $it" }
        val references = listOfNotNull(originalReferences?.trim()?.ifBlank { null }, originalMessageId?.trim()?.ifBlank { null })
            .joinToString(" ").ifBlank { null }
        return ReplyDraft(to, subject, originalMessageId?.trim()?.ifBlank { null }, references, body)
    }

    /** Parses a List-Unsubscribe header into its mailto and http targets. */
    fun unsubscribeTargets(header: String?): List<String> =
        header.orEmpty().split(',').map { it.trim().removePrefix("<").removeSuffix(">") }.filter { it.startsWith("mailto:") || it.startsWith("http") }
}

/**
 * The email connector over IMAP and SMTP. Archive and label are reversible (move
 * back); reply and unsubscribe are soft. Every method opens and closes its own
 * connection: the phone sleeps a lot and long-lived IMAP sessions do not survive it.
 */
class MailConnector(private val account: MailAccount) : Connector {
    override val actions = setOf(Actions.ARCHIVE_EMAIL.name, Actions.LABEL_EMAIL.name, Actions.REPLY_EMAIL.name, Actions.UNSUBSCRIBE_EMAIL.name)

    override fun execute(p: Proposal): Outcome {
        val id = p.payload["message_id"] ?: return Outcome(false, "no message_id")
        return when (p.spec.name) {
            Actions.ARCHIVE_EMAIL.name -> move(id, "INBOX", account.archiveFolder)
            Actions.LABEL_EMAIL.name -> move(id, "INBOX", p.payload["label"] ?: return Outcome(false, "no label"), copy = true)
            Actions.REPLY_EMAIL.name -> reply(id, p.payload["text"] ?: return Outcome(false, "no text"))
            Actions.UNSUBSCRIBE_EMAIL.name -> unsubscribe(id)
            else -> Outcome(false, "unsupported ${p.spec.name}")
        }
    }

    override fun undo(p: Proposal, o: Outcome): Boolean {
        val token = o.undoToken ?: return false
        // Token format: move:<message_id>:<from>:<to>
        val parts = token.split(':', limit = 4)
        if (parts.size != 4 || parts[0] != "move") return false
        return move(parts[1], parts[3], parts[2]).ok
    }

    private fun session(): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", account.imapHost)
            put("mail.imaps.port", account.imapPort.toString())
            put("mail.smtp.host", account.smtpHost)
            put("mail.smtp.port", account.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
        }
        return Session.getInstance(props)
    }

    private fun <T> withStore(block: (Store) -> T): T {
        val store = session().getStore("imaps")
        store.connect(account.imapHost, account.username, account.password)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    private fun find(folder: Folder, messageId: String): Message? =
        folder.search(jakarta.mail.search.HeaderTerm("Message-ID", messageId)).firstOrNull()

    private fun move(messageId: String, from: String, to: String, copy: Boolean = false): Outcome = try {
        withStore { store ->
            val src = store.getFolder(from).apply { open(Folder.READ_WRITE) }
            val dst = store.getFolder(to)
            if (!dst.exists()) dst.create(Folder.HOLDS_MESSAGES)
            val msg = find(src, messageId) ?: return@withStore Outcome(false, "message not found in $from")
            src.copyMessages(arrayOf(msg), dst)
            if (!copy) msg.setFlag(Flags.Flag.DELETED, true)
            src.close(true)
            Outcome(true, "moved to $to", undoToken = if (copy) null else "move:$messageId:$from:$to")
        }
    } catch (e: Exception) {
        Outcome(false, "mail error: ${e.message}")
    }

    private fun reply(messageId: String, text: String): Outcome = try {
        withStore { store ->
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            val original = find(inbox, messageId) as? MimeMessage ?: return@withStore Outcome(false, "message not found")
            val draft = MailMessages.reply(
                originalFrom = (original.from?.firstOrNull() as? InternetAddress)?.address ?: return@withStore Outcome(false, "no sender"),
                originalReplyTo = (original.replyTo?.firstOrNull() as? InternetAddress)?.address,
                originalSubject = original.subject,
                originalMessageId = original.messageID,
                originalReferences = original.getHeader("References")?.joinToString(" "),
                body = text,
            )
            val s = session()
            val msg = MimeMessage(s).apply {
                setFrom(InternetAddress(account.address, account.displayName))
                setRecipients(Message.RecipientType.TO, draft.to.map { InternetAddress(it) }.toTypedArray())
                subject = draft.subject
                draft.inReplyTo?.let { setHeader("In-Reply-To", it) }
                draft.references?.let { setHeader("References", it) }
                setText(draft.body, "UTF-8")
            }
            Transport.send(msg, account.username, account.password)
            inbox.close(false)
            Outcome(true, "replied to ${draft.to.joinToString()}")
        }
    } catch (e: Exception) {
        Outcome(false, "mail error: ${e.message}")
    }

    private fun unsubscribe(messageId: String): Outcome = try {
        withStore { store ->
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            val original = find(inbox, messageId) as? MimeMessage ?: return@withStore Outcome(false, "message not found")
            val targets = MailMessages.unsubscribeTargets(original.getHeader("List-Unsubscribe")?.joinToString(","))
            val mailto = targets.firstOrNull { it.startsWith("mailto:") } ?: return@withStore Outcome(false, "no mailto unsubscribe; http targets: ${targets.joinToString()}")
            val addr = mailto.removePrefix("mailto:").substringBefore('?')
            val subject = Regex("[?&]subject=([^&]+)").find(mailto)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "unsubscribe"
            val msg = MimeMessage(session()).apply {
                setFrom(InternetAddress(account.address, account.displayName))
                setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(addr)))
                this.subject = subject
                setText("unsubscribe", "UTF-8")
            }
            Transport.send(msg, account.username, account.password)
            inbox.close(false)
            Outcome(true, "unsubscribe sent to $addr")
        }
    } catch (e: Exception) {
        Outcome(false, "mail error: ${e.message}")
    }
}
