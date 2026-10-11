package com.svetlana.home.server

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Какой адрес персонального сервера допустим (защита от SSRF):
 * - только http/https, без логина/пароля в адресе;
 * - запрещены сам телефон (localhost/127.x/::1), link-local (169.254.x — в т.ч. облачные metadata),
 *   0.0.0.0, multicast и служебные имена metadata;
 * - http без шифрования — только в домашней сети (10.x, 172.16–31.x, 192.168.x, fc00::/7, *.local);
 *   сервер в интернете — только https.
 * Имя хоста дополнительно проверяется после DNS ([safeDns]): имя, указывающее на запрещённый адрес
 * (DNS rebinding), отклоняется.
 */
object ServerUrlPolicy {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val BLOCKED_NAMES = setOf("localhost", "metadata", "metadata.google.internal", "instance-data")

    /** null — адрес допустим, иначе причина отказа для пользователя. */
    fun check(url: String): String? {
        val u = url.trim().toHttpUrlOrNull() ?: return "Некорректный адрес сервера"
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return "Адрес не должен содержать логин или пароль"
        val host = u.host.lowercase().trimEnd('.')
        if (host in BLOCKED_NAMES || host.endsWith(".localhost") || host.endsWith(".internal")) {
            return "Этот адрес запрещён: служебный или сам телефон"
        }
        val ip = ipLiteral(host)
        if (ip != null && isBlocked(ip)) return "Этот адрес запрещён: служебный или сам телефон"
        if (u.scheme == "http" && !isPrivateLan(host, ip)) return "Для сервера в интернете нужен защищённый адрес https://"
        return null
    }

    /** Запрещённые назначения: сам телефон, link-local (metadata облаков), 0.0.0.0, multicast. */
    fun isBlocked(addr: InetAddress): Boolean =
        addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress

    private fun isPrivateLan(host: String, ip: InetAddress?): Boolean {
        if (host.endsWith(".local")) return true
        if (ip == null) return false
        if (ip is Inet4Address) return ip.isSiteLocalAddress
        if (ip is Inet6Address) return (ip.address[0].toInt() and 0xFE) == 0xFC
        return false
    }

    /** Адрес-литерал без обращения к DNS; для имён — null. */
    private fun ipLiteral(host: String): InetAddress? = try {
        when {
            IPV4.matches(host) -> InetAddress.getByName(host)
            host.contains(':') -> InetAddress.getByName(host.removePrefix("[").removeSuffix("]"))
            else -> null
        }
    } catch (t: Throwable) { null }

    /** DNS с проверкой результата: запрещённые адреса отбрасываются, если ничего не осталось — отказ. */
    val safeDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val allowed = Dns.SYSTEM.lookup(hostname).filterNot { isBlocked(it) }
            if (allowed.isEmpty()) throw UnknownHostException("Адрес $hostname запрещён политикой безопасности")
            return allowed
        }
    }
}
