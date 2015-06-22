package com.marvinware.btchatbenchmark.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import org.msgpack.MessagePack
import org.msgpack.annotation.Index
import org.msgpack.annotation.Message
import org.msgpack.template.Template
import org.msgpack.template.Templates.tList
import org.msgpack.template.Templates.TString
import java.io.IOException
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

Message data class BenchmarkRecord(
        Index(0) var senderName: String = "",
        Index(1) var receiverName: String = "",
        Index(2) var pingPayloadSize: Int = PAYLOAD_LENGTH,
        Index(3) var numRounds: Int = NUM_ROUNDS,
        Index(4) var started: Long = 0,
        Index(5) var connected: Long = 0,
        Index(6) var received: Long = 0,
        Index(7) var finished: Long = 0
)

val results = ConcurrentHashMap<String, Array<BenchmarkRecord>>()

Message data class Token (
        Index(0) var uuid: String = "",
        Index(1) var master: String = "",  // MAC address of master
        Index(2) var devices: MutableList<String> = arrayListOf(),  // Array of MAC addresses
        Index(3) var times: MutableList<BenchmarkRecord> = arrayListOf(),  // Token holds benchmark records
        Index(4) var remainingRounds: Int = NUM_ROUNDS
)

Message data class Ping (
        var payload: String = Array(PAYLOAD_LENGTH, {"x"}).joinToString("")
)

Message data class Pong (
        Index(0) var ack: Boolean = true,
        Index(1) var received: Long = 0
)

fun sendPingToNextDevice(btAdapter: BluetoothAdapter, token: Token, msgpack: MessagePack) {
    // send to next device
    try {
        val nextDeviceIdx = (token.devices.indexOf(btAdapter.getAddress()) +1) % token.devices.size()
        val nextDevice = btAdapter.getRemoteDevice(token.devices[nextDeviceIdx])

        Log.d(TOKEN_TAG, "Sending ping to ${nextDevice.getAddress()}")

        var benchmarkRecord = BenchmarkRecord(btAdapter.getName(), nextDevice.getName(), PAYLOAD_LENGTH, NUM_ROUNDS, 0, 0, 0, 0)
        benchmarkRecord.started = System.currentTimeMillis()

        val socket = nextDevice.createInsecureRfcommSocketToServiceRecord(TOKEN_UUID)
        socket.connect()
        benchmarkRecord.connected = System.currentTimeMillis()

        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val outPing = Ping()
        msgpack.write(output, outPing)
        // todo: update received here?
        Log.d(TOKEN_TAG, "Ping sent")

        val pong = msgpack.read(input, javaClass<Pong>())
        benchmarkRecord.finished = System.currentTimeMillis()
        token.times.add(benchmarkRecord)
        Log.d(TOKEN_TAG, "Received pong, sending token")

        // send the token to the next device
        msgpack.write(output, token)

    } catch(i: IOException) {
        Log.e(TOKEN_TAG, "error answering to message", i)
        return
    }
}

class TokenRunner(
        uuid: String,
        payloadSize: Int,
        devices_addr: ArrayList<String>,
        btAdapter: BluetoothAdapter
) {
    // Send the first ping with the token
    val uuid = uuid
    val payloadSize = payloadSize
    val devices_addr = devices_addr
    val btAdapter = btAdapter

    fun createToken(): Token {
        val master = btAdapter.getAddress()
        if(!devices_addr.contains(master)) {
            devices_addr.add(0, master)
        }

        var token = Token(uuid, master, devices_addr, ArrayList<BenchmarkRecord>())

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
            token = msgpack.read(input, javaClass<Token>())
            Log.d(TOKEN_TAG, "Got the token! ${token.toString()}")

            input.close()
            output.close()
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
