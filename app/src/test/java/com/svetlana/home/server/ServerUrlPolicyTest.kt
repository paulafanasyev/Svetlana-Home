package com.svetlana.home.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress

class ServerUrlPolicyTest {
    @Test
    fun homeLanAndPublicHttpsAreAllowed() {
        assertThat(ServerUrlPolicy.check("http://192.168.1.10:8000")).isNull()
        assertThat(ServerUrlPolicy.check("http://10.0.0.5")).isNull()
        assertThat(ServerUrlPolicy.check("http://my-pc.local:11434")).isNull()
        assertThat(ServerUrlPolicy.check("https://gpu.example.com")).isNull()
    }

    @Test
    fun phoneItselfMetadataAndLinkLocalAreBlocked() {
        assertThat(ServerUrlPolicy.check("http://127.0.0.1:8080")).isNotNull()
        assertThat(ServerUrlPolicy.check("http://localhost")).isNotNull()
        assertThat(ServerUrlPolicy.check("http://169.254.169.254/latest")).isNotNull()
        assertThat(ServerUrlPolicy.check("https://metadata.google.internal")).isNotNull()
        assertThat(ServerUrlPolicy.check("http://[::1]:80")).isNotNull()
        assertThat(ServerUrlPolicy.check("http://0.0.0.0")).isNotNull()
    }

    @Test
    fun plainHttpToInternetAndCredentialsAreRejected() {
        assertThat(ServerUrlPolicy.check("http://gpu.example.com")).isNotNull()
        assertThat(ServerUrlPolicy.check("http://8.8.8.8")).isNotNull()
        assertThat(ServerUrlPolicy.check("https://user:pass@gpu.example.com")).isNotNull()
        assertThat(ServerUrlPolicy.check("ftp://gpu.example.com")).isNotNull()
        assertThat(ServerUrlPolicy.check("not a url")).isNotNull()
    }

    @Test
    fun blockedAddressesAfterDns() {
        assertThat(ServerUrlPolicy.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue()
        assertThat(ServerUrlPolicy.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue()
        assertThat(ServerUrlPolicy.isBlocked(InetAddress.getByName("192.168.1.10"))).isFalse()
    }
}
