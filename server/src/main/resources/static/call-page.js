"use strict";

// The app's call screen (a WebView): call.html#t=<phone token>&call=<doctor id>&name=<doctor>&video=1 places a call,
// call.html#t=<phone token>&accept=<call id> answers one that is ringing. The token stays in the page (a # part is
// never sent to the server) and is removed from the address at once.

(() => {
    const params = new URLSearchParams(location.hash.slice(1));
    history.replaceState(null, "", location.pathname);
    const status = document.getElementById("status");
    let started = false;

    const close = () => {
        if (window.ParkinsonApp) window.ParkinsonApp.close();
        else status.textContent = "تماس تمام شد. می‌توانید این صفحه را ببندید.";
    };
    Calls.on("ended", () => setTimeout(close, 2500));
    Calls.on("ready", () => {
        if (started) return;
        started = true;
        status.textContent = "";
        if (params.get("call")) {
            Calls.start(Number(params.get("call")), params.get("name") || "", params.get("video") === "1");
        } else if (params.get("accept")) {
            // The ring arrives right after "ready"; if it does not, the call already ended.
            setTimeout(() => { if (!Calls.inCall) { status.textContent = "این تماس دیگر در جریان نیست."; setTimeout(close, 2000); } }, 4000);
        }
    });
    Calls.on("ring", (m) => { if (m.callId === params.get("accept")) Calls.answer(m.callId); });
    if (!Calls.supported) status.textContent = "این گوشی تماس اینترنتی را پشتیبانی نمی‌کند.";
    else Calls.connect(params.get("t"));
})();
