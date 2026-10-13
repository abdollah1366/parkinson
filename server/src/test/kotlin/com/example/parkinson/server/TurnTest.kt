package com.example.parkinson.server

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The built-in TURN relay, driven like a browser would over UDP and TCP. */
class TurnTest {
    private val localhost: InetAddress = InetAddress.getByName("127.0.0.1")
    private lateinit var turn: TurnServer
    private val server get() = InetSocketAddress(localhost, turn.boundPort)

    @BeforeTest
    fun start() {
        turn = TurnServer(localhost, 0, 50100..50140, "test-secret").apply { start() }
    }

    @AfterTest
    fun stop() = turn.stop()

    private fun tx() = Random.nextBytes(12)

    /** A UDP client that keeps its login after the first 401, like a browser. */
    private inner class UdpTurnClient(private val login: TurnCredentials = turn.credentials("test")) {
        val socket = DatagramSocket().apply { soTimeout = 3000 }
        private var realm = ""
        private var nonce = ""
        private val key get() = MessageDigest.getInstance("MD5").digest("${login.username}:$realm:${login.password}".toByteArray())

        fun send(bytes: ByteArray) = socket.send(DatagramPacket(bytes, bytes.size, server))

        fun receive(): ByteArray {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return buffer.copyOf(packet.length)
        }

        fun receiveStun(): Stun.Message = receive().let { Stun.parse(it, it.size)!! }

        fun request(method: Int, fill: Stun.Builder.() -> Unit = {}): Stun.Message {
            val signed = { Stun.Builder(method, Stun.REQUEST, tx()).apply(fill)
                .add(Stun.USERNAME, login.username.toByteArray()).add(Stun.REALM, realm.toByteArray())
                .add(Stun.NONCE, nonce.toByteArray()).build(key) }
            if (realm.isEmpty()) {
                send(Stun.Builder(method, Stun.REQUEST, tx()).apply(fill).build())
                val challenge = receiveStun()
                assertEquals(Stun.ERROR, challenge.cls)
                realm = challenge.text(Stun.REALM)!!
                nonce = challenge.text(Stun.NONCE)!!
            }
            send(signed())
            return receiveStun()
        }

        fun allocate(): InetSocketAddress {
            val r = request(Stun.ALLOCATE) { add(Stun.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)) }
            assertEquals(Stun.SUCCESS, r.cls, errorOf(r))
            assertTrue(Stun.verify(r, key), "answers are signed")
            return Stun.readXorAddress(r.attr(Stun.XOR_RELAYED_ADDRESS)!!)!!
        }

