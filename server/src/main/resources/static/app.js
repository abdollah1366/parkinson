"use strict";

// Doctor, admin and patient panel. Plain DOM building (no innerHTML with data), so patient text can never run as code.

const TYPES = {
    finger_tapping: "ضربه زدن با انگشتان",
    hand_stability: "نگه‌داشتن دست ثابت",
    pronation_supination: "چرخش دست",
    hand_open_close: "باز و بسته کردن دست",
    resting_hand_tremor: "لرزش دست در استراحت",
    gait: "راه رفتن",
    sit_to_stand: "بلند شدن از صندلی",
    speech: "گفتار",
    dual_task: "حرکتی-شناختی",
};
const QUALITY = { VALID: "معتبر", LOW_QUALITY: "کیفیت پایین", INSUFFICIENT_DATA: "داده‌ی ناکافی", INVALID: "نامعتبر" };
const MEDICATION = { on: "ON (اثر دارو)", off: "OFF (بدون اثر دارو)", unsure: "نامطمئن" };
const HAND = { right: "راست", left: "چپ" };
const GENDER = { male: "مرد", female: "زن" };
const ERRORS = {
    invalid_credentials: "شماره‌ی موبایل یا رمز درست نیست.",
    account_disabled: "این حساب غیرفعال شده است.",
    unauthorized: "دوباره وارد شوید.",
    forbidden: "دسترسی ندارید.",
    mobile_taken: "این شماره‌ی موبایل قبلاً ثبت شده است.",
    weak_password: "رمز باید دست‌کم ۸ نویسه باشد.",
    invalid_mobile: "شماره‌ی موبایل درست نیست.",
    invalid_name: "نام را وارد کنید.",
    wrong_password: "رمز فعلی درست نیست.",
    not_found: "پیدا نشد.",
    server_error: "خطای سرور. دوباره امتحان کنید.",
    network: "ارتباط با سرور برقرار نشد.",
    invalid_note: "متن یادداشت را بنویسید.",
    invalid_message: "متن پیام را بنویسید (حداکثر ۴۰۰۰ نویسه).",
    invalid_voice: "صدای ضبط‌شده خوانده نشد. دوباره ضبط کنید.",
    too_large: "پیام صوتی بیش از حد طولانی است (حداکثر ۳ دقیقه).",
    rate_limited: "پیام‌ها خیلی پشت سر هم فرستاده شد. کمی صبر کنید.",
};
const PATIENT_LOGIN_ERROR = "کد ملی یا رمز درست نیست. رمز پنل را باید اول در برنامه‌ی گوشی، بخش «اتصال به پزشک»، بگذارید.";

const fa = (n) => (n == null ? "—" : Number(n).toLocaleString("fa-IR"));
const dateFmt = new Intl.DateTimeFormat("fa-IR-u-ca-persian", { dateStyle: "medium", timeStyle: "short" });
const shortDateFmt = new Intl.DateTimeFormat("fa-IR-u-ca-persian", { month: "numeric", day: "numeric" });
const when = (ms) => (ms == null ? "—" : dateFmt.format(new Date(ms)));
const typeName = (t) => TYPES[t] || t;

// --- Session ---

const session = {
    get token() { return sessionStorage.getItem("token"); },
    get user() { const u = sessionStorage.getItem("user"); return u ? JSON.parse(u) : null; },
    save(token, user) { sessionStorage.setItem("token", token); sessionStorage.setItem("user", JSON.stringify(user)); },
    clear() { sessionStorage.removeItem("token"); sessionStorage.removeItem("user"); },
};

class ApiError extends Error {
    constructor(code, status) { super(code); this.code = code; this.status = status; }
}

async function api(method, path, body) {
    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (session.token) headers.Authorization = "Bearer " + session.token;
    let response;
    try {
        response = await fetch("/api" + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
        throw new ApiError("network", 0);
    }
    if (response.status === 401 && !path.startsWith("/auth/")) {
        session.clear();
        location.hash = "#login";
        throw new ApiError("unauthorized", 401);
    }
    if (response.status === 204) return null;
    const data = await response.json().catch(() => null);
    if (!response.ok) throw new ApiError((data && data.error) || (response.status === 429 ? "rate_limited" : "server_error"), response.status);
    return data;
}

const message = (e) => e.text || ERRORS[e.code] || ERRORS.server_error;

// --- DOM helpers ---

function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
        if (v == null || v === false) continue;
        if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
        else if (k === "class") el.className = v;
        else el.setAttribute(k, v === true ? "" : v);
    }
    for (const c of children.flat(Infinity)) {
        if (c == null || c === false) continue;
        el.append(c instanceof Node ? c : String(c));
    }
    return el;
}

