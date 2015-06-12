package com.marvinware.btchatbenchmark.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import org.msgpack.MessagePack
import org.msgpack.annotation.Message
import java.io.IOException
import java.util.*
import java.util.concurrent.ThreadPoolExecutor

val THROUGHPUT_TAG = "ThroughputBenchmark"
val THROUGHPUT_UUID = UUID.fromString("dc5a1c37-a796-4916-abe1-20158a3198a7")

Message data class ThroughputMessage(var content: ByteArray = ByteArray(0))

data class ThroughputResult(val localName: String, val remoteName: String, val bytes: Int, val time: Long)

class ThroughputRunner(localName: String, device: BluetoothDevice, iterations:Int) {
    val localName = localName
    val iterations = iterations
    val device = device

    fun execute(): List<ThroughputResult> {
        val msgpack = MessagePack()
        val remoteName = device.getName()
        val runs = ArrayList<ThroughputResult>()

        try {
            Log.d(THROUGHPUT_TAG, "attempting connection with ${device.getName()}")
            val socket = device.createInsecureRfcommSocketToServiceRecord(THROUGHPUT_UUID)
            socket.connect()
            val input = socket.getInputStream()
            for(i in 1..iterations){
                val start = System.nanoTime()
                val msg = msgpack.read(input, javaClass<ThroughputMessage>())
                val end = System.nanoTime()
                runs.add(ThroughputResult(localName, remoteName, msg.content.size(), end-start));
            }
            socket.close()
            Log.d(THROUGHPUT_TAG, "${iterations} iterations completed")
        } catch(i: IOException) {
            Log.e(THROUGHPUT_TAG, "exception while sending message", i)
        }
        return runs
    }
}

class ThroughputListener(adapter: BluetoothAdapter, outgoing: ThreadPoolExecutor): Runnable {
    private val btAdapter = adapter
    private val outgoing = outgoing

    override fun run() {
        Log.d(THROUGHPUT_TAG, "listening for connections")
        var serverSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord(THROUGHPUT_TAG, THROUGHPUT_UUID)

        while(true) {
            try{
                val socket = serverSocket.accept()
                Log.d(THROUGHPUT_TAG, "Launching ProcessMessageThread on $socket: ${socket.isConnected()}")

                outgoing.execute(ThroughputHandler(socket))
            } catch (e: IOException){
                Log.e(THROUGHPUT_TAG, "serverSocket accept() failed", e)
                continue
            }
        }
    }
}

class ThroughputHandler(socket: BluetoothSocket?) : Runnable {
    private val socket = socket
    private val random = Random()
    private val defaultSize = 4096

    override fun run() {
        if(socket == null){
            Log.e(THROUGHPUT_TAG, "got a null socket in ProcessMessage thread")
            return
        }

        try {
            val msgpack = MessagePack()
            val outstream = socket.getOutputStream()
            val buffer = ByteArray(defaultSize)

            Log.d(THROUGHPUT_TAG, "got connection from ${socket.getRemoteDevice()}")
            while(true) {
                random.nextBytes(buffer)
                val message = ThroughputMessage(buffer)
                msgpack.write(outstream, message)
            }
        } catch(i: IOException) {
            Log.e(THROUGHPUT_TAG, "error sending message", i)
        }
    }
}
