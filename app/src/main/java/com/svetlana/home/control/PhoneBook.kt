package com.svetlana.home.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.svetlana.home.permissions.PermissionManager

/** Один номер из телефонной книги. */
data class ContactPhone(val contactId: Long, val name: String, val number: String, val primary: Boolean = false)

/** Результат поиска номера по имени. */
sealed class PhonePick {
    data class Number(val name: String, val number: String) : PhonePick()
    /** У одного человека несколько разных номеров и ни один не основной — выбирает пользователь. */
    data class ManyNumbers(val name: String, val contactId: Long) : PhonePick()
    /** Под имя подходят разные люди — не угадываем. */
    data class Ambiguous(val names: List<String>) : PhonePick()
    object NotFound : PhonePick()
}

/**
 * Поиск номера по сказанному имени (чистая логика, без Android).
 *
 * Правила те же, что и для мессенджеров ([ContactMatch]): сначала имя как сказано, только если
 * его нет — падежные формы («Серому» → «Серый»); совпадение — целое имя или имя + пробел;
 * подошли два разных контакта — отказ ([PhonePick.Ambiguous]). Один и тот же номер в записях
 * «+7 912…» и «8 912…» считается одним (последние 10 цифр).
 */
object PhoneLookup {

    private val PHONE_RE = Regex("^\\+?[0-9\\s\\-().]+$")
    private const val MIN_DIGITS = 3

    fun isPhoneNumber(s: String): Boolean {
        val t = s.trim()
        return PHONE_RE.matches(t) && t.count { it.isDigit() } >= MIN_DIGITS
    }

    fun normalizeNumber(s: String): String {
        val t = s.trim()
        val digits = t.filter { it.isDigit() }
        return if (t.startsWith("+")) "+$digits" else digits
    }

    private fun comparable(n: String): String = n.filter { it.isDigit() }.takeLast(10)

    fun pick(spoken: String, phones: List<ContactPhone>): PhonePick {
        val forms = ContactMatch.forms(spoken)
        if (forms.isEmpty()) return PhonePick.NotFound
        val usable = phones.filter { it.number.count { c -> c.isDigit() } >= MIN_DIGITS }
        var hits = usable.filter { ContactMatch.matches(it.name, forms[0]) }
        if (hits.isEmpty()) {
            val derived = forms.drop(1)
            hits = usable.filter { p -> derived.any { ContactMatch.matches(p.name, it) } }
        }
        if (hits.isEmpty()) return PhonePick.NotFound
        val byContact = hits.groupBy { it.contactId }
        if (byContact.size > 1) {
            return PhonePick.Ambiguous(byContact.values.map { it.first().name.trim() }.distinct().take(5))
        }
        val rows = byContact.values.first()
        val name = rows.first().name.trim()
        val numbers = rows.distinctBy { comparable(it.number) }
        if (numbers.size == 1) return PhonePick.Number(name, normalizeNumber(numbers[0].number))
        val primary = numbers.filter { it.primary }
        if (primary.size == 1) return PhonePick.Number(name, normalizeNumber(primary[0].number))
        return PhonePick.ManyNumbers(name, rows.first().contactId)
    }
}

/** Кому звонить / писать SMS. */
sealed class Callee {
    data class Dial(val name: String, val number: String) : Callee()
    /** Несколько номеров — открываем карточку контакта, номер выбирает пользователь. */
    data class Card(val name: String, val intent: Intent) : Callee()
    data class Refused(val message: String, val why: String) : Callee()
}

/** Читает телефонную книгу (только при разрешении «Контакты») и находит номер по имени. */
class CalleeResolver(
    private val context: Context,
    private val permissionManager: PermissionManager
) {

    fun resolve(contact: String): Callee {
        val spoken = contact.trim()
        if (PhoneLookup.isPhoneNumber(spoken)) return Callee.Dial(spoken, PhoneLookup.normalizeNumber(spoken))
        if (ContactMatch.norm(spoken).isEmpty()) return Callee.Refused("Не поняла, кому", "empty contact")
        if (!permissionManager.isGranted(android.Manifest.permission.READ_CONTACTS)) {
            return Callee.Refused(
                "Чтобы звонить и писать по имени, разрешите Светлане доступ к контактам (Настройки → Разрешения)",
                "contacts permission not granted")
        }
        return when (val p = PhoneLookup.pick(spoken, phones())) {
            is PhonePick.Number -> Callee.Dial(p.name, p.number)
            is PhonePick.ManyNumbers -> Callee.Card(p.name,
                Intent(Intent.ACTION_VIEW,
                    Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, p.contactId.toString()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            is PhonePick.Ambiguous -> Callee.Refused(
                if (p.names.size == 1) "Нашла несколько контактов «${p.names[0].take(40)}» — назовите полное имя"
                else "Нашла несколько контактов: ${p.names.joinToString(", ") { it.take(40) }} — назовите полное имя",
                "ambiguous contact")
            PhonePick.NotFound -> Callee.Refused("Не нашла «${spoken.take(40)}» в контактах", "contact not found")
        }
    }

    private fun phones(): List<ContactPhone> {
        val out = mutableListOf<ContactPhone>()
        try {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY),
                null, null, null
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_ROWS) {
                    val name = c.getString(1)
                    val number = c.getString(2)
                    if (name != null && number != null) {
                        out += ContactPhone(c.getLong(0), name, number, c.getInt(3) != 0)
                    }
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    companion object {
        private const val MAX_ROWS = 20000
    }
}