function svg(tag, attrs, ...children) {
    const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
    for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v);
    for (const c of children.flat(Infinity)) if (c != null) el.append(c instanceof Node ? c : String(c));
    return el;
}

const view = () => document.getElementById("view");
function show(...nodes) { view().replaceChildren(...nodes.filter((n) => n != null)); }
function loading() { show(h("p", { class: "muted" }, "در حال بارگذاری…")); }
function failure(e) { show(h("p", { class: "error" }, message(e))); }

function field(label, input) { return h("label", { class: "field" }, h("span", {}, label), input); }

function form(fields, submitLabel, onSubmit) {
    const error = h("p", { class: "error", hidden: true });
    const button = h("button", { type: "submit" }, submitLabel);
    const el = h("form", {
        onsubmit: async (event) => {
            event.preventDefault();
            error.hidden = true;
            button.disabled = true;
            try { await onSubmit(new FormData(el)); } catch (e) { error.textContent = message(e); error.hidden = false; }
            finally { button.disabled = false; }
        },
    }, fields, error, button);
    return el;
}

// --- Views ---

function loginView(asPatient = location.hash === "#patient-login") {
    const tabs = h("div", { class: "chips" },
        h("a", { class: "chip" + (asPatient ? "" : " on"), href: "#login" }, "پزشک یا مدیر"),
        h("a", { class: "chip" + (asPatient ? " on" : ""), href: "#patient-login" }, "بیمار"),
    );
    const staffForm = form([
        field("شماره‌ی موبایل", h("input", { name: "mobile", inputmode: "tel", autocomplete: "username", required: true })),
        field("رمز", h("input", { name: "password", type: "password", autocomplete: "current-password", required: true })),
    ], "ورود", async (data) => {
        const r = await api("POST", "/auth/login", { mobile: data.get("mobile"), password: data.get("password") });
        session.save(r.token, r.user);
        location.hash = r.user.role === "ADMIN" ? "#doctors" : "#patients";
        route();
    });
    const patientForm = form([
        field("کد ملی", h("input", { name: "nationalId", inputmode: "numeric", autocomplete: "username", required: true })),
        field("رمز پنل (همان که در برنامه‌ی گوشی گذاشتید)", h("input", { name: "password", type: "password", autocomplete: "current-password", required: true })),
    ], "ورود", async (data) => {
        let r;
        try {
            r = await api("POST", "/auth/patient-login", { nationalId: data.get("nationalId"), password: data.get("password") });
        } catch (e) {
            if (e.code === "invalid_credentials") throw Object.assign(new ApiError("patient_login"), { text: PATIENT_LOGIN_ERROR });
            throw e;
        }
        session.save(r.token, { role: "PATIENT", fullName: r.patient.firstName + " " + r.patient.lastName });
        location.hash = "#me";
        route();
    });
    show(h("section", { class: "card narrow" }, h("h1", {}, "ورود"), tabs, asPatient ? patientForm : staffForm));
}

function passwordView() {
    show(h("section", { class: "card narrow" },
        h("h1", {}, "تغییر رمز"),
        form([
            field("رمز فعلی", h("input", { name: "current", type: "password", autocomplete: "current-password", required: true })),
            field("رمز تازه (دست‌کم ۸ نویسه)", h("input", { name: "next", type: "password", autocomplete: "new-password", minlength: 8, required: true })),
        ], "ذخیره", async (data) => {
            const path = session.user.role === "PATIENT" ? "/patient/password" : "/me/password";
            await api("POST", path, { currentPassword: data.get("current"), newPassword: data.get("next") });
            show(h("section", { class: "card narrow" }, h("p", {}, "رمز تغییر کرد."), h("a", { href: "#" }, "بازگشت")));
        }),
    ));
}

