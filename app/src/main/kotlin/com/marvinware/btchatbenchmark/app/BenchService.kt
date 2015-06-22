package com.marvinware.btchatbenchmark.app

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.IOException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

public class BenchService: Service() {
    companion object {
        val TAG = "BtBenchmark"
    }

    private var server = BenchmarkServer()

    private val cores = Runtime.getRuntime().availableProcessors()
    private val queue = LinkedBlockingQueue<Runnable>()
    private val outgoing = ThreadPoolExecutor(cores, cores, 1, TimeUnit.SECONDS, queue)

    val btAdapter by Delegates.lazy { BluetoothAdapter.getDefaultAdapter() }

    var throughputThread: Thread by Delegates.notNull()
    var messageThread: Thread by Delegates.notNull()
    var tokenThread: Thread by Delegates.notNull()

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.d(TAG, "setting up listeners")
        throughputThread = Thread(ThroughputListener(btAdapter, outgoing))
        throughputThread.start()

        messageThread = Thread(MessageListener(btAdapter, outgoing))
        messageThread.start()

        tokenThread = Thread(TokenListener(btAdapter, outgoing))
        tokenThread.start()

        Log.d(TAG, "starting http server on port 8080")
        try {
            server.start()
        } catch(ioe: IOException) {
            Log.e(TAG, "could not start http server", ioe)
        }
    }

    override fun onDestroy() {
        server.stop()
    }

    inner class BenchmarkServer: NanoHTTPD(38080) {
        private val defaultIterations = 1000
        private val defaultMessageSize = 1024
        private val defaultMessages = 1

        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            Log.d(TAG, "got a connection")
            val url = session.getUri()
            return when {
                url.contains("/mac") -> handleMac()
                url.contains("/throughput") -> handleT(session)
                url.contains("/messages") -> handleM(session)
                url.contains("/token") -> handleTok(session)
                url.contains("/tokres") -> getTokResults(session)
            // todo: add post to alter defaultSize
                else -> super.serve(session)
            }

        }

        private fun handleM(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            val params = decodeParameters(session.getQueryParameterString())
            val targets: List<BluetoothDevice> = params.getOrElse("target", {listOf()})
                    .filter { mac -> BluetoothAdapter.checkBluetoothAddress(mac) }
                    .map { mac -> btAdapter.getRemoteDevice(mac) }

            if(targets.size() < 1) {
                return NanoHTTPD.Response(
                        NanoHTTPD.Response.Status.BAD_REQUEST,
                        "text/plain",
                        "must specify at least a target"
                )
            }

            // TODO: this should be a parameter
            val messages = try {
                Integer.parseInt(params.get("messages")?.first())
            } catch(n: NumberFormatException) {
                defaultMessages
            }

            val size: Int = try {
                Integer.parseInt(params.get("size")?.first())
            } catch(n: NumberFormatException) {
                defaultMessageSize
            }

            val s = StringBuilder()
            val mr = MessageRunner(
                    localName = btAdapter.getName(),
                    devices = targets,
                    outgoing = outgoing,
                    messages = messages,
                    messageSize = size
            )
            val results = mr.execute()
            val format = CSVFormat.DEFAULT.withRecordSeparator("\n");
            val printer = CSVPrinter(s, format)
            printer.printRecord("from", "to", "message_size", "started", "received", "finished")
            results.forEach { record ->
                printer.printRecord(
                        record.localName, record.remoteName, record.size,
                        record.started, record.received, record.finished
                )
            }
            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/csv", s.toString())
        }

        private fun handleT(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            val params = session.getParms()
            val target = params.get("target")
            val iterations: Int = try {
                Integer.parseInt(params.get("iterations"))
            } catch(n: NumberFormatException) {
                defaultIterations
            }

            val s = StringBuilder()
            val device = btAdapter.getRemoteDevice(target)
            Log.d(TAG, "going to pull from ${device.getName()} [${device.getAddress()}]")
            val br = ThroughputRunner(btAdapter.getName(), device, iterations)
            val results = br.execute()
            val format = CSVFormat.DEFAULT.withRecordSeparator("\n");
            val printer = CSVPrinter(s, format)
            printer.printRecord("from", "to", "bytes", "nanotime")
            results.forEach { record ->
                printer.printRecord(record.remoteName, record.localName, record.bytes, record.time)
            }
            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/csv", s.toString())
        }

        private fun handleTok(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            // params:
            // devices - payload_length

            val params = decodeParameters(session.getQueryParameterString())
            val devices: List<String> = params.getOrElse("devices", {listOf()})
                    .filter { mac -> BluetoothAdapter.checkBluetoothAddress(mac) }

            if(devices.size() < 2) {
                return NanoHTTPD.Response(
                        NanoHTTPD.Response.Status.BAD_REQUEST,
                        "text/plain",
                        "must specify at least two devices\nDevices received: ${devices.joinToString(", ")}"
                )
            }

            val br = TokenRunner(UUID.randomUUID().toString(), PAYLOAD_LENGTH, devices.toArrayList(), btAdapter)
            val result = br.execute()
            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain", result.toString())
        }

        private fun handleMac(): NanoHTTPD.Response {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val name = adapter.getName()
            val addr = adapter.getAddress()

            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain", "$name\n$addr")
        }

    }

    private fun getTokResults(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uuid = UUID.fromString(session.getParms().get("uuid")).toString()
        val res = ResultsGetter(uuid).execute()
        if (res == null) {
            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/csv", "no results yet")
        }

        val s = StringBuilder()
        val format = CSVFormat.DEFAULT.withRecordSeparator("\n");
        val printer = CSVPrinter(s, format)
        printer.printRecord("sender", "receiver", "payloadSize", "numRounds", "started", "connected", "received", "finished")

        res.forEach { r ->
            printer.printRecord(r.senderName, r.receiverName, r.pingPayloadSize, r.numRounds, r.started, r.connected, r.received, r.finished)
        }

        return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/csv", s.toString())
    }
}
