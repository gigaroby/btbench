package com.marvinware.btchatbenchmark.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import org.msgpack.MessagePack
import org.msgpack.annotation.Message
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import kotlin.properties.Delegates


// val TAG = "MessageBenchmark"
val MESSAGE_TAG = "MessageBenchmark"
val MESSAGE_UUID = UUID.fromString("7bf58d64-b885-4f53-a7a4-cf41ac7521eb")

data class MessageResult(
        val localName: String,
        val remoteName: String,
        val size: Int,
        val started: Long,
        val received: Long,
        val finished: Long
)

data class MessageTimes(
        var started: Long = 0,
        var received: Long = 0,
        var finished: Long = 0
)

Message data class MessageRequest(var uuid: String = "", var content: ByteArray = ByteArray(0))
Message data class MessageResponse(var uuid: String = "", var received: Long = 0)


class MessageRunner(
        localName: String,
        devices: List<BluetoothDevice>,
        outgoing: ThreadPoolExecutor,
        messageSize: Int,
        messages: Int
) {
    val localName = localName
    val outgoing = outgoing
    val devices = devices
    val messageSize = messageSize
    val messages = messages

    fun execute(): List<MessageResult> {
        val waitGroup = CountDownLatch((messages * devices.size()) as Int)
        val results = ConcurrentHashMap<String, MessageTimes>()
        val uuidDevice = HashMap<String, String>()
        for(i in 1..messages){
            devices.forEach { device ->
                val uuid = UUID.randomUUID().toString()
                uuidDevice.put(device.getName(), uuid)
                outgoing.execute(MessageSender(
                        uuid = uuid,
                        started = System.currentTimeMillis(),
                        messageSize = messageSize,
                        device = device,
                        counter = waitGroup,
                        results = results
                ))
            }
        }
        Log.d(MESSAGE_TAG, "will wait for all processes to terminate")
        waitGroup.await()
        return results.map { pair ->
            val times = pair.getValue()
            MessageResult(
                    localName = localName,
                    remoteName = uuidDevice.get(pair.getKey()),
                    size = messageSize,
                    started = times.started,
                    received = times.received,
                    finished = times.finished
            )
        }
    }
}

class MessageSender(uuid: String, started: Long, messageSize: Int, device: BluetoothDevice, results: ConcurrentHashMap<String, MessageTimes>, counter: CountDownLatch): Runnable{
    val uuid = uuid
    val started = started
    val messageSize = messageSize
    val device = device
    val results = results
    val counter = counter

    override fun run() {
        val msgpack = MessagePack()
        val random = Random()
        Log.d(MESSAGE_TAG, "attempting to send message [${uuid}] to ${device.getName()} message size is ${messageSize}")
        try {
            val socket = device.createInsecureRfcommSocketToServiceRecord(MESSAGE_UUID)
            socket.connect()
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buffer = ByteArray(messageSize)
            random.nextBytes(buffer)
            val outMsg = MessageRequest(uuid, buffer)
            msgpack.write(output, outMsg)
            val inMsg = msgpack.read(input, javaClass<MessageResponse>())
            val finished = System.currentTimeMillis()
            results.set(uuid, MessageTimes(started, inMsg.received, finished))
        } catch(i: IOException) {
            Log.e(MESSAGE_TAG, "error sending message", i)
        }
        counter.countDown()
    }
}

class MessageListener(adapter: BluetoothAdapter, outgoing: ThreadPoolExecutor): Runnable {
    private val btAdapter = adapter
    private val outgoing = outgoing


    override fun run() {
        Log.d(MESSAGE_TAG, "waiting for messages")
        var serverSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord(MESSAGE_TAG, MESSAGE_UUID)

        while(true) {
            try{
                val socket = serverSocket.accept()
                val device = socket.getRemoteDevice()
                val received = System.currentTimeMillis()
                Log.d(MESSAGE_TAG, "got a message from ${device.getName()}[${device.getAddress()}]")
                outgoing.execute(MessageHandler(socket, received))
            } catch (e: IOException){
                Log.e(MESSAGE_TAG, "failed to get message", e)
            }
        }
    }
}

class MessageHandler(socket: BluetoothSocket, received: Long): Runnable {
    val socket = socket
    val received = received

    override fun run() {
        val msgpack = MessagePack()
        val received = System.currentTimeMillis()
        socket.use { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            try {
                val message = msgpack.read(input, javaClass<MessageRequest>())
                val outgoing = MessageResponse(message.uuid, received)
                msgpack.write(output, outgoing)
            }catch(i: IOException) {
                Log.e(MESSAGE_TAG, "error answering to message", i)
                return
            }
        }
    }
}