async function doctorsView() {
    loading();
    let doctors;
    try { doctors = await api("GET", "/admin/doctors"); } catch (e) { return failure(e); }

    const rows = doctors.map((d) => h("tr", {},
        h("td", {}, d.fullName),
        h("td", { class: "ltr" }, d.mobile),
        h("td", {}, d.medicalCouncilNo || "—"),
        h("td", {}, d.active ? "فعال" : "غیرفعال"),
        h("td", {},
            h("button", { type: "button", class: "small", onclick: async () => {
                await api("PATCH", "/admin/doctors/" + d.id, { active: !d.active }).catch((e) => alert(message(e)));
                doctorsView();
            } }, d.active ? "غیرفعال کن" : "فعال کن"),
            " ",
            h("button", { type: "button", class: "small secondary", onclick: async () => {
                const password = prompt("رمز تازه برای " + d.fullName + " (دست‌کم ۸ نویسه):");
                if (!password) return;
                await api("PATCH", "/admin/doctors/" + d.id, { password })
                    .then(() => alert("رمز تغییر کرد."), (e) => alert(message(e)));
            } }, "رمز تازه"),
        ),
    ));

    show(
        h("section", { class: "card" },
            h("h1", {}, "پزشکان"),
            doctors.length === 0 ? h("p", { class: "muted" }, "هنوز پزشکی ثبت نشده است.") :
                h("table", {}, h("thead", {}, h("tr", {}, ["نام", "موبایل", "نظام پزشکی", "وضعیت", ""].map((t) => h("th", {}, t)))), h("tbody", {}, rows)),
        ),
        h("section", { class: "card" },
            h("h2", {}, "افزودن پزشک"),
            form([
                field("نام و نام خانوادگی", h("input", { name: "fullName", required: true })),
                field("شماره‌ی موبایل (نام کاربری)", h("input", { name: "mobile", inputmode: "tel", required: true })),
                field("شماره‌ی نظام پزشکی", h("input", { name: "council" })),
                field("رمز اولیه (دست‌کم ۸ نویسه)", h("input", { name: "password", type: "password", autocomplete: "new-password", minlength: 8, required: true })),
            ], "افزودن", async (data) => {
                await api("POST", "/admin/doctors", {
                    fullName: data.get("fullName"), mobile: data.get("mobile"),
                    medicalCouncilNo: data.get("council") || null, password: data.get("password"),
                });
                doctorsView();
            }),
        ),
    );
}

async function patientsView() {
    loading();
    let patients, invites;
    try {
        [patients, invites] = await Promise.all([api("GET", "/doctor/patients"), api("GET", "/doctor/invites")]);
    } catch (e) { return failure(e); }

    const search = h("input", { type: "search", placeholder: "جستجوی نام یا ۴ رقم آخر کد ملی" });
    const tbody = h("tbody");
    const fill = () => {
        const q = search.value.trim();
        const digits = q.replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x06F0));
        tbody.replaceChildren(...patients
            .filter((p) => !q || p.fullName.includes(q) || p.nationalIdLast4.includes(digits))
            .map((p) => h("tr", { class: "clickable", onclick: () => { location.hash = "#patient/" + p.id; } },
                h("td", {}, h("a", { href: "#patient/" + p.id }, p.fullName), p.attention ? [" ", h("span", { class: "badge", title: "نمره‌ی یکی از آزمون‌ها نسبت به ابتدا دست‌کم ۱۰ واحد پایین آمده است." }, "نیاز به توجه")] : null,
                    p.unreadMessages ? [" ", h("span", { class: "badge new" }, fa(p.unreadMessages) + " پیام تازه")] : null),
                h("td", {}, fa(p.age)),
                h("td", {}, GENDER[p.gender] || p.gender),
                h("td", { class: "ltr" }, "••••••" + p.nationalIdLast4),
                h("td", {}, p.parkinsonDiagnosed ? (p.takesParkinsonMedication ? "تشخیص، مصرف دارو" : "تشخیص، بدون دارو") : "بدون تشخیص"),
                h("td", {}, fa(p.resultCount)),
                h("td", {}, when(p.lastResultEpochMs)),
            )));
    };
    search.addEventListener("input", fill);
    fill();

    const inviteBox = h("div", { class: "invite", hidden: true });
    const newInvite = h("button", { type: "button", onclick: async () => {
        try {
            const invite = await api("POST", "/doctor/invites");
            inviteBox.replaceChildren(
                h("p", {}, "این کد را به بیمار بدهید تا در برنامه، بخش «اتصال به پزشک» وارد کند:"),
                h("p", { class: "code ltr" }, invite.code.slice(0, 4) + "-" + invite.code.slice(4)),
                h("p", { class: "muted" }, "یک‌بار مصرف، معتبر تا " + when(invite.expiresAtEpochMs)),
            );
            inviteBox.hidden = false;
        } catch (e) { alert(message(e)); }
    } }, "کد دعوت بیمار تازه");

    show(
        h("section", { class: "card" },
            h("div", { class: "row" }, h("h1", {}, "بیماران من"), newInvite),
            inviteBox,
            patients.length === 0
                ? h("p", { class: "muted" }, "هنوز بیماری وصل نشده است. یک کد دعوت بسازید و به بیمار بدهید.")
                : [search, h("table", {},
                    h("thead", {}, h("tr", {}, ["نام", "سن", "جنس", "کد ملی", "وضعیت", "آزمون‌ها", "آخرین آزمون"].map((t) => h("th", {}, t)))),
                    tbody)],
        ),
        invites.length === 0 ? null : h("section", { class: "card" },
            h("h2", {}, "کدهای دعوت"),
            h("table", {},
                h("thead", {}, h("tr", {}, ["کد", "ساخته‌شده", "وضعیت"].map((t) => h("th", {}, t)))),
                h("tbody", {}, invites.map((i) => h("tr", {},
                    h("td", { class: "ltr" }, i.code.slice(0, 4) + "-" + i.code.slice(4)),
                    h("td", {}, when(i.createdAtEpochMs)),
                    h("td", {}, i.usedAtEpochMs ? "استفاده شد: " + (i.patientName || "") :
                        i.expiresAtEpochMs < Date.now() ? "منقضی" : "تا " + when(i.expiresAtEpochMs)),
                ))),
            ),
        ),
    );
}

