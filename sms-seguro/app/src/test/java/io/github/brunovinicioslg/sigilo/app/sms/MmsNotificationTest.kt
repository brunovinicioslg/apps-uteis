package io.github.brunovinicioslg.sigilo.app.sms

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test

class MmsNotificationTest {

    /** An m-notification-ind as carriers send it (OMA MMS 1.2 encapsulation). */
    private fun notification(fromField: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x8C.toByte(), 0x82.toByte())) // message type: notification
        write(0x98); write("T0042".toByteArray()); write(0) // transaction id
        write(byteArrayOf(0x8D.toByte(), 0x92.toByte())) // version 1.2
        write(fromField)
        write(byteArrayOf(0x8A.toByte(), 0x80.toByte())) // class: personal
        write(byteArrayOf(0x8E.toByte(), 0x02, 0x10, 0x00)) // size
        write(0x83); write("http://mmsc.operadora.com.br/m?id=81f".toByteArray()); write(0) // content location
    }.toByteArray()

    private fun from(address: String): ByteArray {
        val text = address.toByteArray() + 0
        return byteArrayOf(0x89.toByte(), (text.size + 1).toByte(), 0x80.toByte()) + text
    }

    @Test
    fun readsTheSender() {
        assertThat(MmsNotification.from(notification(from("+5531999998888/TYPE=PLMN")))).isEqualTo("+5531999998888")
        assertThat(MmsNotification.from(notification(from("31999998888")))).isEqualTo("31999998888")
    }

    @Test
    fun noSenderOrGarbageGivesNothing() {
        // "Insert address" token: the MMS center fills the sender in later; nothing to read yet.
        assertThat(MmsNotification.from(notification(byteArrayOf(0x89.toByte(), 0x01, 0x81.toByte())))).isNull()
        assertThat(MmsNotification.from(ByteArray(0))).isNull()
        assertThat(MmsNotification.from(ByteArray(300) { (it * 37).toByte() })).isNull()
    }
}
