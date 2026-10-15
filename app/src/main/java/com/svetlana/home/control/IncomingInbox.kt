package com.svetlana.home.control

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * пользователь сам выдал доступ к уведомлениям. Читаются сообщения (CATEGORY_MESSAGE,
 * MessagingStyle); банки, платёжки, менеджеры паролей и госуслуги ([SensitiveApps]) пропускаются.
 *
 * Отвечать можно ТОЛЬКО в мессенджеры из точного списка пакетов ([REPLY_PACKAGES]): любое
 * приложение может нарисовать уведомление «как сообщение» и подставить своё поле ответа, но текст
 * пользователя ему не достанется.
 *
 * Мои собственные ответы, которые мессенджер дописывает в уведомление, не делают меня
 * отправителем ([IncomingLogic.inbound]). Сводка группы берётся, только если в этой же группе
 * уведомлений нет отдельных уведомлений с сообщениями.
 *
 * Ответ уходит через стандартную кнопку «Ответить» (RemoteInput) и только если беседа в
 * уведомлении всё ещё та, что показали в подтверждении: сверка [IncomingEntry.fingerprint] и отправка
 * делаются атомарно.
 */
object IncomingInbox {

    private class Item(
        val entry: IncomingEntry,
        val reply: Notification.Action?,
        val summary: Boolean,
        val group: String?
    )

    enum class Send { SENT, GONE, CHANGED, UNSUPPORTED }

    private const val MAX_ITEMS = 50
    private val items = LinkedHashMap<String, Item>()

    /** Мессенджеры, в которые разрешён ответ из уведомления (точные имена пакетов). */
    internal val REPLY_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram",
        "com.viber.voip", "org.thoughtcrime.securesms", "com.vkontakte.android", "ru.oneme.app",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.facebook.orca", "com.discord", "com.skype.raider", "jp.naver.line.android", "com.instagram.android"
    )

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

    /** Сводка группы уведомлений — только если в ТОЙ ЖЕ группе нет отдельных (иначе дубли). */
    @Synchronized
    fun entries(): List<IncomingEntry> {
        val groupsWithChildren = items.values.filter { !it.summary }.mapNotNull { it.group }.toSet()
        return items.values.filter { !it.summary || it.group == null || it.group !in groupsWithChildren }.map { it.entry }
    }

    @Synchronized
    fun entry(key: String): IncomingEntry? = items[key]?.entry

    /**
     * Ответ через кнопку «Ответить» именно этого уведомления и именно этой беседы.
     * Сверка и отправка под одной блокировкой: обновление уведомления не вклинится между ними.
     */
    @Synchronized
    fun sendReply(context: Context, key: String, fingerprint: String, text: String): Send {
        val item = items[key] ?: return Send.GONE
        if (item.entry.fingerprint != fingerprint) return Send.CHANGED
        val action = item.reply ?: return Send.UNSUPPORTED
        val free = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }
        if (free.isEmpty()) return Send.UNSUPPORTED
        val pending = action.actionIntent ?: return Send.UNSUPPORTED
        // Неизменяемый PendingIntent не примет текст ответа — честно говорим «не поддерживается».
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pending.isImmutable) return Send.UNSUPPORTED
        val results = Bundle().apply { free.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(free.toTypedArray(), fill, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        pending.send(context, 0, fill)
        return Send.SENT
    }

    private fun replyActionOf(n: Notification, pkg: String): Notification.Action? {
        if (pkg !in REPLY_PACKAGES) return null
        val action = n.actions?.firstOrNull { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } } ?: return null
        val pending = action.actionIntent ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pending.isImmutable) return null
        // Кнопка должна вести в тот же мессенджер, что показал уведомление.
        if (pending.creatorPackage != null && pending.creatorPackage != pkg) return null
        return action
    }

    private fun extract(context: Context, sbn: StatusBarNotification): Item? {
        val pkg = sbn.packageName ?: return null
        if (pkg == context.packageName || SensitiveApps.isSensitive(pkg)) return null
        if (sbn.isOngoing) return null
        val n = sbn.notification ?: return null
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val reply = replyActionOf(n, pkg)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val messaging = n.category == Notification.CATEGORY_MESSAGE || style != null || reply != null
        if (!messaging) return null
        // Сводку без сообщений не берём («5 новых сообщений»).
        if (summary && style?.messages.isNullOrEmpty()) return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val inbound: Inbound
        var chat: String? = null
        if (style != null && style.messages.isNotEmpty()) {
            val me = style.user
            val userKey = me.key
            val userName = me.name?.toString()
            val allAnonymous = style.messages.all { it.person == null }
            val messages = style.messages.map { m ->
                val person = m.person
                val fromMe = IncomingLogic.isFromMe(
                    person?.key, person?.name?.toString(), userKey, userName,
                    hasPerson = person != null, allAnonymous = allAnonymous
                )
                StyleMessage(person?.name?.toString(), m.text?.toString(), if (m.timestamp > 0) m.timestamp else sbn.postTime, fromMe)
            }
            inbound = IncomingLogic.inbound(messages, title) ?: return null
            if (style.isGroupConversation) chat = style.conversationTitle?.toString()?.trim()?.ifEmpty { null }
        } else {
            val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (title.isBlank() || text.isBlank()) return null
            inbound = Inbound(title, text, sbn.postTime, answered = false)
        }
        val app = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }
        val convId = n.shortcutId?.trim()?.ifEmpty { null }
        return Item(
            IncomingEntry(
                sbn.key, pkg, app, inbound.sender.take(80), inbound.text.take(600), inbound.time,
                canReply = reply != null, chat = chat?.take(80), answered = inbound.answered, convId = convId
            ),
            reply,
            summary,
            sbn.groupKey
        )
    }
}

