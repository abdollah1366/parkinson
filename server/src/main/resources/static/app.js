"use strict";

// Doctor and admin panel. Plain DOM building (no innerHTML with data), so patient text can never run as code.

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
};

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
    if (response.status === 401 && path !== "/auth/login") {
        session.clear();
        location.hash = "#login";
        throw new ApiError("unauthorized", 401);
    }
    if (response.status === 204) return null;
    const data = await response.json().catch(() => null);
    if (!response.ok) throw new ApiError((data && data.error) || "server_error", response.status);
    return data;
}

const message = (e) => ERRORS[e.code] || ERRORS.server_error;

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
function show(...nodes) { view().replaceChildren(...nodes); }
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

function loginView() {
    show(h("section", { class: "card narrow" },
        h("h1", {}, "ورود"),
        form([
            field("شماره‌ی موبایل", h("input", { name: "mobile", inputmode: "tel", autocomplete: "username", required: true })),
            field("رمز", h("input", { name: "password", type: "password", autocomplete: "current-password", required: true })),
        ], "ورود", async (data) => {
            const r = await api("POST", "/auth/login", { mobile: data.get("mobile"), password: data.get("password") });
            session.save(r.token, r.user);
            location.hash = r.user.role === "ADMIN" ? "#doctors" : "#patients";
            route();
        }),
    ));
}

function passwordView() {
    show(h("section", { class: "card narrow" },
        h("h1", {}, "تغییر رمز"),
        form([
            field("رمز فعلی", h("input", { name: "current", type: "password", autocomplete: "current-password", required: true })),
            field("رمز تازه (دست‌کم ۸ نویسه)", h("input", { name: "next", type: "password", autocomplete: "new-password", minlength: 8, required: true })),
        ], "ذخیره", async (data) => {
            await api("POST", "/me/password", { currentPassword: data.get("current"), newPassword: data.get("next") });
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
                h("td", {}, h("a", { href: "#patient/" + p.id }, p.fullName)),
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
    let p, results;
    try {
        [p, results] = await Promise.all([api("GET", "/doctor/patients/" + id), api("GET", "/doctor/patients/" + id + "/results")]);
    } catch (e) { return failure(e); }

    const types = [...new Set(results.map((r) => r.type))];
    const content = h("div");
    const chips = h("div", { class: "chips" });
    let selected = types[0] || null;

    const render = () => {
        chips.replaceChildren(...types.map((t) => h("button", {
            type: "button", class: "chip" + (t === selected ? " on" : ""), onclick: () => { selected = t; render(); },
        }, typeName(t) + " (" + fa(results.filter((r) => r.type === t).length) + ")")));
        const list = results.filter((r) => r.type === selected);
        content.replaceChildren(trendCard(list), resultsTable(list));
    };

    show(
        h("p", {}, h("a", { href: "#patients" }, "→ بیماران من")),
        h("section", { class: "card" },
            h("h1", {}, p.firstName + " " + p.lastName),
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
        results.length === 0
            ? h("section", { class: "card" }, h("p", { class: "muted" }, "هنوز نتیجه‌ای از این بیمار نرسیده است."))
            : h("section", { class: "card" }, h("h2", {}, "نتایج"), chips, content),
    );
    if (selected) render();
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
    const user = session.user;
    const nav = document.getElementById("nav");
    nav.hidden = !user;
    document.getElementById("who").textContent = user ? user.fullName : "";
    const hash = location.hash.replace(/^#/, "");
    if (!user) return loginView();
    if (hash === "password") return passwordView();
    if (user.role === "ADMIN") return doctorsView();
    const m = hash.match(/^patient\/(\d+)$/);
    if (m) return patientView(m[1]);
    return patientsView();
}

window.addEventListener("hashchange", route);
document.getElementById("logout").addEventListener("click", () => { session.clear(); location.hash = "#login"; route(); });
route();
