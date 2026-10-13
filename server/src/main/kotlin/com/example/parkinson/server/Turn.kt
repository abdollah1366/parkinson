package com.example.parkinson.server

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Stage 4: a small TURN relay (RFC 8656, the parts browsers and Android use) built into the server, so voice and video
// calls work when the doctor and the patient are both behind NAT (mobile networks), with nothing else to install.
// Only people the server gave a time-limited password (see [TurnServer.credentials]) can relay; relays go over UDP.

/** STUN message format (RFC 8489), only what TURN needs. */
object Stun {
    const val MAGIC = 0x2112A442

    const val BINDING = 0x001
    const val ALLOCATE = 0x003
    const val REFRESH = 0x004
    const val SEND = 0x006
    const val DATA = 0x007
    const val CREATE_PERMISSION = 0x008
    const val CHANNEL_BIND = 0x009

    const val REQUEST = 0x000
    const val INDICATION = 0x010
    const val SUCCESS = 0x100
    const val ERROR = 0x110

    const val USERNAME = 0x0006
    const val MESSAGE_INTEGRITY = 0x0008
    const val ERROR_CODE = 0x0009
    const val CHANNEL_NUMBER = 0x000C
    const val LIFETIME = 0x000D
    const val XOR_PEER_ADDRESS = 0x0012
    const val DATA_ATTR = 0x0013
    const val REALM = 0x0014
    const val NONCE = 0x0015
    const val XOR_RELAYED_ADDRESS = 0x0016
    const val REQUESTED_TRANSPORT = 0x0019
    const val XOR_MAPPED_ADDRESS = 0x0020
    const val SOFTWARE = 0x8022
    const val FINGERPRINT = 0x8028

    class Message(
        val method: Int,
        val cls: Int,
        val txId: ByteArray,
        val attrs: List<Pair<Int, ByteArray>>,
        /** The whole message, and where its MESSAGE-INTEGRITY attribute starts (-1 when it has none). */
        val raw: ByteArray,
        val integrityAt: Int,
    ) {
        fun attr(type: Int): ByteArray? = attrs.firstOrNull { it.first == type }?.second
        fun all(type: Int): List<ByteArray> = attrs.filter { it.first == type }.map { it.second }
        fun text(type: Int): String? = attr(type)?.toString(Charsets.UTF_8)
    }

    fun isStun(b: ByteArray, len: Int) = len >= 20 && (b[0].toInt() and 0xC0) == 0 && int32(b, 4) == MAGIC

    fun parse(b: ByteArray, len: Int): Message? {
        if (!isStun(b, len)) return null
        val type = u16(b, 0)
        val length = u16(b, 2)
        if (length % 4 != 0 || 20 + length != len) return null
        val method = (type and 0x000F) or ((type and 0x00E0) shr 1) or ((type and 0x3E00) shr 2)
        val attrs = ArrayList<Pair<Int, ByteArray>>()
        var integrityAt = -1
        var pos = 20
        while (pos + 4 <= len) {
            val t = u16(b, pos)
            val l = u16(b, pos + 2)
            if (pos + 4 + l > len) return null
            if (t == MESSAGE_INTEGRITY) integrityAt = pos
            // Attributes after MESSAGE-INTEGRITY are not covered by it; only FINGERPRINT may follow.
            if (integrityAt < 0 || t == MESSAGE_INTEGRITY || t == FINGERPRINT) attrs += t to b.copyOfRange(pos + 4, pos + 4 + l)
            pos += 4 + pad4(l)
        }
        return Message(method, type and 0x0110, b.copyOfRange(8, 20), attrs, b.copyOf(len), integrityAt)
    }

    /** Whether the message's MESSAGE-INTEGRITY matches [key]. */
    fun verify(m: Message, key: ByteArray): Boolean {
        val at = m.integrityAt
        val given = m.attr(MESSAGE_INTEGRITY) ?: return false
        if (at < 0 || given.size != 20) return false
        val covered = m.raw.copyOf(at)
        put16(covered, 2, at + 24 - 20)
        return MessageDigest.isEqual(hmacSha1(key, covered), given)
    }