/**
 * Исполнение команд про входящие. Ответ сначала «прикалывается» к конкретному уведомлению
 * ([pin]): в действии запоминаются ключ, собеседник, приложение, отпечаток беседы и кусочек
 * его сообщения — именно их пользователь видит в подтверждении. Если к моменту отправки уведомление
 * с тем же ключом принадлежит другой беседе — ответ не уходит.
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
        val app = action.app
        val inApp = app?.let { t(" в $it", " in $it") }.orEmpty()
        return when (val p = IncomingLogic.replyTarget(name, IncomingInbox.entries(), app)) {
            is IncomingPick.Found -> Pinned.Ok(action.copy(
                contact = IncomingLogic.who(p.entry),
                key = p.entry.key,
                app = p.entry.app,
                fp = p.entry.fingerprint,
                quote = IncomingLogic.safeText(p.entry.text, english()).take(80)
            ))
            is IncomingPick.Ambiguous -> Pinned.Fail(t(
                "Подходят несколько: ${p.names.joinToString(", ")}. Уточните имя или приложение: «ответь Серому в телеграме …»",
                "Several match: ${p.names.joinToString(", ")}. Say the full name or the app: “reply to Sam in Telegram …”"))
            IncomingPick.NotFound -> Pinned.Fail(t(
                "Не вижу сообщений от «${name.orEmpty().take(40)}»$inApp, на которые можно ответить из уведомления. Скажите «ответь Серому …» или «ответь ему …»",
                "I don't see messages from “${name.orEmpty().take(40)}”$inApp that can be answered from a notification. Say “reply to Sam …” or “reply …”"))
            IncomingPick.Empty -> Pinned.Fail(t(
                "Нет сообщений$inApp, на которые можно ответить из уведомления (уведомление скрыто или в нём нет кнопки «Ответить»)",
                "No messages$inApp that can be answered from a notification (it was dismissed or has no Reply button)"))
        }
    }

    fun read(action: SvetlanaAction.ReadIncoming): ActionResult {
        val steps = mutableListOf(ProofStep(ProofStage.PLAN, StepStatus.OK,
            "PLAN_TARGET=READ_INCOMING contact=${action.contact ?: "-"} app=${action.app ?: "-"} latest=${action.latestOnly}"))
        if (!IncomingInbox.isEnabled(context)) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "notification access not granted")
            return ActionResult(action, false, noAccess(), steps, "notification")
        }
        val entries = IncomingInbox.entries()
        steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.OK, "PLAN_RESULT=VERIFIED entries=${entries.size}")
        val limit = if (action.latestOnly) 1 else IncomingLogic.MAX_READ
        return ActionResult(action, true,
            IncomingLogic.summary(action.contact, entries, english(), action.app, limit), steps, "notification")
    }

    @Suppress("UNUSED_PARAMETER")
    fun reply(grant: ActionGrant, action: SvetlanaAction.ReplyIncoming): ActionResult {
        val steps = mutableListOf(ProofStep(ProofStage.PLAN, StepStatus.OK, "PLAN_TARGET=REPLY_INCOMING app=${action.app ?: "-"}"))
        val key = action.key
        val fp = action.fp
        val entry = key?.let { IncomingInbox.entry(it) }
        if (key == null || fp == null || entry == null) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "notification gone")
            return ActionResult(action, false, t(
                "Уведомление уже исчезло — ответ не отправлен",
                "The notification is gone — the reply was not sent"), steps, "notification")
        }
        val sent = try { IncomingInbox.sendReply(context, key, fp, action.text) } catch (t: Throwable) { IncomingInbox.Send.UNSUPPORTED }
        val ok = sent == IncomingInbox.Send.SENT
        steps += ProofStep(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED, "reply via RemoteInput: $sent")
        steps += ProofStep(ProofStage.RESULT_VERIFIED, if (ok) StepStatus.UNVERIFIED else StepStatus.FAILED,
            if (ok) "PLAN_RESULT=NOT PROVEN delivered by the messenger" else "PLAN_RESULT=NOT VERIFIED")
        val who = action.contact ?: IncomingLogic.who(entry)
        val message = when (sent) {
            IncomingInbox.Send.SENT -> t("Ответила $who в ${entry.app}: «${action.text.take(60)}»",
                "Replied to $who in ${entry.app}: “${action.text.take(60)}”")
            IncomingInbox.Send.GONE -> t("Уведомление уже исчезло — ответ не отправлен",
                "The notification is gone — the reply was not sent")
            IncomingInbox.Send.CHANGED -> t("Уведомление изменилось (там уже другая беседа) — ответ не отправлен, повторите команду",
                "The notification changed to another conversation — the reply was not sent, please repeat")
            IncomingInbox.Send.UNSUPPORTED -> t("${entry.app} не даёт отвечать из уведомления — ответ не отправлен",
                "${entry.app} doesn't allow replying from the notification — the reply was not sent")
        }
        return ActionResult(action, ok, message, steps, "notification")
    }
}