        fun permit(peer: InetSocketAddress) =
            assertEquals(Stun.SUCCESS, request(Stun.CREATE_PERMISSION) { address(Stun.XOR_PEER_ADDRESS, peer) }.cls)
    }

    private fun errorOf(m: Stun.Message) = m.attr(Stun.ERROR_CODE)?.let { "${it[2] * 100 + it[3]} ${String(it, 4, it.size - 4)}" }

    @Test
    fun bindingAnswersWithTheClientsAddress() {
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            val request = Stun.Builder(Stun.BINDING, Stun.REQUEST, tx()).build()
            s.send(DatagramPacket(request, request.size, server))
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            s.receive(packet)
            val answer = Stun.parse(buffer, packet.length)!!
            assertEquals(Stun.SUCCESS, answer.cls)
            assertEquals(s.localPort, Stun.readXorAddress(answer.attr(Stun.XOR_MAPPED_ADDRESS)!!)!!.port)
        }
    }

    @Test
    fun relaysBothWaysOnlyForPermittedPeers() {
        val client = UdpTurnClient()
        val relay = client.allocate()
        val peer = DatagramSocket().apply { soTimeout = 3000 }
        val peerAddress = InetSocketAddress(localhost, peer.localPort)

        // No permission yet: the peer's packet is dropped.
        peer.send(DatagramPacket(byteArrayOf(1), 1, relay))
        client.permit(peerAddress)

        client.send(Stun.Builder(Stun.SEND, Stun.INDICATION, tx())
            .address(Stun.XOR_PEER_ADDRESS, peerAddress).add(Stun.DATA_ATTR, "hello".toByteArray()).build())
        val buffer = ByteArray(100)
        val packet = DatagramPacket(buffer, buffer.size)
        peer.receive(packet)
        assertEquals("hello", String(buffer, 0, packet.length))
        assertEquals(relay.port, packet.port)

        peer.send(DatagramPacket("back".toByteArray(), 4, relay))
        val data = client.receiveStun()
        assertEquals(Stun.DATA, data.method)
        assertContentEquals("back".toByteArray(), data.attr(Stun.DATA_ATTR))

        // With a channel, data travels in the short ChannelData form.
        val bound = client.request(Stun.CHANNEL_BIND) {
            add(Stun.CHANNEL_NUMBER, byteArrayOf(0x40, 0x01, 0, 0)); address(Stun.XOR_PEER_ADDRESS, peerAddress)
        }
        assertEquals(Stun.SUCCESS, bound.cls)
        client.send(byteArrayOf(0x40, 0x01, 0, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()))
        peer.receive(packet)
        assertEquals("abc", String(buffer, 0, packet.length))
        peer.send(DatagramPacket("xyz".toByteArray(), 3, relay))
        assertContentEquals(byteArrayOf(0x40, 0x01, 0, 3) + "xyz".toByteArray(), client.receive())
        peer.close()
    }

    @Test
    fun twoClientsOfThisRelayReachEachOther() {
        val a = UdpTurnClient()
        val b = UdpTurnClient()
        val relayA = a.allocate()
        val relayB = b.allocate()
        a.permit(relayB)
        b.permit(relayA)
        a.send(Stun.Builder(Stun.SEND, Stun.INDICATION, tx())
            .address(Stun.XOR_PEER_ADDRESS, relayB).add(Stun.DATA_ATTR, "hi".toByteArray()).build())
        val got = b.receiveStun()
        assertContentEquals("hi".toByteArray(), got.attr(Stun.DATA_ATTR))
        assertEquals(relayA, Stun.readXorAddress(got.attr(Stun.XOR_PEER_ADDRESS)!!))
    }

    @Test
    fun refusesWrongOrExpiredLogins() {
        val wrong = UdpTurnClient(TurnCredentials(turn.credentials("x").username, "not-the-password"))
        val r = wrong.request(Stun.ALLOCATE) { add(Stun.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)) }
        assertEquals("401 Unauthorized", errorOf(r))
        val expired = UdpTurnClient(turn.credentials("x", nowEpochSeconds = 1000, ttlSeconds = 60))
        assertEquals("401 Unauthorized", errorOf(expired.request(Stun.ALLOCATE) { add(Stun.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)) }))
        // Without a login there is no relay, but also no crash.
        assertNull(Stun.parse(ByteArray(10), 10))
    }

    @Test
    fun worksOverTcpToo() {
        Socket(localhost, turn.boundPort).use { socket ->
            socket.soTimeout = 3000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            fun read(): Stun.Message {
                val head = input.readNBytes(20)
                val body = input.readNBytes(Stun.u16(head, 2))
                val all = head + body
                return Stun.parse(all, all.size)!!
            }
            out.write(Stun.Builder(Stun.ALLOCATE, Stun.REQUEST, tx()).add(Stun.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)).build())
            val challenge = read()
            val login = turn.credentials("tcp")
            val key = MessageDigest.getInstance("MD5").digest("${login.username}:${challenge.text(Stun.REALM)}:${login.password}".toByteArray())
            out.write(Stun.Builder(Stun.ALLOCATE, Stun.REQUEST, tx()).add(Stun.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0))
                .add(Stun.USERNAME, login.username.toByteArray()).add(Stun.REALM, challenge.attr(Stun.REALM)!!)
                .add(Stun.NONCE, challenge.attr(Stun.NONCE)!!).build(key))
            val allocated = read()
            assertEquals(Stun.SUCCESS, allocated.cls)
            assertNotNull(allocated.attr(Stun.XOR_RELAYED_ADDRESS))
        }
    }
}