async function patientView(id) {
    loading();
    let p, results, analysis, notes;
    try {
        [p, results, analysis, notes] = await Promise.all([
            api("GET", "/doctor/patients/" + id), api("GET", "/doctor/patients/" + id + "/results"),
            api("GET", "/doctor/patients/" + id + "/analysis"), api("GET", "/doctor/patients/" + id + "/notes"),
        ]);
    } catch (e) { return failure(e); }

    const csv = h("button", { type: "button", class: "secondary", onclick: async () => {
        try { await download("/doctor/patients/" + id + "/results.csv", "patient-" + id + "-results.csv"); } catch (e) { alert(message(e)); }
    } }, "دریافت CSV نتایج");

    show(
        h("p", {}, h("a", { href: "#patients" }, "→ بیماران من")),
        h("section", { class: "card" },
            h("div", { class: "row" }, h("h1", {}, p.firstName + " " + p.lastName), results.length ? csv : null),
            h("dl", { class: "facts" },
                fact("سن", fa(p.age)),
                fact("جنس", GENDER[p.gender] || p.gender),
                fact("کد ملی", h("span", { class: "ltr" }, "••••••" + p.nationalIdLast4)),
                fact("موبایل", h("span", { class: "ltr" }, p.mobile)),
                fact("تشخیص پارکینسون", p.parkinsonDiagnosed ? "دارد" : "ندارد"),
                fact("مصرف داروی پارکینسون", p.takesParkinsonMedication == null ? "—" : p.takesParkinsonMedication ? "بله" : "خیر"),
                fact("دست غالب", HAND[p.dominantHand] || p.dominantHand),
                fact("رضایت", when(p.consentEpochMs)),
                fact("اتصال به شما", when(p.linkedAtEpochMs)),
            ),
        ),
        chatSection("/doctor/patients/" + id, true, "گفتگو با بیمار", { id: p.id, name: p.firstName + " " + p.lastName }),
        analysisSection(analysis),
        notesSection(id, notes),
        resultsSection(results, "هنوز نتیجه‌ای از این بیمار نرسیده است."),
    );
}

/** One card per test (and hand): first vs latest results, ON vs OFF, and the trend. */
function analysisSection(analysis, forPatient = false) {
    if (analysis.length === 0) return null;
    const signed = (v) => (v == null ? "—" : (v > 0 ? "+" : v < 0 ? "−" : "") + fa(Math.abs(v)));
    return h("section", { class: "card" },
        h("h2", {}, "خلاصه‌ی روند"),
        h("p", { class: "muted" }, "بر پایه‌ی نمره‌ی عملکرد (۰ تا ۱۰۰، بیشتر بهتر). «ابتدا» میانگین نخستین نتایج و «اخیر» میانگین آخرین نتایج است. این خلاصه کمکی برای خواندن نتایج است، نه تشخیص."),
        h("div", { class: "grid" }, analysis.map((a) => h("div", { class: "stat" + (a.attention && !forPatient ? " warn" : "") },
            h("h3", {}, typeName(a.type) + (a.hand ? " — دست " + HAND[a.hand] : "")),
            a.attention && !forPatient ? h("p", { class: "badge" }, "نیاز به توجه") : null,
            h("dl", {},
                fact("تعداد", fa(a.count) + (a.scoredCount !== a.count ? " (" + fa(a.scoredCount) + " با نمره)" : "")),
                fact("ابتدا ← اخیر", a.change == null ? "نتیجه‌ی کافی نیست" : fa(a.baseline) + " ← " + fa(a.recent) + " (" + signed(a.change) + ")"),
                fact("روند در ۳۰ روز", signed(a.slopePer30Days)),
                fact(h("span", {}, "میانگین ", h("bdi", {}, "ON")), a.onCount ? fa(a.onMean) + " (" + fa(a.onCount) + " بار)" : "—"),
                fact(h("span", {}, "میانگین ", h("bdi", {}, "OFF")), a.offCount ? fa(a.offMean) + " (" + fa(a.offCount) + " بار)" : "—"),
                fact("بازه", when(a.firstEpochMs) + " تا " + when(a.lastEpochMs)),
            ),
        ))),
    );
}

