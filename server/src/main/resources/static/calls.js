"use strict";

// Voice and video calls between a doctor and a linked patient (WebRTC). The server only introduces the two sides over
// a WebSocket and relays the media through its TURN relay when they cannot reach each other directly. Used by the
// panels (index.html) and by the app's call screen (call.html). Plain DOM, no innerHTML.

const Calls = (() => {
    const TEXT = {
        calling: (name, video) => (video ? "تماس تصویری با " : "تماس صوتی با ") + name + "…",
        ringing: (name, video) => name + (video ? " تماس تصویری می‌گیرد" : " تماس صوتی می‌گیرد"),
        connecting: "در حال برقراری ارتباط…",
        accept: "پاسخ", decline: "رد", hangup: "قطع تماس",
        mute: "بی‌صدا", unmute: "صدادار", cameraOff: "خاموش کردن دوربین", cameraOn: "روشن کردن دوربین",
        ended: {
            hangup: "تماس تمام شد.", declined: "تماس رد شد.", no_answer: "پاسخی نداد.", busy: "مخاطب در تماس دیگری است.",
            not_found: "تماس ممکن نیست.", answered_elsewhere: "در دستگاه دیگری پاسخ داده شد.", media: "میکروفون یا دوربین باز نشد. اجازه‌ی دسترسی را بدهید.",
            network: "ارتباط برقرار نشد. اینترنت را بررسی کنید.", offline: "ارتباط با سرور برقرار نیست.",
        },
    };

    let ws = null, token = null, me = null, iceServers = [], wanted = false, retry = 1000;
    let call = null; // { id, peerName, video, outgoing, pc, local, pendingCandidates, ui, state, startedAt }
    const ringing = new Map(); // callId -> ring message, while the overlay offers it
    const listeners = { ready: [], ring: [], ended: [] };

    function el(tag, attrs, ...children) {
        const e = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs || {})) {
            if (v == null || v === false) continue;
            if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
            else if (k === "class") e.className = v;
            else e.setAttribute(k, v === true ? "" : v);
        }
        for (const c of children.flat()) if (c != null && c !== false) e.append(c instanceof Node ? c : String(c));
        return e;
    }

    // --- Connection ---

    function connect(newToken) {
        token = newToken;
        wanted = true;
        if (ws && ws.readyState <= 1) return;
        const url = (location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/api/calls/ws";
        ws = new WebSocket(url);
        ws.onopen = () => ws.send(JSON.stringify({ type: "auth", token }));
        ws.onmessage = (e) => { try { onMessage(JSON.parse(e.data)); } catch (err) { console.error(err); } };
        ws.onclose = () => {
            ws = null;
            if (call) finish(call.state === "active" ? "network" : "offline");
            if (wanted) { setTimeout(() => wanted && connect(token), retry); retry = Math.min(retry * 2, 30000); }
        };
    }

    function disconnect() {
        wanted = false;
        if (call) hangup();
        if (ws) ws.close();
        ws = null;
    }

    function send(m) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(m)); }

    function onMessage(m) {
        switch (m.type) {
            case "error": wanted = false; break;
            case "ready":
                retry = 1000; me = m.me; iceServers = m.iceServers || [];
                listeners.ready.forEach((f) => f(me));
                break;
            case "ring":
                ringing.set(m.callId, m);
                if (!call) showRing(m);
                listeners.ring.forEach((f) => f(m));
                break;
            case "calling":
                if (call && call.outgoing && !call.id) call.id = m.callId;
                else send({ type: "hangup", callId: m.callId }); // Hung up before the server answered.
                break;
            case "accepted":
                if (call && call.id === m.callId) startMedia(true);
                break;
            case "signal":
                if (call && call.id === m.callId) onSignal(m.data);
                break;
            case "ended":
                ringing.delete(m.callId);
                if (call && (call.id === m.callId || !m.callId)) finish(m.reason);
                else if (!call) hideRingFor(m.callId);
                listeners.ended.forEach((f) => f(m));
                break;
        }
    }

    // --- Calling ---

    async function start(toId, peerName, video) {
        if (call || !ws || ws.readyState !== 1) { if (!call) notice(TEXT.ended.offline); return; }
        call = { id: null, peerName, video, outgoing: true, pendingCandidates: [], state: "calling" };
        showCall(TEXT.calling(peerName, video));
        try {
            call.local = await navigator.mediaDevices.getUserMedia(constraints(video));
        } catch (e) { return finish("media"); }
        if (!call) return stopTracks();
        attachLocal();
        send({ type: "call", to: toId, video });
    }

    async function answer(callId) {
        const ring = ringing.get(callId);
        if (!ring || call) return;
        ringing.delete(callId);
        hideRing();
        call = { id: callId, peerName: ring.from.name, video: ring.video, outgoing: false, pendingCandidates: [], state: "connecting" };
        showCall(TEXT.connecting);
        try {
            call.local = await navigator.mediaDevices.getUserMedia(constraints(ring.video));
        } catch (e) {
            send({ type: "decline", callId });
            return finish("media");
        }
        attachLocal();
        startMedia(false);
        send({ type: "accept", callId });
    }

    function decline(callId) {
        ringing.delete(callId);
        send({ type: "decline", callId });
        hideRing();
    }

    function hangup() {
        if (!call) return;
        if (call.id) send({ type: "hangup", callId: call.id });
        finish("hangup", true);
    }

    function constraints(video) {
        return { audio: { echoCancellation: true, noiseSuppression: true }, video: video ? { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: "user" } : false };
    }

    /** Builds the peer connection; the caller makes the offer once the other side has answered. */
    async function startMedia(makeOffer) {
        const c = call;
        if (!c || c.pc) return;
        c.state = "connecting";
        setStatus(TEXT.connecting);
        const pc = new RTCPeerConnection({ iceServers });
        c.pc = pc;
        c.local.getTracks().forEach((t) => pc.addTrack(t, c.local));
        pc.onicecandidate = (e) => { if (e.candidate) send({ type: "signal", callId: c.id, data: { candidate: e.candidate.toJSON() } }); };
        pc.ontrack = (e) => {
            const remote = c.ui && c.ui.remote;
            if (remote && remote.srcObject !== e.streams[0]) { remote.srcObject = e.streams[0]; remote.play().catch(() => {}); }
        };
        pc.onconnectionstatechange = () => {
            if (call !== c) return;
            if (pc.connectionState === "connected" && c.state !== "active") {
                c.state = "active";
                c.startedAt = Date.now();
                setStatus("");
            } else if (pc.connectionState === "failed") {
                send({ type: "hangup", callId: c.id });
                finish("network", true);
            }
        };
        if (makeOffer) {
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            send({ type: "signal", callId: c.id, data: { sdp: pc.localDescription.toJSON() } });
        }
    }

    async function onSignal(data) {
        const c = call;
        if (!c || !c.pc) return;
        const pc = c.pc;
        try {
            if (data.sdp) {
                await pc.setRemoteDescription(data.sdp);
                if (data.sdp.type === "offer") {
                    await pc.setLocalDescription(await pc.createAnswer());
                    send({ type: "signal", callId: c.id, data: { sdp: pc.localDescription.toJSON() } });
                }
                for (const cand of c.pendingCandidates.splice(0)) await pc.addIceCandidate(cand);
            } else if (data.candidate) {
                if (pc.remoteDescription) await pc.addIceCandidate(data.candidate);
                else c.pendingCandidates.push(data.candidate);
            }
        } catch (e) { console.warn("signal", e); }
    }

    function finish(reason, quiet) {
        const c = call;
        if (!c) return;
        call = null;
        if (c.pc) c.pc.close();
        stopTracks(c.local);
        stopTimer(c);
        if (c.ui) c.ui.root.remove();
        if (!quiet || reason !== "hangup") notice(TEXT.ended[reason] || TEXT.ended.hangup);
        listeners.ended.forEach((f) => f({ reason, local: true }));
        // Another call may still be ringing.
        const next = ringing.values().next().value;
        if (next) showRing(next);
    }

    function stopTracks(stream) { if (stream) stream.getTracks().forEach((t) => t.stop()); }

    // --- On screen ---

    let ringUi = null, ringTone = null;

    function showRing(m) {
        hideRing();
        ringUi = el("div", { class: "call-overlay ring", role: "alertdialog", "aria-label": TEXT.ringing(m.from.name, m.video) },
            el("p", { class: "call-title" }, TEXT.ringing(m.from.name, m.video)),
            el("div", { class: "call-actions" },
                el("button", { type: "button", class: "accept", onclick: () => answer(m.callId) }, TEXT.accept),
                el("button", { type: "button", class: "danger", onclick: () => decline(m.callId) }, TEXT.decline),
            ),
        );
        ringUi.dataset.callId = m.callId;
        document.body.append(ringUi);
        ringTone = tone();
    }

    function hideRing() {
        if (ringUi) ringUi.remove();
        ringUi = null;
        if (ringTone) ringTone();
        ringTone = null;
    }

    function hideRingFor(callId) {
        if (ringUi && ringUi.dataset.callId === callId) {
            hideRing();
            const next = ringing.values().next().value;
            if (next) showRing(next);
        }
    }

    /** A soft repeating ring with WebAudio (no sound file); returns a function that stops it. */
    function tone() {
        let ctx;
        try { ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return () => {}; }
        const beep = () => {
            [0, 0.4].forEach((at) => {
                const o = ctx.createOscillator(), g = ctx.createGain();
                o.frequency.value = 440;
                g.gain.setValueAtTime(0.0001, ctx.currentTime + at);
                g.gain.exponentialRampToValueAtTime(0.2, ctx.currentTime + at + 0.02);
                g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + at + 0.3);
                o.connect(g).connect(ctx.destination);
                o.start(ctx.currentTime + at);
                o.stop(ctx.currentTime + at + 0.32);
            });
        };
        beep();
        const timer = setInterval(beep, 2500);
        return () => { clearInterval(timer); ctx.close().catch(() => {}); };
    }

    function showCall(status) {
        const remote = el("video", { class: "call-remote", autoplay: true, playsinline: true });
        const local = el("video", { class: "call-local", autoplay: true, playsinline: true, muted: true });
        local.muted = true;
        const statusLine = el("p", { class: "call-status" }, status);
        const timer = el("p", { class: "call-timer" });
        const mute = el("button", { type: "button", class: "secondary" }, TEXT.mute);
        mute.addEventListener("click", () => {
            const track = call && call.local && call.local.getAudioTracks()[0];
            if (!track) return;
            track.enabled = !track.enabled;
            mute.textContent = track.enabled ? TEXT.mute : TEXT.unmute;
        });
        const camera = call.video ? el("button", { type: "button", class: "secondary" }, TEXT.cameraOff) : null;
        if (camera) camera.addEventListener("click", () => {
            const track = call && call.local && call.local.getVideoTracks()[0];
            if (!track) return;
            track.enabled = !track.enabled;
            camera.textContent = track.enabled ? TEXT.cameraOff : TEXT.cameraOn;
        });
        const root = el("div", { class: "call-overlay" + (call.video ? " video" : ""), role: "dialog", "aria-label": call.peerName },
            el("p", { class: "call-title" }, call.peerName),
            statusLine, timer,
            el("div", { class: "call-media" }, remote, call.video ? local : null),
            el("div", { class: "call-actions" }, mute, camera, el("button", { type: "button", class: "danger", onclick: hangup }, TEXT.hangup)),
        );
        document.body.append(root);
        call.ui = { root, remote, local, status: statusLine, timer };
        call.timerId = setInterval(() => {
            if (call && call.startedAt) {
                const s = Math.floor((Date.now() - call.startedAt) / 1000);
                timer.textContent = (Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0")).replace(/\d/g, (d) => "۰۱۲۳۴۵۶۷۸۹"[d]);
            }
        }, 1000);
    }

    function stopTimer(c) { if (c.timerId) clearInterval(c.timerId); }

    function attachLocal() { if (call && call.ui && call.video) call.ui.local.srcObject = call.local; }

    function setStatus(text) { if (call && call.ui) call.ui.status.textContent = text; }

    function notice(text) {
        const n = el("div", { class: "call-notice", role: "status" }, text);
        document.body.append(n);
        setTimeout(() => n.remove(), 4000);
    }

    return {
        connect, disconnect, start, answer, decline, hangup,
        get me() { return me; },
        get inCall() { return call != null; },
        on(event, f) { listeners[event].push(f); },
        /** Whether this browser can make calls at all (WebRTC, and a secure page for the microphone). */
        get supported() { return !!(window.RTCPeerConnection && navigator.mediaDevices && window.isSecureContext); },
    };
})();