    class Builder(private val method: Int, private val cls: Int, private val txId: ByteArray) {
        private val body = ByteArrayOutputStream()

        fun add(type: Int, value: ByteArray) = apply {
            body.write(type shr 8); body.write(type)
            body.write(value.size shr 8); body.write(value.size)
            body.write(value)
            repeat(pad4(value.size) - value.size) { body.write(0) }
        }

        fun address(type: Int, address: InetSocketAddress) = add(type, xorAddress(address))

        fun error(code: Int, reason: String) =
            add(ERROR_CODE, byteArrayOf(0, 0, (code / 100).toByte(), (code % 100).toByte()) + reason.toByteArray())

        /** The message, signed with [key] when given, and ending with a FINGERPRINT. */
        fun build(key: ByteArray? = null): ByteArray {
            var message = header(body.size()) + body.toByteArray()
            if (key != null) {
                put16(message, 2, message.size - 20 + 24)
                message += attrBytes(MESSAGE_INTEGRITY, hmacSha1(key, message))
            }
            put16(message, 2, message.size - 20 + 8)
            val crc = CRC32().apply { update(message) }.value.toInt() xor 0x5354554E
            return message + attrBytes(FINGERPRINT, int32Bytes(crc))
        }

        private fun header(length: Int): ByteArray {
            val type = (method and 0x000F) or ((method and 0x0070) shl 1) or ((method and 0x0F80) shl 2) or cls
            val h = ByteArray(20)
            put16(h, 0, type)
            put16(h, 2, length)
            int32Bytes(MAGIC).copyInto(h, 4)
            txId.copyInto(h, 8)
            return h
        }

        private fun attrBytes(type: Int, value: ByteArray): ByteArray {
            val a = ByteArray(4 + value.size)
            put16(a, 0, type)
            put16(a, 2, value.size)
            value.copyInto(a, 4)
            return a
        }
    }

    /** An XOR-*-ADDRESS value; IPv4 only (IPv6 peers are refused). */
    fun xorAddress(address: InetSocketAddress): ByteArray {
        val ip = address.address.address
        val v = ByteArray(8)
        v[1] = 0x01
        put16(v, 2, address.port xor (MAGIC ushr 16))
        val magic = int32Bytes(MAGIC)
        for (i in 0..3) v[4 + i] = (ip[i].toInt() xor magic[i].toInt()).toByte()
        return v
    }

    fun readXorAddress(v: ByteArray): InetSocketAddress? {
        if (v.size != 8 || v[1].toInt() != 0x01) return null
        val port = u16(v, 2) xor (MAGIC ushr 16)
        val magic = int32Bytes(MAGIC)
        val ip = ByteArray(4) { (v[4 + it].toInt() xor magic[it].toInt()).toByte() }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(data)

    fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    fun int32(b: ByteArray, at: Int) = (u16(b, at) shl 16) or u16(b, at + 2)
    fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v shr 8).toByte(); b[at + 1] = v.toByte()
    }
    fun int32Bytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun pad4(n: Int) = (n + 3) and 3.inv()
}

/** Time-limited TURN login for one person, as browsers and Android take it (RTCIceServer username/credential). */
data class TurnCredentials(val username: String, val password: String)