function notesSection(patientId, notes) {
    const list = h("div", { class: "notes" });
    const fill = () => list.replaceChildren(...(notes.length === 0
        ? [h("p", { class: "muted" }, "هنوز یادداشتی ننوشته‌اید.")]
        : notes.map((n) => h("div", { class: "note" },
            h("p", { class: "note-text" }, n.text),
            h("p", { class: "muted small" }, when(n.createdAtEpochMs), " — ", n.sharedWithPatient ? "بیمار هم می‌بیند" : "فقط شما می‌بینید", " ",
                h("button", { type: "button", class: "link", onclick: async () => {
                    if (!confirm("این یادداشت پاک شود؟")) return;
                    try { await api("DELETE", "/doctor/notes/" + n.id); notes = notes.filter((x) => x.id !== n.id); fill(); }
                    catch (e) { alert(message(e)); }
                } }, "پاک کردن")),
        ))));
    fill();
    const text = h("textarea", { name: "text", rows: 3, maxlength: 4000, required: true });
    const shared = h("input", { type: "checkbox", name: "shared" });
    const add = form([
        field("یادداشت تازه", text),
        h("label", { class: "check" }, shared, " بیمار هم در پنل خودش ببیند"),
    ], "افزودن یادداشت", async (data) => {
        const note = await api("POST", "/doctor/patients/" + patientId + "/notes", { text: data.get("text"), sharedWithPatient: shared.checked });
        notes = [note, ...notes];
        text.value = "";
        shared.checked = false;
        fill();
    });
    return h("section", { class: "card" }, h("h2", {}, "یادداشت‌ها"), add, list);
}

/** Results by test: chips to pick the test, the trend chart and the table. */
function resultsSection(results, emptyText) {
    if (results.length === 0) return h("section", { class: "card" }, h("p", { class: "muted" }, emptyText));
    const types = [...new Set(results.map((r) => r.type))];
    const content = h("div");
    const chips = h("div", { class: "chips" });
    let selected = types[0];
    const render = () => {
        chips.replaceChildren(...types.map((t) => h("button", {
            type: "button", class: "chip" + (t === selected ? " on" : ""), onclick: () => { selected = t; render(); },
        }, typeName(t) + " (" + fa(results.filter((r) => r.type === t).length) + ")")));
        const list = results.filter((r) => r.type === selected);
        content.replaceChildren(trendCard(list), resultsTable(list));
    };
    render();
    return h("section", { class: "card" }, h("h2", {}, "نتایج"), chips, content);
}

