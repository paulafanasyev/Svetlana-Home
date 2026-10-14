package com.svetlana.home.control

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ProofStep
import com.svetlana.home.core.StepStatus
import com.svetlana.home.core.Tr
import com.svetlana.home.hands.SensitiveApps

/**
 * Входящие из уведомлений мессенджеров (только в памяти, на диск не пишется).
 *
 * Наполняется сервисом уведомлений (NotificationDotsService) — только после того, как
 * пользователь сам выдал доступ к уведомлениям. Берутся только сообщения
 * (CATEGORY_MESSAGE, MessagingStyle или кнопка «Ответить»); банки, платёжки, менеджеры паролей и
 * госуслуги ([SensitiveApps]) пропускаются целиком. Ответ уходит через стандартную кнопку
 * «Ответить» уведомления (RemoteInput) — без Hands и без открытия чата.
 */
object IncomingInbox {

    private class Item(val entry: IncomingEntry, val reply: Notification.Action?)

    private const val MAX_ITEMS = 50
    private val items = LinkedHashMap<String, Item>()

    fun isEnabled(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (t: Throwable) {
        false
    }

    @Synchronized
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        val item = try { extract(context, sbn) } catch (t: Throwable) { null }
        items.remove(sbn.key)
        if (item != null) {
            items[sbn.key] = item
            while (items.size > MAX_ITEMS) items.remove(items.keys.first())
        }
    }

    @Synchronized
    fun onRemoved(key: String) {
        items.remove(key)
    }

    @Synchronized
    fun reset(context: Context, list: Array<StatusBarNotification>?) {
        items.clear()
        list.orEmpty().sortedBy { it.postTime }.forEach { onPosted(context, it) }
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    @Synchronized
    fun entries(): List<IncomingEntry> = items.values.map { it.entry }

    @Synchronized
    fun entry(key: String): IncomingEntry? = items[key]?.entry

    @Synchronized
    private fun replyAction(key: String): Notification.Action? = items[key]?.reply

    /** Ответ через кнопку «Ответить» именно этого уведомления. */
    fun sendReply(context: Context, key: String, text: String): Boolean {
        val action = replyAction(key) ?: return false
        val free = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }
        if (free.isEmpty()) return false
        val pending = action.actionIntent ?: return false
        val results = Bundle().apply { free.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(free.toTypedArray(), fill, results)
        pending.send(context, 0, fill)
        return true
    }

    private fun extract(context: Context, sbn: StatusBarNotification): Item? {
        val pkg = sbn.packageName ?: return null
        if (pkg == context.packageName || SensitiveApps.isSensitive(pkg)) return null
        if (sbn.isOngoing) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (n.category != Notification.CATEGORY_MESSAGE && style == null && reply == null) return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var sender = title
        var chat: String? = null
        var time = sbn.postTime
        val messages = style?.messages.orEmpty()
        val text: String
        if (style != null && messages.isNotEmpty()) {
            val last = messages.last()
            sender = last.person?.name?.toString()?.trim()?.ifEmpty { null } ?: title
            if (style.isGroupConversation) chat = style.conversationTitle?.toString()?.trim()?.ifEmpty { null }
            text = messages.takeLast(3).mapNotNull { it.text?.toString()?.trim()?.ifEmpty { null } }.joinToString(" / ")
            if (last.timestamp > 0) time = last.timestamp
        } else {
            text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
        }
        if (sender.isBlank() || text.isBlank()) return null
        val app = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }
        return Item(
            IncomingEntry(sbn.key, pkg, app, sender.take(80), text.take(600), time, canReply = reply != null, chat = chat?.take(80)),
            reply
        )
    }
}

/**
 * Исполнение команд про входящие. Ответ сначала «прикалывается» к конкретному уведомлению
 * ([pin]): в действии запоминаются ключ, собеседник и приложение — именно их пользователь видит
 * в подтверждении, и именно туда уйдёт ответ. Пришло новое сообщение от другого человека
 * между вопросом и «Подтвердить» — ответ всё равно уйдёт тому, кого показали.
 */
class IncomingMessages(private val context: Context) {

