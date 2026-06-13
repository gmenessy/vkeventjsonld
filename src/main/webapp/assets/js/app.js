/* VK Veranstaltungskalender – Vanilla-SPA
 * Drei-gliedrige Oberfläche: Suche/Filter · Ergebnisübersicht · Detailansicht.
 * Leichtgewichtig, ohne Framework. Alle Nutzerdaten DOM-sicher via textContent.
 */
(function () {
  "use strict";

  var API = "api";
  var PAGE_SIZE = 20;

  // ---- State --------------------------------------------------------------
  var state = {
    mandant: null, vk: null,        // aktueller Mandant/VK (Multi-Tenant)
    q: "", from: "", to: "", category: "", place: "", placeLabel: "",
    organizer: "", organizerLabel: "", attendanceMode: "", free: false,
    sort: "date", page: 1
  };
  var meta = { page: 1, size: PAGE_SIZE, total: 0 };
  var categories = [];
  var searchController = null;     // AbortController der laufenden Suche
  var searchTimer = null;
  var internalHash = false;        // unterscheidet eigene Hash-Updates von echter Navigation

  // ---- DOM-Helfer ---------------------------------------------------------
  function $(id) { return document.getElementById(id); }
  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === "text") { node.textContent = attrs[k]; }
        else if (k === "html") { /* absichtlich nicht unterstützt */ }
        else if (k.indexOf("on") === 0 && typeof attrs[k] === "function") {
          node.addEventListener(k.slice(2), attrs[k]);
        } else if (attrs[k] !== null && attrs[k] !== undefined && attrs[k] !== false) {
          node.setAttribute(k, attrs[k]);
        }
      });
    }
    (children || []).forEach(function (c) {
      if (c == null) return;
      node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return node;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  // ---- API ----------------------------------------------------------------
  function buildUrl(path, params) {
    var url = new URL(path, window.location.href);
    if (params) {
      Object.keys(params).forEach(function (k) {
        var v = params[k];
        if (v !== undefined && v !== null && v !== "" && v !== false) {
          url.searchParams.set(k, v);
        }
      });
    }
    // Kein Tenant-Parameter: Mandant/VK bestimmt das System serverseitig (ConfigVk).
    return url.toString();
  }

  function getJson(path, params, signal) {
    return fetch(buildUrl(path, params), {
      headers: { "Accept": "application/json" },
      credentials: "same-origin",
      signal: signal
    }).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok || body.success === false) {
          var msg = (body.errors && body.errors[0] && body.errors[0].message) || "Fehler";
          throw new Error(msg);
        }
        return body;
      });
    });
  }

  // ---- Datum/Format -------------------------------------------------------
  var MONTHS = ["Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"];
  var WEEKDAYS = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"];
  function parseDate(iso) { return iso ? new Date(iso) : null; }
  function fmtTime(d) {
    return d.getHours().toString().padStart(2, "0") + ":" + d.getMinutes().toString().padStart(2, "0");
  }
  function fmtDateLong(d) {
    return WEEKDAYS[d.getDay()] + ", " + d.getDate() + ". " + MONTHS[d.getMonth()] + " " + d.getFullYear();
  }
  function modeLabel(m) {
    return m === "ONLINE" ? "Online" : m === "MIXED" ? "Hybrid" : "Vor Ort";
  }
  function statusLabel(s) {
    return { EventCancelled: "Abgesagt", EventPostponed: "Verschoben",
      EventRescheduled: "Neuer Termin", EventMovedOnline: "Findet online statt" }[s] || null;
  }

  // ===========================================================================
  // 1. FILTER
  // ===========================================================================
  function renderFilters() {
    var body = $("vk-filters-body");
    clear(body);

    // Zeitraum
    var dateGroup = el("div", { "class": "vk-filter-group" }, [
      el("p", { "class": "vk-filter-group__label", text: "Zeitraum" })
    ]);
    var fromField = el("div", { "class": "vk-field" }, [
      el("label", { "for": "vk-from", text: "Von" }),
      el("input", { "class": "vk-input", id: "vk-from", type: "date", value: state.from,
        onchange: function (e) { state.from = e.target.value; resetAndSearch(); } })
    ]);
    var toField = el("div", { "class": "vk-field" }, [
      el("label", { "for": "vk-to", text: "Bis" }),
      el("input", { "class": "vk-input", id: "vk-to", type: "date", value: state.to,
        onchange: function (e) { state.to = e.target.value; resetAndSearch(); } })
    ]);
    dateGroup.appendChild(fromField);
    dateGroup.appendChild(toField);
    body.appendChild(dateGroup);

    // Kategorien
    var catGroup = el("div", { "class": "vk-filter-group" }, [
      el("p", { "class": "vk-filter-group__label", text: "Kategorie" })
    ]);
    catGroup.appendChild(renderCategoryTree(categories));
    body.appendChild(catGroup);

    // Anwesenheitsmodus
    var modeGroup = el("div", { "class": "vk-filter-group" }, [
      el("p", { "class": "vk-filter-group__label", text: "Format" })
    ]);
    var chipRow = el("div", { "class": "vk-chip-row" });
    [["", "Alle"], ["OFFLINE", "Vor Ort"], ["ONLINE", "Online"], ["MIXED", "Hybrid"]].forEach(function (m) {
      chipRow.appendChild(el("button", {
        type: "button", "class": "vk-chip", "aria-pressed": state.attendanceMode === m[0] ? "true" : "false",
        text: m[1], onclick: function () { state.attendanceMode = m[0]; renderFilters(); resetAndSearch(); }
      }));
    });
    modeGroup.appendChild(chipRow);
    body.appendChild(modeGroup);

    // Ort-Autocomplete
    body.appendChild(renderAutocomplete("Ort", "vk-place", API + "/places",
      state.placeLabel, function (item) {
        state.place = item ? item.id : ""; state.placeLabel = item ? item.name : ""; resetAndSearch();
      }));

    // Veranstalter-Autocomplete
    body.appendChild(renderAutocomplete("Veranstalter", "vk-org", API + "/organizers",
      state.organizerLabel, function (item) {
        state.organizer = item ? item.id : ""; state.organizerLabel = item ? item.name : ""; resetAndSearch();
      }));

    // Kostenlos
    var freeGroup = el("div", { "class": "vk-filter-group" });
    var sw = el("label", { "class": "vk-switch" }, [
      el("input", { type: "checkbox", onchange: function (e) { state.free = e.target.checked; resetAndSearch(); } }),
      document.createTextNode("Nur kostenlose Veranstaltungen")
    ]);
    if (state.free) sw.querySelector("input").checked = true;
    freeGroup.appendChild(sw);
    body.appendChild(freeGroup);
  }

  function renderCategoryTree(cats) {
    var ul = el("ul", { "class": "vk-cat-tree" });
    cats.forEach(function (cat) {
      var pressed = state.category === cat.id;
      var btn = el("button", {
        type: "button", "class": "vk-cat-btn", "aria-pressed": pressed ? "true" : "false",
        onclick: function () { state.category = pressed ? "" : cat.id; renderFilters(); resetAndSearch(); }
      }, [
        el("span", { text: cat.name }),
        el("span", { "class": "vk-count", text: String(cat.eventCount) })
      ]);
      var li = el("li", { "class": "vk-cat-item" }, [btn]);
      if (cat.children && cat.children.length) {
        li.appendChild(renderCategoryTree(cat.children));
      }
      ul.appendChild(li);
    });
    return ul;
  }

  function renderAutocomplete(label, idBase, endpoint, currentLabel, onSelect) {
    var group = el("div", { "class": "vk-filter-group" });
    var field = el("div", { "class": "vk-field" });
    field.appendChild(el("label", { "for": idBase, text: label }));
    var input = el("input", { "class": "vk-input", id: idBase, type: "text",
      autocomplete: "off", value: currentLabel || "",
      placeholder: "Tippen zum Suchen …", role: "combobox", "aria-expanded": "false",
      "aria-controls": idBase + "-list" });
    var list = el("ul", { "class": "vk-cat-tree", id: idBase + "-list", role: "listbox" });
    var timer = null;
    input.addEventListener("input", function () {
      var val = input.value.trim();
      if (!val) { clear(list); input.setAttribute("aria-expanded", "false"); onSelect(null); return; }
      clearTimeout(timer);
      timer = setTimeout(function () {
        getJson(endpoint, { q: val }).then(function (body) {
          clear(list);
          (body.data || []).forEach(function (item) {
            var label2 = item.name + (item.locality ? " · " + item.locality : "");
            list.appendChild(el("li", { "class": "vk-cat-item", role: "option" }, [
              el("button", { type: "button", "class": "vk-cat-btn", text: label2,
                onclick: function () {
                  input.value = item.name; clear(list);
                  input.setAttribute("aria-expanded", "false"); onSelect(item);
                } })
            ]));
          });
          input.setAttribute("aria-expanded", body.data && body.data.length ? "true" : "false");
        }).catch(function () {});
      }, 250);
    });
    field.appendChild(input);
    field.appendChild(list);
    group.appendChild(field);
    return group;
  }

  // ===========================================================================
  // 2. ERGEBNISÜBERSICHT
  // ===========================================================================
  function searchParams() {
    return {
      q: state.q, from: state.from, to: state.to, category: state.category,
      place: state.place, organizer: state.organizer, attendanceMode: state.attendanceMode,
      free: state.free, sort: state.sort, page: state.page, size: PAGE_SIZE
    };
  }

  function showSkeletons() {
    var cards = $("vk-cards");
    clear(cards);
    for (var i = 0; i < 5; i++) {
      cards.appendChild(el("li", {}, [el("div", { "class": "vk-skeleton" })]));
    }
  }

  function runSearch() {
    if (searchController) { searchController.abort(); }
    searchController = new AbortController();
    showSkeletons();
    $("vk-result-count").textContent = "Suche läuft …";

    getJson(API + "/events", searchParams(), searchController.signal)
      .then(function (body) {
        meta = body.meta || meta;
        renderCards(body.data || []);
        renderPagination();
        var total = meta.total;
        $("vk-result-count").textContent = total === 0
          ? "Keine Veranstaltungen gefunden"
          : total + (total === 1 ? " Veranstaltung" : " Veranstaltungen");
      })
      .catch(function (err) {
        if (err.name === "AbortError") return; // veraltete Anfrage, ignorieren
        $("vk-result-count").textContent = "Fehler bei der Suche";
        toast("Suche fehlgeschlagen: " + err.message);
      });
  }

  function renderCards(items) {
    var cards = $("vk-cards");
    clear(cards);
    if (!items.length) {
      cards.appendChild(el("li", {}, [
        el("div", { "class": "vk-empty", text: "Keine Treffer. Passen Sie Suche oder Filter an." })
      ]));
      return;
    }
    var current = currentDetailId();
    items.forEach(function (ev) {
      cards.appendChild(el("li", {}, [buildCard(ev, current)]));
    });
  }

  function buildCard(ev, currentId) {
    var start = parseDate(ev.startAt);
    var dateBox = el("div", { "class": "vk-card__date" }, [
      el("span", { "class": "vk-card__day", text: start ? String(start.getDate()) : "–" }),
      el("span", { "class": "vk-card__month", text: start ? MONTHS[start.getMonth()] : "" })
    ]);

    var meta1 = el("div", { "class": "vk-card__meta" }, [
      el("span", { text: start ? fmtTime(start) + " Uhr" : "" }),
      ev.placeName ? el("span", { text: ev.placeName }) : (ev.attendanceMode === "ONLINE" ? el("span", { text: "Online" }) : null),
      ev.locality ? el("span", { text: ev.locality }) : null,
      ev.primaryCategory ? el("span", { text: ev.primaryCategory }) : null
    ]);

    var tags = el("div", { "class": "vk-card__tags" });
    var st = statusLabel(ev.eventStatus);
    if (st) tags.appendChild(el("span", { "class": "vk-tag vk-tag--status", text: st }));
    if (ev.isAccessibleForFree) tags.appendChild(el("span", { "class": "vk-tag vk-tag--free", text: "Kostenlos" }));
    else if (ev.minPrice != null) tags.appendChild(el("span", { "class": "vk-tag", text: "ab " + formatPrice(ev.minPrice) }));
    if (ev.attendanceMode && ev.attendanceMode !== "OFFLINE")
      tags.appendChild(el("span", { "class": "vk-tag", text: modeLabel(ev.attendanceMode) }));
    if (ev.hasAccessibilityInfo) tags.appendChild(el("span", { "class": "vk-tag vk-tag--a11y", text: "♿ Barrierefrei-Infos" }));

    var body = el("div", { "class": "vk-card__body" }, [
      el("h3", { "class": "vk-card__title", text: ev.title }),
      meta1,
      ev.shortDescription ? el("p", { "class": "vk-card__desc", text: ev.shortDescription }) : null,
      tags
    ]);

    return el("button", {
      type: "button", "class": "vk-card",
      "aria-current": currentId === ev.id ? "true" : "false",
      "aria-label": ev.title + ", " + (start ? fmtDateLong(start) : ""),
      onclick: function () { location.hash = "#/events/" + ev.id; }
    }, [dateBox, body]);
  }

  function formatPrice(p) {
    var n = Number(p);
    return n.toLocaleString("de-DE", { style: "currency", currency: "EUR" });
  }

  function renderPagination() {
    var nav = $("vk-pagination");
    clear(nav);
    var totalPages = Math.max(1, Math.ceil(meta.total / meta.size));
    if (meta.total === 0) return;

    function pageBtn(label, page, opts) {
      opts = opts || {};
      return el("button", {
        "class": "vk-page-btn", type: "button", text: label,
        disabled: opts.disabled ? "disabled" : false,
        "aria-current": opts.current ? "page" : false,
        "aria-label": opts.ariaLabel || ("Seite " + page),
        onclick: function () { goToPage(page); }
      });
    }
    nav.appendChild(pageBtn("‹", meta.page - 1, { disabled: meta.page <= 1, ariaLabel: "Vorherige Seite" }));

    var start = Math.max(1, meta.page - 2);
    var end = Math.min(totalPages, start + 4);
    start = Math.max(1, end - 4);
    if (start > 1) { nav.appendChild(pageBtn("1", 1)); if (start > 2) nav.appendChild(el("span", { "class": "vk-page-info", text: "…" })); }
    for (var i = start; i <= end; i++) {
      nav.appendChild(pageBtn(String(i), i, { current: i === meta.page }));
    }
    if (end < totalPages) {
      if (end < totalPages - 1) nav.appendChild(el("span", { "class": "vk-page-info", text: "…" }));
      nav.appendChild(pageBtn(String(totalPages), totalPages));
    }
    nav.appendChild(pageBtn("›", meta.page + 1, { disabled: meta.page >= totalPages, ariaLabel: "Nächste Seite" }));
  }

  function goToPage(page) {
    state.page = page;
    runSearch();
    writeListHash();
    $("vk-results").scrollIntoView({ block: "start" });
    $("vk-results").focus();
  }

  function resetAndSearch() {
    state.page = 1;
    runSearch();
    writeListHash();
  }

  // ---- Deep-Linkbare Such-URLs (Hash-State) -------------------------------
  function listHash() {
    var p = new URLSearchParams();
    if (state.q) p.set("q", state.q);
    if (state.from) p.set("from", state.from);
    if (state.to) p.set("to", state.to);
    if (state.category) p.set("category", state.category);
    if (state.place) { p.set("place", state.place); p.set("placeLabel", state.placeLabel || ""); }
    if (state.organizer) { p.set("organizer", state.organizer); p.set("orgLabel", state.organizerLabel || ""); }
    if (state.attendanceMode) p.set("attendanceMode", state.attendanceMode);
    if (state.free) p.set("free", "true");
    if (state.sort && state.sort !== "date") p.set("sort", state.sort);
    if (state.page > 1) p.set("page", String(state.page));
    var qs = p.toString();
    return qs ? "#/events?" + qs : "#/events";
  }

  function writeListHash() {
    var h = listHash();
    if (h === (location.hash || "")) return;   // keine Aenderung -> kein Event
    internalHash = true;
    location.hash = h;
  }

  function applyHashToState() {
    var hash = location.hash || "";
    var qIndex = hash.indexOf("?");
    var p = new URLSearchParams(qIndex >= 0 ? hash.slice(qIndex + 1) : "");
    state.q = p.get("q") || "";
    state.from = p.get("from") || "";
    state.to = p.get("to") || "";
    state.category = p.get("category") || "";
    state.place = p.get("place") || "";
    state.placeLabel = p.get("placeLabel") || "";
    state.organizer = p.get("organizer") || "";
    state.organizerLabel = p.get("orgLabel") || "";
    state.attendanceMode = p.get("attendanceMode") || "";
    state.free = p.get("free") === "true";
    state.sort = p.get("sort") || "date";
    state.page = Math.max(1, parseInt(p.get("page") || "1", 10) || 1);
  }

  function syncFilterInputs() {
    var input = $("vk-q");
    input.value = state.q;
    $("vk-q-clear").hidden = !state.q;
    $("vk-sort").value = state.sort;
    renderFilters();
  }

  // ===========================================================================
  // 3. DETAILANSICHT
  // ===========================================================================
  function currentDetailId() {
    var m = location.hash.match(/^#\/events\/(.+)$/);
    return m ? decodeURIComponent(m[1]) : null;
  }

  function openDetail(id) {
    var panel = $("vk-detail");
    var content = $("vk-detail-content");
    var placeholder = $("vk-detail-placeholder");
    panel.classList.add("is-active");
    placeholder.hidden = true;
    content.hidden = false;
    clear(content);
    content.appendChild(el("div", { "class": "vk-skeleton" }));
    highlightActiveCard(id);

    getJson(API + "/events/" + encodeURIComponent(id)).then(function (body) {
      renderDetail(body.data);
    }).catch(function (err) {
      clear(content);
      content.appendChild(el("div", { "class": "vk-empty", text: "Veranstaltung nicht gefunden." }));
    });
  }

  function highlightActiveCard(id) {
    var cards = document.querySelectorAll(".vk-card");
    cards.forEach(function (c) { c.setAttribute("aria-current", "false"); });
  }

  function closeDetail() {
    var panel = $("vk-detail");
    panel.classList.remove("is-active");
    $("vk-detail-content").hidden = true;
    $("vk-detail-placeholder").hidden = false;
  }

  function renderDetail(ev) {
    var content = $("vk-detail-content");
    clear(content);

    content.appendChild(el("button", {
      type: "button", "class": "vk-btn vk-btn--text vk-detail__back", text: "← Zurück zur Liste",
      onclick: function () { location.hash = listHash(); }
    }));

    var start = parseDate(ev.startAt);
    var end = parseDate(ev.endAt);

    content.appendChild(el("h2", { text: ev.title }));

    var st = statusLabel(ev.eventStatus);
    if (st) content.appendChild(el("p", { "class": "vk-tag vk-tag--status", text: st }));

    if (ev.shortDescription) {
      content.appendChild(el("p", { text: ev.shortDescription }));
    }

    // Eckdaten
    var dl = el("dl", {});
    function row(term, value) {
      if (!value) return;
      dl.appendChild(el("dt", { text: term }));
      dl.appendChild(el("dd", typeof value === "string" ? { text: value } : {}, typeof value === "string" ? [] : [value]));
    }
    if (start) {
      var when = fmtDateLong(start) + ", " + fmtTime(start) + " Uhr";
      if (end) when += " – " + (sameDay(start, end) ? fmtTime(end) + " Uhr" : fmtDateLong(end));
      row("Wann", when);
    }
    if (ev.place) {
      var loc = ev.place.name;
      if (ev.place.address) {
        var a = ev.place.address;
        var parts = [a.streetAddress, [a.postalCode, a.locality].filter(Boolean).join(" ")].filter(Boolean);
        if (parts.length) loc += " · " + parts.join(", ");
      }
      row("Wo", loc);
    }
    if (ev.virtualLocation && ev.virtualLocation.url) {
      row("Online", el("a", { href: ev.virtualLocation.url, target: "_blank", rel: "noopener noreferrer",
        text: ev.virtualLocation.name || ev.virtualLocation.url }));
    }
    row("Format", modeLabel(ev.attendanceMode));
    if (ev.categories && ev.categories.length) {
      row("Kategorie", ev.categories.map(function (c) { return c.name; }).join(", "));
    }
    content.appendChild(dl);

    // Beschreibung (sanitisiertes Rich Text vom Server) – DOM-sicher gerendert
    if (ev.description) {
      var descSection = el("div", { "class": "vk-detail__section vk-detail__desc" }, [
        el("h3", { text: "Beschreibung" })
      ]);
      renderRichText(descSection, ev.description);
      content.appendChild(descSection);
    }

    // Veranstalter / Kontakt
    if (ev.organizers && ev.organizers.length) {
      var orgSection = el("div", { "class": "vk-detail__section" }, [el("h3", { text: "Veranstalter" })]);
      ev.organizers.forEach(function (o) {
        var lines = [el("strong", { text: o.displayName })];
        if (o.email) lines.push(el("div", {}, [el("a", { href: "mailto:" + o.email, text: o.email })]));
        if (o.telephone) lines.push(el("div", { text: o.telephone }));
        if (o.url) lines.push(el("div", {}, [el("a", { href: o.url, target: "_blank", rel: "noopener noreferrer", text: o.url })]));
        orgSection.appendChild(el("p", {}, lines));
      });
      content.appendChild(orgSection);
    }

    // Angebote / Tickets
    if (ev.offers && ev.offers.length) {
      var offerSection = el("div", { "class": "vk-detail__section" }, [el("h3", { text: "Tickets & Preise" })]);
      var ul = el("ul", {});
      ev.offers.forEach(function (of) {
        var label = (of.name || "Ticket") + ": " +
          (of.price != null ? (Number(of.price) === 0 ? "kostenlos" : formatPrice(of.price)) : "");
        var li = el("li", { text: label });
        if (of.url) { li.appendChild(document.createTextNode(" ")); li.appendChild(el("a", { href: of.url, target: "_blank", rel: "noopener noreferrer", text: "→ Tickets" })); }
        ul.appendChild(li);
      });
      offerSection.appendChild(ul);
      content.appendChild(offerSection);
    }

    // Barrierefreiheit
    if (ev.place && ev.place.accessibilityNote) {
      content.appendChild(el("div", { "class": "vk-detail__section" }, [
        el("h3", { text: "Barrierefreiheit" }), el("p", { text: ev.place.accessibilityNote })
      ]));
    }

    // Schlagworte
    if (ev.keywords && ev.keywords.length) {
      var tagWrap = el("div", { "class": "vk-detail__section" }, [el("h3", { text: "Schlagworte" })]);
      var tags = el("div", { "class": "vk-card__tags" });
      ev.keywords.forEach(function (k) { tags.appendChild(el("span", { "class": "vk-tag", text: k })); });
      tagWrap.appendChild(tags);
      content.appendChild(tagWrap);
    }

    // Aktionen: Teilen, Kalender (ICS), JSON-LD
    var actions = el("div", { "class": "vk-detail__actions" });
    actions.appendChild(el("a", { "class": "vk-btn vk-btn--tonal",
      href: API + "/events/" + encodeURIComponent(ev.id) + "/jsonld", target: "_blank",
      rel: "noopener noreferrer", text: "schema.org JSON-LD" }));
    actions.appendChild(el("button", { type: "button", "class": "vk-btn vk-btn--tonal", text: "Teilen",
      onclick: function () { shareEvent(ev); } }));
    content.appendChild(actions);

    // JSON-LD zusätzlich ins DOM einbetten (für Suchmaschinen)
    injectJsonLd(ev);

    content.scrollTop = 0;
    content.focus && content.focus();
  }

  function sameDay(a, b) {
    return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  }

  // Rich Text: nur erlaubte Tags durchlassen, Rest als Text. Server sanitisiert ebenfalls.
  function renderRichText(target, html) {
    var allowed = { P: 1, BR: 1, STRONG: 1, EM: 1, UL: 1, OL: 1, LI: 1, A: 1 };
    var doc = new DOMParser().parseFromString("<div>" + html + "</div>", "text/html");
    var root = doc.body.firstChild;
    function walk(src, dest) {
      Array.prototype.forEach.call(src.childNodes, function (n) {
        if (n.nodeType === 3) { dest.appendChild(document.createTextNode(n.nodeValue)); }
        else if (n.nodeType === 1 && allowed[n.tagName]) {
          var clone = document.createElement(n.tagName.toLowerCase());
          if (n.tagName === "A") {
            var href = n.getAttribute("href") || "";
            if (/^https?:\/\//i.test(href) || href.indexOf("mailto:") === 0) {
              clone.setAttribute("href", href);
              clone.setAttribute("target", "_blank");
              clone.setAttribute("rel", "noopener noreferrer");
            }
          }
          dest.appendChild(clone);
          walk(n, clone);
        } else if (n.nodeType === 1) {
          walk(n, dest); // nicht erlaubtes Element: Inhalt übernehmen, Tag verwerfen
        }
      });
    }
    walk(root, target);
  }

  function injectJsonLd(ev) {
    var old = document.getElementById("vk-jsonld");
    if (old) old.remove();
    fetch(API + "/events/" + encodeURIComponent(ev.id) + "/jsonld", { credentials: "same-origin" })
      .then(function (r) { return r.text(); })
      .then(function (txt) {
        var s = document.createElement("script");
        s.type = "application/ld+json";
        s.id = "vk-jsonld";
        s.textContent = txt;
        document.head.appendChild(s);
      }).catch(function () {});
  }

  function shareEvent(ev) {
    var url = location.origin + location.pathname + "#/events/" + ev.id;
    if (navigator.share) {
      navigator.share({ title: ev.title, url: url }).catch(function () {});
    } else if (navigator.clipboard) {
      navigator.clipboard.writeText(url).then(function () { toast("Link kopiert"); });
    } else {
      toast(url);
    }
  }

  // ---- Toast --------------------------------------------------------------
  var toastTimer = null;
  function toast(msg) {
    var t = $("vk-toast");
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, 4000);
  }

  // ===========================================================================
  // ROUTER
  // ===========================================================================
  function handleRoute() {
    var id = currentDetailId();
    if (id) {
      openDetail(id);
      return;
    }
    // Listenansicht: Filter aus der URL uebernehmen und suchen (teilbare Links,
    // Browser zurueck/vor zwischen Suchzustaenden).
    closeDetail();
    applyHashToState();
    syncFilterInputs();
    runSearch();
  }

  // ===========================================================================
  // INIT
  // ===========================================================================
  function init() {
    var form = $("vk-search-form");
    var input = $("vk-q");
    var clearBtn = $("vk-q-clear");

    form.addEventListener("submit", function (e) { e.preventDefault(); state.q = input.value.trim(); resetAndSearch(); });
    input.addEventListener("input", function () {
      clearBtn.hidden = !input.value;
      clearTimeout(searchTimer);
      searchTimer = setTimeout(function () { state.q = input.value.trim(); resetAndSearch(); }, 250);
    });
    clearBtn.addEventListener("click", function () {
      input.value = ""; clearBtn.hidden = true; state.q = ""; resetAndSearch(); input.focus();
    });

    $("vk-sort").addEventListener("change", function (e) { state.sort = e.target.value; resetAndSearch(); });

    $("vk-filters-reset").addEventListener("click", function () {
      state = { q: state.q, from: "", to: "", category: "", place: "", placeLabel: "",
        organizer: "", organizerLabel: "", attendanceMode: "", free: false, sort: state.sort, page: 1 };
      renderFilters(); resetAndSearch();
    });

    // Mobiler Filter-Drawer
    $("vk-filters-open").addEventListener("click", function () {
      var f = $("vk-filters");
      var open = f.classList.toggle("is-open");
      this.setAttribute("aria-expanded", open ? "true" : "false");
    });

    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape") {
        $("vk-filters").classList.remove("is-open");
        if ($("vk-detail").classList.contains("is-active") && window.innerWidth < 1200) {
          location.hash = listHash();
        }
      }
    });

    window.addEventListener("hashchange", function () {
      if (internalHash) { internalHash = false; return; }  // eigenes Update -> ignorieren
      handleRoute();
    });

    // Bootstrap: erst VK-Kontext (nur Anzeige), dann Kategorien, dann Filter aus URL + Suche.
    loadContext().then(loadCategories).then(function () {
      applyHashToState();
      syncFilterInputs();
      var id = currentDetailId();
      runSearch();
      if (id) { openDetail(id); }
    });
  }

  // ---- Mandanten-/VK-Kontext (nur Anzeige) --------------------------------
  function loadContext() {
    return getJson(API + "/context").then(function (body) {
      var ctx = body.data || {};
      state.mandant = ctx.mandant;
      state.vk = ctx.vk;
      if (ctx.name) {
        $("vk-vk-name").textContent = ctx.name;
        document.title = ctx.name;
      }
    }).catch(function () { /* Kontext nur kosmetisch */ });
  }

  function loadCategories() {
    return getJson(API + "/categories").then(function (body) {
      categories = body.data || [];
      renderFilters();
    }).catch(function () { renderFilters(); });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