/** Downloads an authenticated file (a plain link cannot send the login token). */
async function download(path, filename) {
    const response = await fetch("/api" + path, { headers: { Authorization: "Bearer " + session.token } });
    if (!response.ok) throw new ApiError(response.status === 401 ? "unauthorized" : "server_error", response.status);
    const url = URL.createObjectURL(await response.blob());
    const a = h("a", { href: url, download: filename });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// --- The patient's own panel ---

async function myView() {
    loading();
    let me, results, analysis, notes, conversations;
    try {
        [me, results, analysis, notes, conversations] = await Promise.all([
            api("GET", "/patient/me"), api("GET", "/patient/results"), api("GET", "/patient/analysis"), api("GET", "/patient/notes"),
            api("GET", "/patient/conversations"),
        ]);
    } catch (e) { return failure(e); }
    show(
        h("section", { class: "card" },
            h("h1", {}, me.firstName + " " + me.lastName),
            h("p", {}, me.doctors.length ? "پزشک شما: " + me.doctors.join("، ") : "هنوز به پزشکی وصل نیستید."),
            h("p", { class: "muted" }, "نتایج آزمون‌هایی که با برنامه‌ی گوشی انجام داده‌اید اینجا دیده می‌شود."),
        ),
        patientChats(conversations),
        h("section", { class: "card" },
            h("h2", {}, "یادداشت‌های پزشک"),
            notes.length === 0 ? h("p", { class: "muted" }, "یادداشتی نیست.") :
                h("div", { class: "notes" }, notes.map((n) => h("div", { class: "note" },
                    h("p", { class: "note-text" }, n.text),
                    h("p", { class: "muted small" }, n.doctorName + " — " + when(n.createdAtEpochMs)),
                ))),
        ),
        analysisSection(analysis, true),
        resultsSection(results, "هنوز نتیجه‌ای فرستاده نشده است."),
    );
}

// --- Messages (text and voice) between a doctor and a patient ---

/** Stops the timers of the page being left, so an old conversation does not keep polling. */
let leavePage = () => {};
const POLL_MS = 10000;
const MAX_VOICE_MS = 3 * 60 * 1000;

/** The patient's conversations: one chat, with chips to switch when they have several doctors. */
function patientChats(conversations) {
    if (conversations.length === 0) return null;
    const holder = h("div");
    const chips = h("div", { class: "chips" });
    const open = (c) => {
        chips.replaceChildren(...conversations.map((x) => h("button", {
            type: "button", class: "chip" + (x === c ? " on" : ""), onclick: () => open(x),
        }, x.doctorName, x.unread && x !== c ? [" ", h("span", { class: "badge new" }, fa(x.unread))] : null)));
        c.unread = 0;
        leavePage();
        holder.replaceChildren(chatSection("/patient/doctors/" + c.doctorId, false, "گفتگو با " + c.doctorName, { id: c.doctorId, name: c.doctorName }));
    };
    open(conversations.find((c) => c.unread) || conversations[0]);
    return h("div", {}, conversations.length > 1 ? h("section", { class: "card" }, h("h2", {}, "پزشکان شما"), chips) : null, holder);
}

/** A conversation at [base] (…/messages under it), written as the doctor when [asDoctor]; [peer] can be called. */
function chatSection(base, asDoctor, title, peer) {
    const list = h("div", { class: "chat-list", "aria-live": "polite" });
    const status = h("p", { class: "error", hidden: true });
    let lastId = 0;
    let stopped = false;

    const bubble = (m) => {
        const mine = m.fromDoctor === asDoctor;
        return h("div", { class: "bubble " + (mine ? "mine" : "theirs") },
            m.text != null ? h("p", { class: "note-text" }, m.text) : voicePlayer(base, m),
            h("p", { class: "muted small" }, when(m.createdAtEpochMs), mine && m.readAtEpochMs ? " — خوانده شد" : null),
        );
    };
    const add = (messages, scroll) => {
        if (messages.length === 0) return;
        if (lastId === 0) list.replaceChildren();
        for (const m of messages) if (m.id > lastId) { list.append(bubble(m)); lastId = m.id; }
        if (scroll) list.scrollTop = list.scrollHeight;
    };
    const refresh = async (all) => {
        try {
            const messages = await api("GET", base + "/messages" + (all ? "" : "?after=" + lastId));
            if (all) { lastId = 0; list.replaceChildren(); }
            if (messages.length === 0 && lastId === 0) list.replaceChildren(h("p", { class: "muted" }, "هنوز پیامی نیست. اولین پیام را بنویسید."));
            const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
            add(messages, all || atBottom);
            if (messages.some((m) => m.fromDoctor !== asDoctor) || all) await api("POST", base + "/messages/read");
            status.hidden = true;
        } catch (e) { status.textContent = message(e); status.hidden = false; }
    };

    const send = async (doIt) => {
        status.hidden = true;
        try { add([await doIt()], true); } catch (e) { status.textContent = message(e); status.hidden = false; throw e; }
    };
    const text = h("textarea", { rows: 2, maxlength: 4000, placeholder: "پیام…" });
    const sendButton = h("button", { type: "submit" }, "بفرست");
    const composer = h("form", { class: "composer", onsubmit: async (event) => {
        event.preventDefault();
        if (!text.value.trim()) return;
        sendButton.disabled = true;
        try { await send(() => api("POST", base + "/messages", { text: text.value })); text.value = ""; } catch (e) { /* shown */ }
        finally { sendButton.disabled = false; }
    } }, text, recorder(base, send), sendButton);
    // Enter sends, Shift+Enter makes a new line.
    text.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); composer.requestSubmit(); } });

    const timer = setInterval(() => { if (!stopped && !document.hidden) refresh(false); }, POLL_MS);
    const previous = leavePage;
    leavePage = () => { previous(); stopped = true; clearInterval(timer); };
    list.append(h("p", { class: "muted" }, "در حال بارگذاری…"));
    refresh(true);
    const callButtons = peer && Calls.supported ? h("div", { class: "call-buttons" },
        h("button", { type: "button", class: "secondary", onclick: () => Calls.start(peer.id, peer.name, false) }, "📞 تماس صوتی"),
        h("button", { type: "button", class: "secondary", onclick: () => Calls.start(peer.id, peer.name, true) }, "🎥 تماس تصویری"),
    ) : null;
    return h("section", { class: "card chat" }, h("div", { class: "row" }, h("h2", {}, title), callButtons), list, status, composer);
}