    sealed class Pinned {
        data class Ok(val action: SvetlanaAction.ReplyIncoming) : Pinned()
        data class Fail(val message: String) : Pinned()
    }

    private fun english(): Boolean = runCatching { Tr.isEnglish(context) }.getOrDefault(false)
    private fun t(ru: String, en: String): String = if (english()) en else ru

    private fun noAccess(): String = t(
        "Нет доступа к уведомлениям: включите «Значки уведомлений» в настройках главного экрана Светланы",
        "No notification access: turn on “Notification dots” in Svetlana's Home screen settings"
    )

    fun pin(action: SvetlanaAction.ReplyIncoming): Pinned {
        if (!IncomingInbox.isEnabled(context)) return Pinned.Fail(noAccess())
        val name = action.contact
        return when (val p = IncomingLogic.replyTarget(name, IncomingInbox.entries())) {
            is IncomingPick.Found -> Pinned.Ok(action.copy(contact = IncomingLogic.who(p.entry), key = p.entry.key, app = p.entry.app))
            is IncomingPick.Ambiguous -> Pinned.Fail(t(
                "Сообщения от нескольких людей: ${p.names.joinToString(", ")} — назовите полное имя",
                "Messages from several people: ${p.names.joinToString(", ")} — say the full name"))
            IncomingPick.NotFound -> Pinned.Fail(t(
                "Не вижу сообщений от «${name.orEmpty().take(40)}», на которые можно ответить. Скажите «ответь Серому …» или «ответь ему …»",
                "I don't see messages from “${name.orEmpty().take(40)}” that can be answered. Say “reply to Sam …” or “reply …”"))
            IncomingPick.Empty -> Pinned.Fail(t(
                "Нет сообщений, на которые можно ответить из уведомления",
                "No messages that can be answered from a notification"))
        }
    }

    fun read(contact: String?): ActionResult {
        val action = SvetlanaAction.ReadIncoming(contact)
        val steps = mutableListOf(ProofStep(ProofStage.PLAN, StepStatus.OK, "PLAN_TARGET=READ_INCOMING contact=${contact ?: "-"}"))
        if (!IncomingInbox.isEnabled(context)) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "notification access not granted")
            return ActionResult(action, false, noAccess(), steps, "notification")
        }
        val entries = IncomingInbox.entries()
        steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.OK, "PLAN_RESULT=VERIFIED entries=${entries.size}")
        return ActionResult(action, true, IncomingLogic.summary(contact, entries, english()), steps, "notification")
    }

    @Suppress("UNUSED_PARAMETER")
    fun reply(grant: ActionGrant, action: SvetlanaAction.ReplyIncoming): ActionResult {
        val steps = mutableListOf(ProofStep(ProofStage.PLAN, StepStatus.OK, "PLAN_TARGET=REPLY_INCOMING app=${action.app ?: "-"}"))
        val key = action.key
        val entry = key?.let { IncomingInbox.entry(it) }
        if (key == null || entry == null) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "notification gone")
            return ActionResult(action, false, t(
                "Уведомление уже исчезло — ответ не отправлен",
                "The notification is gone — the reply was not sent"), steps, "notification")
        }
        val sent = try { IncomingInbox.sendReply(context, key, action.text) } catch (t: Throwable) { false }
        steps += ProofStep(ProofStage.ACTION_PERFORMED, if (sent) StepStatus.OK else StepStatus.FAILED,
            if (sent) "reply sent via notification RemoteInput" else "reply action failed")
        steps += ProofStep(ProofStage.RESULT_VERIFIED, if (sent) StepStatus.UNVERIFIED else StepStatus.FAILED,
            if (sent) "PLAN_RESULT=NOT PROVEN delivered by the messenger" else "PLAN_RESULT=NOT VERIFIED")
        val who = IncomingLogic.who(entry)
        return ActionResult(action, sent,
            if (sent) t("Ответила $who в ${entry.app}: «${action.text.take(60)}»",
                "Replied to $who in ${entry.app}: “${action.text.take(60)}”")
            else t("Не получилось ответить через уведомление", "Couldn't reply via the notification"),
            steps, "notification")
    }
}
