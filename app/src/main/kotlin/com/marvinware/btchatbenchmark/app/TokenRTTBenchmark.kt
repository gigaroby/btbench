package com.marvinware.btchatbenchmark.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import org.msgpack.MessagePack
import org.msgpack.annotation.Index
import org.msgpack.annotation.Message
import org.msgpack.packer.Packer
import org.msgpack.template.Template
import org.msgpack.template.Templates.tList
import org.msgpack.template.Templates.TString
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import kotlin.properties.Delegates

// 1    -> PING ->      2
// 1    <- PONG <-      2
// 1    -> TOKEN ->     2

val TOKEN_TAG = "TokenRTTBenchmark"
val TOKEN_UUID = UUID.fromString("37f889dc-a695-45db-9f2c-3cf6cdcbbfc6")
val PAYLOAD_LENGTH = 128
val NUM_ROUNDS = 5

class BenchmarkRecord(
        var senderName: String = "",
        var receiverName: String = "",
        var pingPayloadSize: Int = PAYLOAD_LENGTH,
        var numRounds: Int = NUM_ROUNDS,
        var started: Long = 0,
        var connected: Long = 0,
        var received: Long = 0,
        var finished: Long = 0,
        var sleep: Int = 0
): Serializable {}

val results = ConcurrentHashMap<String, Array<BenchmarkRecord>>()

class Token(
        var uuid: String = "",
        var master: String = "",
        var devices: MutableList<String> = arrayListOf(),
        var times: MutableList<BenchmarkRecord> = arrayListOf(),
        var payloadLength: Int = PAYLOAD_LENGTH,
        var remainingRounds: Int = NUM_ROUNDS
): Serializable {}


Message data class Ping (
        var payload: String = Array(PAYLOAD_LENGTH, {"x"}).joinToString("")
)

Message data class Pong (
        Index(0) var ack: Boolean = true,
        Index(1) var received: Long = 0
)

fun sendPingToNextDevice(btAdapter: BluetoothAdapter, token: Token, msgpack: MessagePack) {
    val random = Random()

    // send to next device
    try {
        val sleep = (random.nextInt(2000).toLong())
        Thread.sleep(sleep)

        val devices = token.devices
        val nextDeviceIdx = (devices.indexOf(btAdapter.getAddress()) +1) % token.devices.size()
        val nextDevice = btAdapter.getRemoteDevice(devices[nextDeviceIdx])

        Log.d(TOKEN_TAG, "Sending ping to ${nextDevice.getAddress()}")

        // todo: get name instead of address?
        var benchmarkRecord = BenchmarkRecord(btAdapter.getAddress(), nextDevice.getAddress(), token.payloadLength, NUM_ROUNDS, 0, 0, 0, 0, sleep.toInt())
        benchmarkRecord.started = System.currentTimeMillis()

        var socket = nextDevice.createInsecureRfcommSocketToServiceRecord(TOKEN_UUID)
        if(!socket.isConnected()) {
            socket.connect()
        }
        benchmarkRecord.connected = System.currentTimeMillis()

        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val outPing = Ping()
        val payload = ByteArray(token.payloadLength)
        random.nextBytes(payload)
        outPing.payload = String(payload)

        msgpack.write(output, outPing)
        benchmarkRecord.received = System.currentTimeMillis()
        Log.d(TOKEN_TAG, "Ping sent")

        val pong = msgpack.read(input, javaClass<Pong>())
        benchmarkRecord.finished = System.currentTimeMillis()
        val times = token.times
        times.add(benchmarkRecord)
        token.times = times
        Log.d(TOKEN_TAG, "Received pong, sending token")

        // send the token to the next device
        val os = ObjectOutputStream(output)
        os.writeObject(token)

        socket.close()

    } catch(i: IOException) {
        Log.e(TOKEN_TAG, "error answering to message", i)
        return
    }
}

class TokenRunner(
        val uuid: String,
        val payloadSize: Int,
        val numRounds: Int,
        val devices_addr: ArrayList<String>,
        val btAdapter: BluetoothAdapter
) {
    fun createToken(): Token {
        val master = btAdapter.getAddress()
        if(!devices_addr.contains(master)) {
            devices_addr.add(0, master)
        }

        var token = Token(uuid, master, devices_addr, ArrayList<BenchmarkRecord>(0), payloadSize, numRounds)

        return token
    }

    fun execute(): String {
        val msgpack = MessagePack()
        Log.d(TOKEN_TAG, "attempting to send the FIRST ping with payload=${payloadSize}")
        try {
            Log.d(TOKEN_TAG, "creating token")
            val token = createToken()
            sendPingToNextDevice(btAdapter, token, msgpack)
        } catch(i: IOException) {
            Log.e(TOKEN_TAG, "error sending message", i)
        }
        return uuid
    }
}

class ResultsGetter(uuid: String) {
    val uuid=uuid

    fun execute(): Array<BenchmarkRecord>? {
        val res: Array<BenchmarkRecord>? = results.getOrElse(uuid, {null})
        return res
    }
}

class TokenListener(adapter: BluetoothAdapter, outgoing: ThreadPoolExecutor): Runnable {
    private val btAdapter = adapter
    private val outgoing = outgoing


    override fun run() {
        Log.d(TOKEN_TAG, "waiting for messages")
        var serverSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord(TOKEN_TAG, TOKEN_UUID)

        while(true) {
            try{
                val socket = serverSocket.accept()
                val device = socket.getRemoteDevice()
                val received = System.currentTimeMillis()
                Log.d(TOKEN_TAG, "got a message from ${device.getName()}[${device.getAddress()}]")
                outgoing.execute(TokenHandler(socket, received, btAdapter))
            } catch (e: IOException){
                Log.e(TOKEN_TAG, "failed to get message", e)
            }
        }
    }
}

class TokenHandler(socket: BluetoothSocket, received: Long, btAdapter: BluetoothAdapter): Runnable {
    val socket = socket
    val received = received
    val btAdapter = btAdapter

    fun doMasterStuff(token: Token): Boolean {
        token.remainingRounds -= 1
        Log.d(TOKEN_TAG, "${token.remainingRounds} rounds remaining!")
        if (token.remainingRounds <= 0) {
            results.put(token.uuid, token.times.toTypedArray())
            return true
        }
        return false
    }

    override fun run() {
        val myMacAddress = btAdapter.getAddress()
        val msgpack = MessagePack()
        var token: Token
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val ping = msgpack.read(input, javaClass<Ping>())
            Log.d(TOKEN_TAG, "got ping ${ping.toString()}")
            val outgoing = Pong(true, received)
            Log.d(TOKEN_TAG, "writing pong ${outgoing.toString()}")
            msgpack.write(output, outgoing)

            val ois = ObjectInputStream(input)
            token = ois.readObject() as Token
            Log.d(TOKEN_TAG, "Got the token! ${token.toString()}")

            socket.close()
        }catch(i: IOException) {
            Log.e(TOKEN_TAG, "error answering to message", i)
            return
        }

        if(myMacAddress == token.master) {
            val stop = doMasterStuff(token)
            if (stop) {
                return
            }
        }

        sendPingToNextDevice(btAdapter, token, msgpack)
    }
}
