package com.andrerinas.headunitrevived.aap

import android.content.Context
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

internal interface AapRead {
    fun read(): Int
    fun stop() {}

    abstract class Base internal constructor(
            private val connection: AccessoryConnection?,
            internal val ssl: AapSsl,
            internal val handler: AapMessageHandler) : AapRead {

        override fun read(): Int {
            if (connection == null) {
                AppLog.e("No connection.")
                return -1
            }

            return doRead(connection)
        }

        protected abstract fun doRead(connection: AccessoryConnection): Int

        override fun stop() {
            handler.stop()
        }
    }

    object Factory {
        fun create(connection: AccessoryConnection, transport: AapTransport, recorder: MicRecorder, aapAudio: AapAudio, aapVideo: AapVideo, settings: Settings, notification: BackgroundNotification, context: Context, isSelfMode: Boolean = false): AapRead {
            val handler = AapMessageHandlerType(transport, recorder, aapAudio, aapVideo, settings, notification, context, isSelfMode)

            return if (connection.isSingleMessage)
                AapReadSingleMessage(connection, transport.ssl, handler)
            else
                AapReadMultipleMessages(connection, transport.ssl, handler)
        }
    }
}
