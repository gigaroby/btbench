package com.marvinware.btchatbenchmark.app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import fi.iki.elonen.NanoHTTPD
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.msgpack.MessagePack
import org.msgpack.annotation.Message
import java.io.IOException
import java.net.InetAddress
import java.util
import java.util.*
import java.util.concurrent
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates


public class MainActivity : Activity() {

    inner class BenchmarkServer: NanoHTTPD(38080) {

        private val defaultIterations = 10
        private val defaultMessageSize = 1024
        private val defaultMessages = 1

        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            Log.d(TAG, "got a connection")
            val url = session.getUri()
            return when {
                url.contains("/mac") -> handleMac()
                url.contains("/throughput") -> handleT(session)
                url.contains("/messages") -> handleM(session)
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

        private fun handleMac(): NanoHTTPD.Response {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val name = adapter.getName()
            val addr = adapter.getAddress()

            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain", "$name\n$addr")
        }

    }

    val TAG = "BtBenchmark"

    private val cores = Runtime.getRuntime().availableProcessors()
    private val queue = concurrent.LinkedBlockingQueue<Runnable>()
    private val outgoing = ThreadPoolExecutor(cores, cores, 1, TimeUnit.SECONDS, queue)

    val btAdapter by Delegates.lazy { BluetoothAdapter.getDefaultAdapter() }

    var throughputThread: Thread by Delegates.notNull()
    var messageThread: Thread by Delegates.notNull()

    private var server = BenchmarkServer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "setting up listeners")
        throughputThread = Thread(ThroughputListener(btAdapter, outgoing))
        throughputThread.start()

        messageThread = Thread(MessageListener(btAdapter, outgoing))
        messageThread.start()

        Log.d(TAG, "starting http server on port 8080")
        try {
            server.start()
        } catch(ioe: IOException) {
            Log.e(TAG, "could not start http server", ioe)
        }
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item!!.getItemId()

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            // TODO: spit out settings without crashing
            Toast.makeText(this, InetAddress.getLocalHost().toString(), 10).show();
        }

        return super.onOptionsItemSelected(item)
    }
}
