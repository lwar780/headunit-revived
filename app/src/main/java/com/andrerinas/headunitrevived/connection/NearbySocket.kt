package com.andrerinas.headunitrevived.connection

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

class NearbySocket : Socket() {
    @Volatile var inputStreamWrapper: InputStream? = null
    @Volatile var outputStreamWrapper: ParcelFileDescriptor.AutoCloseOutputStream? = null

    override fun isConnected(): Boolean {
        return true
    }
    
    override fun getInetAddress(): InetAddress {
        return InetAddress.getLoopbackAddress()
    }

    override fun getInputStream(): InputStream {
        return object : InputStream() {
            private fun awaitWrapper(): InputStream {
                val deadline = System.currentTimeMillis() + 30_000
                while (inputStreamWrapper == null) {
                    if (System.currentTimeMillis() > deadline) {
                        throw java.net.SocketTimeoutException("NearbySocket: inputStream not ready after 30s")
                    }
                    Thread.sleep(10)
                }
                return inputStreamWrapper!!
            }

            override fun read(): Int = awaitWrapper().read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = awaitWrapper().read(b, off, len)
        }
    }

    override fun getOutputStream(): OutputStream {
        val deadline = System.currentTimeMillis() + 30_000
        while (outputStreamWrapper == null) {
            if (System.currentTimeMillis() > deadline) {
                throw java.net.SocketTimeoutException("NearbySocket: outputStream not ready after 30s")
            }
            Thread.sleep(10)
        }
        return outputStreamWrapper!!
    }
}