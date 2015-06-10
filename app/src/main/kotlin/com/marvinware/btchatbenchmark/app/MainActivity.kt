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

Message class BtMessage(): Parcelable {
    constructor(content: ByteArray) : this() {
        this.content = content
    }

    public var content: ByteArray = ByteArray(0)

    override fun toString(): String {
        return "Message{content=$content}"
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByteArray(content)
    }

    fun readFromParcel(parcel: Parcel) {
        parcel.readByteArray(content)
    }

    override fun describeContents(): Int = 0
}

data class Benchmark(val localName: String, val remoteName: String, val bytes: Int, val time: Long)
val headers = arrayOf("client", "server", "bytes", "time") // .map{it as Object}.toTypedArray()

public class MainActivity : Activity() {

    inner class BenchmarkServer: NanoHTTPD(38080) {

        private val defaultSize = 4096
        private val defaultIterations = 10

        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            val url = session.getUri()
            return when {
                url.contains("/mac") -> handleMac(session)
                url.contains("/run") -> handleRun(session)
                // todo: add post to alter defaultSize
                else -> super.serve(session)
            }

        }
        private fun handleRun(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
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
            val br = BenchmarkRunner(device, iterations)
            br.run()
            val format = CSVFormat.DEFAULT.withRecordSeparator("\n");
            val printer = CSVPrinter(s, format)
            printer.printRecord("from", "to", "bytes", "nanotime")
            br.runs.forEach { record ->
                printer.printRecord(record.remoteName, record.localName, record.bytes, record.time)
            }
            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/csv", s.toString())
        }

        private fun handleMac(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val name = adapter.getName()
            val addr = adapter.getAddress()


            return NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "text/plain", "$name\n$addr")
        }

    }

    val TAG = "BtBenchmark"
    val SERVICE_NAME = "MeshChatService"
    val SERVICE_UUID = UUID.fromString("b61f7b5f-1a88-4252-a0f7-64468f4e26b4")

    private val cores = Runtime.getRuntime().availableProcessors()
    private val queue = concurrent.LinkedBlockingQueue<Runnable>()
    private val outgoing = ThreadPoolExecutor(cores, cores, 1, TimeUnit.SECONDS, queue)

    val btAdapter by Delegates.lazy { BluetoothAdapter.getDefaultAdapter() }

    var listenForIncomingConnectionsThread: ListenForIncomingConnections by Delegates.notNull()

    private var server = BenchmarkServer()

    private val macs = mapOf(
            Pair("nexus","50:46:5D:CC:65:4E"), // nexus 7
            Pair("redmi", "74:51:BA:46:90:A2"), // redmi
            Pair("maccari", "18:22:7E:FC:83:09")  // samsung maccari
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "going to listen for connections")
        listenForIncomingConnectionsThread = ListenForIncomingConnections()
        listenForIncomingConnectionsThread.start()
        Log.d(TAG, "starting http server on port 8080")
        try {
            server.start()
        } catch(ioe: IOException) {
            Log.e(TAG, "could not start http server", ioe)
        }
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
            // TODO: spit out settings
            Toast.makeText(this, InetAddress.getLocalHost().toString(), 10).show();
        }

        return super.onOptionsItemSelected(item)
    }

    inner class BenchmarkRunner(device: BluetoothDevice, iterations:Int): Runnable {
        val localName = btAdapter.getName()
        val iterations = iterations
        val device = device
        public val runs: ArrayList<Benchmark> = ArrayList();

        override fun run() {
            val msgpack = MessagePack()
            val remoteName = device.getName()

            try {
                Log.d(TAG, "attempting connection with ${device.getName()}")
                val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                val input = socket.getInputStream()
                for(i in 1..iterations){
                    val start = System.nanoTime()
                    val msg = msgpack.read(input, javaClass<BtMessage>())
                    val end = System.nanoTime()
                    runs.add(Benchmark(localName, remoteName, msg.content.size(), end-start));
                }
                Log.d(TAG, "${iterations} iterations completed")
            } catch(i: IOException) {
                Log.e(TAG, "exception while sending message", i)
            }
        }
    }

    inner class ListenForIncomingConnections: Thread() {
        override fun run() {
            setName("MeshChatListener")

            Log.d(TAG, "Starting ListenerThread")

            var serverSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)

            while(true) {
                try{
                    val socket = serverSocket.accept()
                    Log.d(TAG, "Launching ProcessMessageThread on $socket: ${socket.isConnected()}")

                    outgoing.execute(ProcessMessage(socket))
                } catch (e: IOException){
                    Log.e(TAG, "serverSocket accept() failed", e)
                    continue
                }
            }
        }
    }

    inner class ProcessMessage(socket: BluetoothSocket?) : Runnable {
        val socket = socket
        val random = Random()

        override fun run() {
            if(socket == null){
                Log.e(TAG, "got a null socket in ProcessMessage thread")
                return
            }

            try {
                val msgpack = MessagePack()
                val outstream = socket.getOutputStream()
                val buffer = ByteArray(1024 * 1024)
                var counter = 0

                Log.d(TAG, "got connection from ${socket.getRemoteDevice()}")
                while(true) {
                    random.nextBytes(buffer)
                    val message = BtMessage(buffer)
                    msgpack.write(outstream, message)
                    Log.d(TAG, "sent a message: $counter")
                    counter++
                }
            } catch(i: IOException) {
                Log.e(TAG, "could not decode message", i)
            }
        }
    }
}
