package com.svetlana.home.avatar

import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.ui.components.SvetlanaAvatarImage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Портрет Светланы из Svetlana 2.0 реально лежит в APK и декодируется.
 * Без этого вместо аватара на экране только пустой контур.
 */
class AvatarAssetDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun portraitIsPackagedAndDecodes() {
        val bytes = context.assets.open(SvetlanaAvatarImage.ASSET).use { it.readBytes() }
        assertTrue("Портрет слишком маленький: ${bytes.size} байт", bytes.size > 10_000)
        assertTrue("Портрет не JPEG", bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        val image = SvetlanaAvatarImage.load(context)
        assertNotNull("Портрет не декодировался", image)
        assertTrue("Портрет пустой", image!!.width > 64 && image.height > 64)
    }
}