/** A button that records a voice message with the microphone and sends it. */
function recorder(base, send) {
    if (!window.MediaRecorder || !navigator.mediaDevices) return null;
    const button = h("button", { type: "button", class: "secondary", title: "ضبط پیام صوتی" }, "🎤 ضبط صدا");
    let rec = null, started = 0, tick = null, cancelled = false;
    const reset = () => {
        clearInterval(tick);
        button.textContent = "🎤 ضبط صدا";
        button.classList.remove("recording");
        rec = null;
    };
    const stop = (cancel) => { cancelled = cancel; if (rec && rec.state !== "inactive") rec.stop(); };
    button.addEventListener("click", async () => {
        if (rec) return stop(false);
        let stream;
        try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
        catch (e) { alert("دسترسی به میکروفون داده نشد."); return; }
        const type = ["audio/webm;codecs=opus", "audio/mp4", "audio/ogg;codecs=opus"].find((t) => MediaRecorder.isTypeSupported(t));
        rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
        const chunks = [];
        cancelled = false;
        rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
        rec.onstop = async () => {
            stream.getTracks().forEach((t) => t.stop());
            const ms = Math.min(Date.now() - started, MAX_VOICE_MS);
            const mime = rec.mimeType;
            reset();
            if (cancelled || ms < 700 || chunks.length === 0) return;
            const blob = new Blob(chunks, { type: mime });
            button.disabled = true;
            try { await send(() => upload(base + "/messages/voice?ms=" + ms, blob)); } catch (e) { /* shown */ }
            finally { button.disabled = false; }
        };
        rec.start();
        started = Date.now();
        button.classList.add("recording");
        const show = () => {
            const s = Math.floor((Date.now() - started) / 1000);
            button.textContent = "⏹ پایان و ارسال " + fa(Math.floor(s / 60)) + ":" + fa(s % 60).padStart(2, "۰");
            if (Date.now() - started >= MAX_VOICE_MS) stop(false);
        };
        show();
        tick = setInterval(show, 500);
    });
    // Leaving the page while recording drops the recording.
    const previous = leavePage;
    leavePage = () => { previous(); stop(true); };
    return button;
}

async function upload(path, blob) {
    let response;
    try {
        response = await fetch("/api" + path, {
            method: "POST", body: blob,
            headers: { "Content-Type": blob.type || "application/octet-stream", Authorization: "Bearer " + session.token },
        });
    } catch (e) { throw new ApiError("network", 0); }
    const data = await response.json().catch(() => null);
    if (!response.ok) throw new ApiError((data && data.error) || (response.status === 429 ? "rate_limited" : "server_error"), response.status);
    return data;
}

/** Plays a voice message; its sound is fetched with the login token only when first played. */
function voicePlayer(base, m) {
    const seconds = Math.round((m.audioMs || 0) / 1000);
    const label = "پیام صوتی " + fa(Math.floor(seconds / 60)) + ":" + fa(seconds % 60).padStart(2, "۰");
    const button = h("button", { type: "button", class: "secondary small" }, "▶ " + label);
    const holder = h("div", { class: "voice" }, button);
    button.addEventListener("click", async () => {
        button.disabled = true;
        try {
            const response = await fetch("/api" + base + "/messages/" + m.id + "/audio", { headers: { Authorization: "Bearer " + session.token } });
            if (!response.ok) throw new Error();
            const audio = h("audio", { controls: true, src: URL.createObjectURL(await response.blob()) });
            holder.replaceChildren(audio);
            audio.play().catch(() => {});
        } catch (e) { button.disabled = false; alert("پخش صدا ممکن نشد."); }
    });
    return holder;
}

function fact(label, value) { return h("div", {}, h("dt", {}, label), h("dd", {}, value)); }