class TurnServer(
    /** The address the phones and browsers reach this computer at. */
    private val publicIp: InetAddress,
    private val port: Int,
    private val relayPorts: IntRange,
    private val secret: String,
    private val realm: String = "parkinson",
    private val maxAllocations: Int = 100,
) {
    private val log = LoggerFactory.getLogger("turn")
    private val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val allocations = ConcurrentHashMap<String, Allocation>()
    private val byRelayPort = ConcurrentHashMap<Int, Allocation>()
    private val threads = Executors.newCachedThreadPool { r -> Thread(r, "turn").apply { isDaemon = true } }
    private val cleaner = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "turn-cleaner").apply { isDaemon = true } }
    private lateinit var udp: DatagramSocket
    private lateinit var tcp: ServerSocket

    /**
     * Where the sockets listen: on the public address itself when this computer has it, so answers leave from the
     * address the browser wrote to (a browser drops them otherwise on a computer with several addresses); on all
     * addresses behind a provider's NAT, where the public address is not on this computer.
     */
    private val bindAddress: InetAddress? =
        publicIp.takeIf { runCatching { java.net.NetworkInterface.getByInetAddress(it) != null }.getOrDefault(false) }

    /** The listening UDP port (useful when [port] is 0 in tests). */
    val boundPort: Int get() = udp.localPort

    fun start() {
        udp = DatagramSocket(InetSocketAddress(bindAddress, port))
        tcp = ServerSocket()
        tcp.reuseAddress = true
        tcp.bind(InetSocketAddress(bindAddress, udp.localPort))
        threads.execute(::udpLoop)
        threads.execute(::tcpLoop)
        cleaner.scheduleAtFixedRate(::expire, 30, 30, TimeUnit.SECONDS)
        log.info("TURN relay on {}:{} (UDP and TCP), relay ports {}", publicIp.hostAddress, udp.localPort, relayPorts)
    }

    fun stop() {
        cleaner.shutdownNow()
        runCatching { udp.close() }
        runCatching { tcp.close() }
        allocations.values.toList().forEach(::remove)
        threads.shutdownNow()
    }

    /** A login valid for [ttlSeconds], for one person ([who] only helps reading logs). */
    fun credentials(who: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000, ttlSeconds: Long = 6 * 3600): TurnCredentials {
        val username = "${nowEpochSeconds + ttlSeconds}:$who"
        return TurnCredentials(username, password(username))
    }

    private fun password(username: String): String =
        Base64.getEncoder().encodeToString(Stun.hmacSha1(secret.toByteArray(), username.toByteArray()))

    /** How a client reached us: one UDP address, or one TCP connection. */
    private abstract inner class Client(val address: InetSocketAddress) {
        abstract val key: String
        abstract fun send(bytes: ByteArray)
        abstract fun sendChannel(channel: Int, data: ByteArray, offset: Int, length: Int)
    }

    private inner class UdpClient(address: InetSocketAddress) : Client(address) {
        override val key = "udp/$address"
        override fun send(bytes: ByteArray) {
            runCatching { udp.send(DatagramPacket(bytes, bytes.size, address)) }
        }
        override fun sendChannel(channel: Int, data: ByteArray, offset: Int, length: Int) {
            val b = ByteArray(4 + length)
            Stun.put16(b, 0, channel); Stun.put16(b, 2, length)
            System.arraycopy(data, offset, b, 4, length)
            send(b)
        }
    }

    private inner class TcpClient(val socket: Socket) : Client(socket.remoteSocketAddress as InetSocketAddress) {
        override val key = "tcp/$address/${System.identityHashCode(socket)}"
        private val out = socket.getOutputStream()
        override fun send(bytes: ByteArray) {
            synchronized(out) { runCatching { out.write(bytes); out.flush() } }
        }
        override fun sendChannel(channel: Int, data: ByteArray, offset: Int, length: Int) {
            // Over TCP, ChannelData is padded to 4 bytes.
            val b = ByteArray(4 + Stun.pad4(length))
            Stun.put16(b, 0, channel); Stun.put16(b, 2, length)
            System.arraycopy(data, offset, b, 4, length)
            send(b)
        }
    }

    private inner class Allocation(val client: Client, val username: String, val key: ByteArray, val relay: DatagramSocket) {
        @Volatile var expiresAt = 0L
        val permissions = ConcurrentHashMap<InetAddress, Long>()
        val channels = ConcurrentHashMap<Int, InetSocketAddress>()
        val peerChannels = ConcurrentHashMap<InetSocketAddress, Int>()
        var allocateTx: ByteArray? = null
        var allocateResponse: ByteArray? = null

        fun permitted(ip: InetAddress) = (permissions[ip] ?: 0) > System.currentTimeMillis()

        /** Passes what a peer sent to the client. */
        fun fromPeer(peer: InetSocketAddress, data: ByteArray, offset: Int, length: Int) {
            if (!permitted(peer.address)) return
            val channel = peerChannels[peer]
            if (channel != null) {
                client.sendChannel(channel, data, offset, length)
            } else {
                val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
                client.send(
                    Stun.Builder(Stun.DATA, Stun.INDICATION, txId)
                        .address(Stun.XOR_PEER_ADDRESS, peer)
                        .add(Stun.DATA_ATTR, data.copyOfRange(offset, offset + length))
                        .build()
                )
            }
        }

        /** Sends to a peer from the relay address. Two clients relaying through this server reach each other directly. */
        fun toPeer(peer: InetSocketAddress, data: ByteArray, offset: Int, length: Int) {
            if (!permitted(peer.address)) return
            val local = if (peer.address == publicIp) byRelayPort[peer.port] else null
            if (local != null) {
                local.fromPeer(InetSocketAddress(publicIp, relay.localPort), data, offset, length)
            } else {
                runCatching { relay.send(DatagramPacket(data, offset, length, peer)) }
            }
        }
    }

    private fun udpLoop() {
        val buffer = ByteArray(65_536)
        while (!udp.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(packet)
            } catch (e: IOException) {
                if (udp.isClosed) return else continue
            }
            val from = packet.socketAddress as InetSocketAddress
            val client = allocations["udp/$from"]?.client ?: UdpClient(from)
            handle(client, buffer.copyOf(packet.length), packet.length)
        }
    }

    private fun tcpLoop() {
        while (!tcp.isClosed) {
            val socket = try {
                tcp.accept()
            } catch (e: IOException) {
                if (tcp.isClosed) return else continue
            }
            threads.execute { tcpConnection(socket) }
        }
    }

    private fun tcpConnection(socket: Socket) {
        val client = TcpClient(socket)
        socket.use {
            socket.tcpNoDelay = true
            socket.soTimeout = 15 * 60_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val head = ByteArray(4)
            try {
                while (true) {
                    input.readFully(head)
                    val length = Stun.u16(head, 2)
                    val frame: ByteArray
                    if ((head[0].toInt() and 0xC0) == 0x40) {
                        frame = ByteArray(4 + length)
                        head.copyInto(frame)
                        input.readFully(frame, 4, length)
                        input.skipNBytes((Stun.pad4(length) - length).toLong())
                    } else {
                        frame = ByteArray(20 + length)
                        head.copyInto(frame)
                        input.readFully(frame, 4, 16 + length)
                    }
                    handle(client, frame, frame.size)
                }
            } catch (e: EOFException) {
            } catch (e: IOException) {
            } finally {
                allocations[client.key]?.let(::remove)
            }
        }
    }

    private fun handle(client: Client, data: ByteArray, length: Int) {
        try {
            if (length >= 4 && (data[0].toInt() and 0xC0) == 0x40) {
                val allocation = allocations[client.key] ?: return
                val channel = Stun.u16(data, 0)
                val size = Stun.u16(data, 2)
                val peer = allocation.channels[channel] ?: return
                if (4 + size <= length) allocation.toPeer(peer, data, 4, size)
                return
            }
            val m = Stun.parse(data, length) ?: return log.debug("Not STUN from {} ({} bytes)", client.address, length)
            log.debug("{} {} from {}", m.method, m.cls, client.key)
            when {
                m.method == Stun.BINDING && m.cls == Stun.REQUEST ->
                    client.send(Stun.Builder(Stun.BINDING, Stun.SUCCESS, m.txId).address(Stun.XOR_MAPPED_ADDRESS, client.address).build())
                m.method == Stun.SEND && m.cls == Stun.INDICATION -> {
                    val allocation = allocations[client.key] ?: return
                    val peer = m.attr(Stun.XOR_PEER_ADDRESS)?.let(Stun::readXorAddress) ?: return
                    val payload = m.attr(Stun.DATA_ATTR) ?: return
                    allocation.toPeer(peer, payload, 0, payload.size)
                }
                m.cls == Stun.REQUEST -> request(client, m)
            }
        } catch (e: Exception) {
            log.debug("Bad TURN message from {}", client.address, e)
        }
    }

    private fun request(client: Client, m: Stun.Message) {
        val existing = allocations[client.key]
        val key = authenticate(client, m, existing) ?: return
        val username = m.text(Stun.USERNAME)!!
        when (m.method) {
            Stun.ALLOCATE -> allocate(client, m, key, username, existing)
            Stun.REFRESH -> {
                val allocation = existing ?: return reply(client, m, key) { error(437, "Allocation Mismatch") }
                val lifetime = lifetimeOf(m)
                if (lifetime == 0) remove(allocation) else allocation.expiresAt = System.currentTimeMillis() + lifetime * 1000L
                reply(client, m, key, Stun.SUCCESS) { add(Stun.LIFETIME, Stun.int32Bytes(lifetime)) }
            }
            Stun.CREATE_PERMISSION -> {
                val allocation = existing ?: return reply(client, m, key) { error(437, "Allocation Mismatch") }
                val peers = m.all(Stun.XOR_PEER_ADDRESS).map { Stun.readXorAddress(it) }
                if (peers.isEmpty() || peers.any { it == null }) return reply(client, m, key) { error(443, "Peer Address Family Mismatch") }
                val until = System.currentTimeMillis() + PERMISSION_MS
                peers.forEach { allocation.permissions[it!!.address] = until }
                reply(client, m, key, Stun.SUCCESS) {}
            }
            Stun.CHANNEL_BIND -> {
                val allocation = existing ?: return reply(client, m, key) { error(437, "Allocation Mismatch") }
                val channel = m.attr(Stun.CHANNEL_NUMBER)?.let { Stun.u16(it, 0) }
                val peer = m.attr(Stun.XOR_PEER_ADDRESS)?.let(Stun::readXorAddress)
                if (channel == null || channel !in 0x4000..0x4FFF || peer == null) {
                    return reply(client, m, key) { error(400, "Bad Request") }
                }
                val boundPeer = allocation.channels[channel]
                val boundChannel = allocation.peerChannels[peer]
                if ((boundPeer != null && boundPeer != peer) || (boundChannel != null && boundChannel != channel)) {
                    return reply(client, m, key) { error(400, "Bad Request") }
                }
                allocation.channels[channel] = peer
                allocation.peerChannels[peer] = channel
                allocation.permissions[peer.address] = System.currentTimeMillis() + PERMISSION_MS
                reply(client, m, key, Stun.SUCCESS) {}
            }
            else -> reply(client, m, key) { error(400, "Bad Request") }
        }
    }

    /** The key of a correctly signed request with a valid login, or null after answering with the error. */
    private fun authenticate(client: Client, m: Stun.Message, existing: Allocation?): ByteArray? {
        val username = m.text(Stun.USERNAME)
        val challenge = { code: Int, reason: String ->
            client.send(
                Stun.Builder(m.method, Stun.ERROR, m.txId).error(code, reason)
                    .add(Stun.REALM, realm.toByteArray()).add(Stun.NONCE, nonce.toByteArray()).build()
            )
        }
        if (username == null || m.integrityAt < 0) return null.also { challenge(401, "Unauthorized") }
        if (m.text(Stun.NONCE) != nonce) return null.also { challenge(438, "Stale Nonce") }
        val expiry = username.substringBefore(':').toLongOrNull()
        if (expiry == null || expiry * 1000 < System.currentTimeMillis()) return null.also { challenge(401, "Unauthorized") }
        val key = MessageDigest.getInstance("MD5").digest("$username:$realm:${password(username)}".toByteArray())
        if (!Stun.verify(m, key)) return null.also { challenge(401, "Unauthorized") }
        if (existing != null && existing.username != username) {
            client.send(Stun.Builder(m.method, Stun.ERROR, m.txId).error(441, "Wrong Credentials").build(key))
            return null
        }
        return key
    }

    private fun allocate(client: Client, m: Stun.Message, key: ByteArray, username: String, existing: Allocation?) {
        if (existing != null) {
            // A retransmission (its answer was lost on UDP) gets the same answer; anything else is a mismatch.
            val again = existing.allocateResponse?.takeIf { existing.allocateTx.contentEquals(m.txId) }
            return if (again != null) client.send(again) else reply(client, m, key) { error(437, "Allocation Mismatch") }
        }
        if (m.attr(Stun.REQUESTED_TRANSPORT)?.firstOrNull()?.toInt() != 17) {
            return reply(client, m, key) { error(442, "Unsupported Transport Protocol") }
        }
        if (allocations.size >= maxAllocations) return reply(client, m, key) { error(486, "Allocation Quota Reached") }
        val relay = bindRelay() ?: return reply(client, m, key) { error(508, "Insufficient Capacity") }
        val allocation = Allocation(client, username, key, relay)
        val lifetime = lifetimeOf(m)
        allocation.expiresAt = System.currentTimeMillis() + lifetime * 1000L
        allocations[client.key] = allocation
        byRelayPort[relay.localPort] = allocation
        threads.execute { relayLoop(allocation) }
        val response = Stun.Builder(Stun.ALLOCATE, Stun.SUCCESS, m.txId)
            .address(Stun.XOR_RELAYED_ADDRESS, InetSocketAddress(publicIp, relay.localPort))
            .add(Stun.LIFETIME, Stun.int32Bytes(lifetime))
            .address(Stun.XOR_MAPPED_ADDRESS, client.address)
            .build(key)
        allocation.allocateTx = m.txId
        allocation.allocateResponse = response
        client.send(response)
    }

    private fun bindRelay(): DatagramSocket? {
        for (p in relayPorts.shuffled()) {
            if (byRelayPort.containsKey(p)) continue
            try {
                return DatagramSocket(InetSocketAddress(bindAddress, p))
            } catch (e: BindException) {
            } catch (e: SocketException) {
            }
        }
        return null
    }

    private fun relayLoop(allocation: Allocation) {
        val buffer = ByteArray(65_536)
        val relay = allocation.relay
        while (!relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
            } catch (e: IOException) {
                if (relay.isClosed) return else continue
            }
            val from = packet.socketAddress as InetSocketAddress
            if (from.address is Inet4Address) allocation.fromPeer(from, buffer, 0, packet.length)
        }
    }

    private fun lifetimeOf(m: Stun.Message): Int {
        val asked = m.attr(Stun.LIFETIME)?.takeIf { it.size == 4 }?.let { Stun.int32(it, 0) } ?: DEFAULT_LIFETIME
        return if (asked == 0) 0 else asked.coerceIn(DEFAULT_LIFETIME, MAX_LIFETIME)
    }

    private fun reply(client: Client, m: Stun.Message, key: ByteArray, cls: Int = Stun.ERROR, fill: Stun.Builder.() -> Unit) {
        client.send(Stun.Builder(m.method, cls, m.txId).apply(fill).build(key))
    }

    private fun remove(allocation: Allocation) {
        allocations.remove(allocation.client.key, allocation)
        byRelayPort.remove(allocation.relay.localPort, allocation)
        allocation.relay.close()
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        allocations.values.filter { it.expiresAt < now }.forEach(::remove)
    }

    companion object {
        const val DEFAULT_LIFETIME = 600
        const val MAX_LIFETIME = 3600
        const val PERMISSION_MS = 300_000L
    }
}