/** Performance index over time, oldest on the left; colour shows the medication state. */
function trendCard(list) {
    const points = list.filter((r) => r.performanceIndex != null).slice().reverse();
    if (points.length === 0) return h("p", { class: "muted" }, "این آزمون نمره‌ی عملکرد ندارد.");

    const W = 640, H = 220, L = 36, R = 12, T = 12, B = 28;
    const t0 = points[0].timestampEpochMs, t1 = points[points.length - 1].timestampEpochMs;
    const x = (i) => points.length === 1 ? (L + W - R) / 2 : L + (i * (W - L - R)) / (points.length - 1);
    const y = (v) => T + ((100 - v) * (H - T - B)) / 100;
    const grid = [0, 25, 50, 75, 100].map((v) => [
        svg("line", { x1: L, x2: W - R, y1: y(v), y2: y(v), class: "grid" }),
        svg("text", { x: L - 6, y: y(v) + 4, class: "axis", "text-anchor": "end" }, fa(v)),
    ]);
    const line = svg("polyline", { points: points.map((p, i) => x(i) + "," + y(p.performanceIndex)).join(" "), class: "trend" });
    const dots = points.map((p, i) => svg("circle", { cx: x(i), cy: y(p.performanceIndex), r: 5, class: "dot " + (p.medicationState || "none") },
        svg("title", {}, when(p.timestampEpochMs) + " — " + fa(p.performanceIndex) + (p.medicationState ? " — " + MEDICATION[p.medicationState] : ""))));
    const labels = [
        svg("text", { x: L, y: H - 8, class: "axis", "text-anchor": "start" }, shortDateFmt.format(new Date(t0))),
        svg("text", { x: W - R, y: H - 8, class: "axis", "text-anchor": "end" }, shortDateFmt.format(new Date(t1))),
    ];

    const mean = (state) => {
        const v = points.filter((p) => p.medicationState === state).map((p) => p.performanceIndex);
        return v.length ? fa(Math.round(v.reduce((a, b) => a + b, 0) / v.length)) + " (" + fa(v.length) + " بار)" : "—";
    };
    return h("div", { class: "trend-box" },
        h("div", { class: "chart ltr" }, svg("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": "نمودار نمره‌ی عملکرد" }, grid, line, dots, labels)),
        h("p", { class: "legend" },
            legendItem("on", "ON"), legendItem("off", "OFF"), legendItem("none", "نامشخص"),
        ),
        h("p", {}, "میانگین نمره‌ی عملکرد: ", h("bdi", {}, "ON"), " ", mean("on"), "، ", h("bdi", {}, "OFF"), " ", mean("off")),
    );
}

function legendItem(state, label) {
    return h("span", { class: "item" }, h("span", { class: "key " + state }), h("bdi", {}, label));
}

function resultsTable(list) {
    return h("table", {},
        h("thead", {}, h("tr", {}, ["زمان", "دست", "دارو", "کیفیت", "نمره‌ی عملکرد", "جزئیات"].map((t) => h("th", {}, t)))),
        h("tbody", {}, list.map((r) => h("tr", {},
            h("td", {}, when(r.timestampEpochMs)),
            h("td", {}, HAND[r.hand] || "—"),
            h("td", {}, MEDICATION[r.medicationState] || "—"),
            h("td", {}, (QUALITY[r.qualityStatus] || r.qualityStatus) + (r.qualityScore != null ? " (" + fa(r.qualityScore) + ")" : "")),
            h("td", {}, fa(r.performanceIndex)),
            h("td", {}, h("details", {}, h("summary", {}, "اندازه‌ها"), metricsList(r))),
        ))),
    );
}

function metricsList(r) {
    const entries = Object.entries(r.metrics);
    return h("dl", { class: "metrics ltr" },
        entries.map(([k, v]) => h("div", {}, h("dt", {}, k), h("dd", {}, typeof v === "number" ? v.toLocaleString("en-US", { maximumFractionDigits: 3 }) : JSON.stringify(v)))),
        h("div", {}, h("dt", {}, "version"), h("dd", {}, r.algorithmVersion + " / " + r.scoringVersion)),
    );
}

// --- Routing ---

function route() {
    leavePage();
    leavePage = () => {};
    const user = session.user;
    // Calls can ring on any page of a doctor's or a patient's panel.
    if (user && user.role !== "ADMIN" && Calls.supported) Calls.connect(session.token); else Calls.disconnect();
    const nav = document.getElementById("nav");
    nav.hidden = !user;
    document.getElementById("who").textContent = user ? user.fullName : "";
    const hash = location.hash.replace(/^#/, "");
    if (!user) return loginView();
    if (hash === "password") return passwordView();
    if (user.role === "PATIENT") return myView();
    if (user.role === "ADMIN") return doctorsView();
    const m = hash.match(/^patient\/(\d+)$/);
    if (m) return patientView(m[1]);
    return patientsView();
}

window.addEventListener("hashchange", route);
document.getElementById("logout").addEventListener("click", () => { Calls.disconnect(); session.clear(); location.hash = "#login"; route(); });
route();
