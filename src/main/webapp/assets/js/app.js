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
  var lastFocusedCardId = null;    // fuer Fokus-Restore beim Schliessen der Detailansicht

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
    // Schnellauswahl-Presets (häufigste Nutzerabsicht)
    var presets = el("div", { "class": "vk-chip-row", role: "group", "aria-label": "Zeitraum-Schnellauswahl" });
    [["today", "Heute"], ["weekend", "Wochenende"], ["month", "Diesen Monat"]].forEach(function (p) {
      presets.appendChild(el("button", { type: "button", "class": "vk-chip", text: p[1],
        onclick: function () { applyDatePreset(p[0]); } }));
    });
    dateGroup.appendChild(presets);
    body.appendChild(dateGroup);

    // Kategorien
    var catGroup = el("div", { "class": "vk-filter-group" }, [
      el("p", { "class": "vk-filter-group__label", id: "vk-cat-label", text: "Kategorie" })
    ]);
    var catTree = renderCategoryTree(categories);
    catTree.setAttribute("role", "group");
    catTree.setAttribute("aria-labelledby", "vk-cat-label");
    catGroup.appendChild(catTree);
    body.appendChild(catGroup);

    // Anwesenheitsmodus
    var modeGroup = el("div", { "class": "vk-filter-group" }, [
      el("p", { "class": "vk-filter-group__label", id: "vk-mode-label", text: "Format" })
    ]);
    var chipRow = el("div", { "class": "vk-chip-row", role: "group", "aria-labelledby": "vk-mode-label" });
    [["", "Alle"], ["OFFLINE", "Vor Ort"], ["ONLINE", "Online"], ["MIXED", "Hybrid"]].forEach(function (m) {
      chipRow.appendChild(el("button", {
        type: "button", "class": "vk-chip", "data-mode": m[0], "aria-pressed": state.attendanceMode === m[0] ? "true" : "false",
        // In-place aktualisieren statt Re-Render -> Tastaturfokus bleibt erhalten (WCAG 2.4.3).
        text: m[1], onclick: function () { state.attendanceMode = m[0]; updatePressedStates(); resetAndSearch(); }
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
      var btn = el("button", {
        type: "button", "class": "vk-cat-btn", "data-cat": cat.id,
        "aria-pressed": state.category === cat.id ? "true" : "false",
        // In-place-Toggle ohne Re-Render -> Fokus bleibt auf dem Button (WCAG 2.4.3).
        onclick: function () {
          state.category = (state.category === cat.id) ? "" : cat.id;
          updatePressedStates();
          resetAndSearch();
        }
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

  /** Aktualisiert die aria-pressed-Zustaende der Filter ohne DOM-Neuaufbau. */
  function updatePressedStates() {
    document.querySelectorAll("#vk-filters-body [data-cat]").forEach(function (b) {
      b.setAttribute("aria-pressed", b.getAttribute("data-cat") === state.category ? "true" : "false");
    });
    document.querySelectorAll("#vk-filters-body [data-mode]").forEach(function (b) {
      b.setAttribute("aria-pressed", b.getAttribute("data-mode") === state.attendanceMode ? "true" : "false");
    });
  }

  function isoDay(d) {
    return d.getFullYear() + "-" +
      String(d.getMonth() + 1).padStart(2, "0") + "-" +
      String(d.getDate()).padStart(2, "0");
  }

  /** Setzt state.from/to anhand eines Presets und löst die Suche aus. */
  function applyDatePreset(kind) {
    var now = new Date();
    var from = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    var to = new Date(from);
    if (kind === "today") {
      // from == to == heute
    } else if (kind === "weekend") {
      // nächster (oder heutiger) Samstag bis Sonntag
      var day = from.getDay(); // 0=So..6=Sa
      var daysToSat = (6 - day + 7) % 7;
      from.setDate(from.getDate() + daysToSat);
      to = new Date(from);
      to.setDate(from.getDate() + 1);
    } else if (kind === "month") {
      to = new Date(now.getFullYear(), now.getMonth() + 1, 0); // letzter Tag des Monats
    }
    state.from = isoDay(from);
    state.to = isoDay(to);
    var fromEl = $("vk-from");
    var toEl = $("vk-to");
    if (fromEl) fromEl.value = state.from;
    if (toEl) toEl.value = state.to;
    resetAndSearch();
  }

  /**
   * Autocomplete nach ARIA-1.2-Combobox-Pattern (Listbox-Popup):
   * Pfeiltasten navigieren, Enter wählt, Escape schließt, aria-activedescendant
   * markiert die aktive Option. Maus- und Tastaturbedienung gleichwertig.
   */
  function renderAutocomplete(label, idBase, endpoint, currentLabel, onSelect) {
    var listId = idBase + "-list";
    var group = el("div", { "class": "vk-filter-group" });
    var field = el("div", { "class": "vk-field vk-combo" });
    field.appendChild(el("label", { "for": idBase, text: label }));
    var input = el("input", { "class": "vk-input", id: idBase, type: "text",
      autocomplete: "off", value: currentLabel || "", placeholder: "Tippen zum Suchen …",
      role: "combobox", "aria-expanded": "false", "aria-controls": listId,
      "aria-autocomplete": "list" });
    var list = el("ul", { "class": "vk-combo__list", id: listId, role: "listbox", hidden: "hidden" });

    var items = [];        // aktuelle Vorschläge
    var activeIndex = -1;  // aktive Option (Tastatur)
    var timer = null;

    function close() {
      list.hidden = true;
      clear(list);
      items = [];
      activeIndex = -1;
      input.setAttribute("aria-expanded", "false");
      input.removeAttribute("aria-activedescendant");
    }

    function setActive(idx) {
      var options = list.children;
      for (var i = 0; i < options.length; i++) {
        var sel = (i === idx);
        options[i].setAttribute("aria-selected", sel ? "true" : "false");
        options[i].classList.toggle("is-active", sel);
      }
      activeIndex = idx;
      if (idx >= 0 && options[idx]) {
        input.setAttribute("aria-activedescendant", options[idx].id);
        options[idx].scrollIntoView({ block: "nearest" });
      } else {
        input.removeAttribute("aria-activedescendant");
      }
    }

    function choose(idx) {
      var item = items[idx];
      if (!item) return;
      input.value = item.name;
      close();
      onSelect(item);
    }

    function renderOptions(data) {
      clear(list);
      items = data || [];
      if (!items.length) { close(); return; }
      items.forEach(function (item, i) {
        var text = item.name + (item.locality ? " · " + item.locality : "");
        var opt = el("li", { id: listId + "-opt-" + i, role: "option",
          "class": "vk-option", "aria-selected": "false", text: text });
        opt.addEventListener("mousedown", function (e) { e.preventDefault(); }); // Blur verhindern
        opt.addEventListener("click", function () { choose(i); });
        list.appendChild(opt);
      });
      list.hidden = false;
      input.setAttribute("aria-expanded", "true");
      setActive(-1);
    }

    input.addEventListener("input", function () {
      var val = input.value.trim();
      if (!val) { close(); onSelect(null); return; }
      clearTimeout(timer);
      timer = setTimeout(function () {
        getJson(endpoint, { q: val }).then(function (body) { renderOptions(body.data); })
          .catch(function () { close(); });
      }, 250);
    });

    input.addEventListener("keydown", function (e) {
      var open = !list.hidden && items.length > 0;
      if (e.key === "ArrowDown") {
        e.preventDefault();
        if (open) { setActive((activeIndex + 1) % items.length); }
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        if (open) { setActive((activeIndex - 1 + items.length) % items.length); }
      } else if (e.key === "Enter") {
        if (open && activeIndex >= 0) { e.preventDefault(); choose(activeIndex); }
      } else if (e.key === "Escape") {
        if (open) { e.stopPropagation(); close(); }
      } else if (e.key === "Home" && open) {
        e.preventDefault(); setActive(0);
      } else if (e.key === "End" && open) {
        e.preventDefault(); setActive(items.length - 1);
      }
    });

    input.addEventListener("blur", function () { setTimeout(close, 150); });

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
    renderActiveFilters();
    showSkeletons();
    $("vk-results").setAttribute("aria-busy", "true");
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
        $("vk-results").setAttribute("aria-busy", "false");
      })
      .catch(function (err) {
        if (err.name === "AbortError") return; // veraltete Anfrage, ignorieren
        $("vk-result-count").textContent = "Fehler bei der Suche";
        $("vk-results").setAttribute("aria-busy", "false");
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
      type: "button", "class": "vk-card", "data-event-id": ev.id,
      "aria-current": currentId === ev.id ? "true" : "false",
      "aria-label": ev.title + ", " + (start ? fmtDateLong(start) : ""),
      onclick: function () { lastFocusedCardId = ev.id; location.hash = "#/events/" + ev.id; }
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

  /**
   * GenAI-Quick-Win: freie Eingabe serverseitig in strukturierte Filter umwandeln
   * (z. B. „kostenlose Konzerte am Wochenende"). Bei Fehlern Fallback auf Keyword-Suche.
   */
  function interpretQuery() {
    var raw = $("vk-q").value.trim();
    if (!raw) { $("vk-q").focus(); return; }
    getJson(API + "/search/parse", { q: raw }).then(function (body) {
      var f = body.data || {};
      state.q = f.q || "";
      state.from = f.from || "";
      state.to = f.to || "";
      state.category = f.category || "";
      state.attendanceMode = f.attendanceMode || "";
      state.free = f.free === true;
      $("vk-q").value = state.q;
      $("vk-q-clear").hidden = !state.q;
      if (state.q && state.sort === "date") state.sort = "relevance";
      else if (!state.q && state.sort === "relevance") state.sort = "date";
      $("vk-sort").value = state.sort;
      renderFilters();
      resetAndSearch();
      toast("Eingabe in Filter umgewandelt");
    }).catch(function () {
      setQuery(raw); resetAndSearch();   // Fallback: normale Keyword-Suche
    });
  }

  /** Setzt den Suchbegriff und schaltet die Sortierung sinnvoll um (Relevanz bei Suche). */
  function setQuery(value) {
    state.q = value;
    if (value && state.sort === "date") { state.sort = "relevance"; $("vk-sort").value = "relevance"; }
    else if (!value && state.sort === "relevance") { state.sort = "date"; $("vk-sort").value = "date"; }
  }

  // ---- Aktive-Filter-Leiste (entfernbare Chips) ---------------------------
  function findCategoryName(slug, cats) {
    cats = cats || categories;
    for (var i = 0; i < cats.length; i++) {
      if (cats[i].id === slug) return cats[i].name;
      if (cats[i].children && cats[i].children.length) {
        var found = findCategoryName(slug, cats[i].children);
        if (found) return found;
      }
    }
    return slug;
  }

  function removeFilter(kind) {
    if (kind === "q") { state.q = ""; $("vk-q").value = ""; $("vk-q-clear").hidden = true; setQuery(""); }
    else if (kind === "category") state.category = "";
    else if (kind === "place") { state.place = ""; state.placeLabel = ""; }
    else if (kind === "organizer") { state.organizer = ""; state.organizerLabel = ""; }
    else if (kind === "attendanceMode") state.attendanceMode = "";
    else if (kind === "free") state.free = false;
    else if (kind === "dates") { state.from = ""; state.to = ""; }
    renderFilters();
    resetAndSearch();
    $("vk-results").focus();
  }

  function renderActiveFilters() {
    var bar = $("vk-active-filters");
    if (!bar) return;
    clear(bar);
    var chips = [];
    if (state.q) chips.push(["q", "Suche: " + state.q]);
    if (state.category) chips.push(["category", findCategoryName(state.category)]);
    if (state.place) chips.push(["place", state.placeLabel || "Ort"]);
    if (state.organizer) chips.push(["organizer", state.organizerLabel || "Veranstalter"]);
    if (state.attendanceMode) chips.push(["attendanceMode", modeLabel(state.attendanceMode)]);
    if (state.free) chips.push(["free", "Kostenlos"]);
    if (state.from || state.to) chips.push(["dates", (state.from || "…") + " – " + (state.to || "…")]);

    chips.forEach(function (c) {
      bar.appendChild(el("button", {
        type: "button", "class": "vk-active-chip",
        "aria-label": "Filter entfernen: " + c[1],
        onclick: function () { removeFilter(c[0]); }
      }, [el("span", { text: c[1] }), el("span", { "class": "vk-active-chip__x", "aria-hidden": "true", text: "×" })]));
    });
    if (chips.length > 1) {
      bar.appendChild(el("button", { type: "button", "class": "vk-btn vk-btn--text", text: "Alle zurücksetzen",
        onclick: function () { $("vk-filters-reset").click(); } }));
    }
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
    document.querySelectorAll(".vk-card").forEach(function (c) {
      c.setAttribute("aria-current", c.getAttribute("data-event-id") === id ? "true" : "false");
    });
  }

  function closeDetail() {
    var panel = $("vk-detail");
    var wasActive = panel.classList.contains("is-active");
    panel.classList.remove("is-active");
    $("vk-detail-content").hidden = true;
    $("vk-detail-placeholder").hidden = false;
    document.querySelectorAll('.vk-card[aria-current="true"]').forEach(function (c) {
      c.setAttribute("aria-current", "false");
    });
    // Fokus zur ausloesenden Karte zuruecksetzen (WCAG 2.4.3), sonst zur Ergebnisliste.
    if (wasActive) {
      var card = lastFocusedCardId
        ? document.querySelector('.vk-card[data-event-id="' + cssEscape(lastFocusedCardId) + '"]')
        : null;
      if (card) {
        card.focus();
      } else {
        $("vk-results").focus();
      }
      lastFocusedCardId = null;
    }
  }

  function cssEscape(s) {
    return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/["\\]/g, "\\$&");
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

    var heading = el("h2", { id: "vk-detail-heading", tabindex: "-1", text: ev.title });
    content.appendChild(heading);

    var st = statusLabel(ev.eventStatus);
    if (st) content.appendChild(el("p", { "class": "vk-tag vk-tag--status", text: st }));

    if (ev.shortDescription) {
      content.appendChild(el("p", { text: ev.shortDescription }));
    }

    // Bilder mit fester aspect-ratio -> kein Layout-Shift beim Nachladen (CLS)
    if (ev.images && ev.images.length) {
      ev.images.forEach(function (img) {
        if (!img.url) return;
        var fig = el("figure", { "class": "vk-detail__media" }, [
          el("img", { src: img.url, alt: img.altText || "", loading: "lazy", decoding: "async" })
        ]);
        if (img.copyrightText) {
          fig.appendChild(el("figcaption", { text: "© " + img.copyrightText }));
        }
        content.appendChild(fig);
      });
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
    // Fokus auf die Überschrift -> Screenreader kündigt die geöffnete Veranstaltung an.
    heading.focus();
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

  // JSON-LD direkt aus den bereits geladenen Detaildaten bauen – kein zweiter
  // Request mehr (besseres INP). Der Server bleibt für /jsonld die kanonische Quelle.
  function injectJsonLd(ev) {
    var old = document.getElementById("vk-jsonld");
    if (old) old.remove();
    var ld = { "@context": "https://schema.org", "@type": ev.schemaType || "Event",
      "@id": location.origin + location.pathname + "#/events/" + ev.id, name: ev.title };
    if (ev.shortDescription || ev.description) ld.description = ev.shortDescription || ev.description;
    if (ev.startAt) ld.startDate = ev.startAt;
    if (ev.endAt) ld.endDate = ev.endAt;
    if (ev.eventStatus) ld.eventStatus = "https://schema.org/" + ev.eventStatus;
    ld.eventAttendanceMode = "https://schema.org/" +
      (ev.attendanceMode === "ONLINE" ? "OnlineEventAttendanceMode"
        : ev.attendanceMode === "MIXED" ? "MixedEventAttendanceMode" : "OfflineEventAttendanceMode");
    ld.isAccessibleForFree = !!ev.isAccessibleForFree;
    if (ev.place) {
      var place = { "@type": "Place", name: ev.place.name };
      if (ev.place.address) {
        var a = ev.place.address;
        place.address = { "@type": "PostalAddress", streetAddress: a.streetAddress,
          postalCode: a.postalCode, addressLocality: a.locality, addressRegion: a.region,
          addressCountry: a.countryCode };
      }
      ld.location = place;
    } else if (ev.virtualLocation) {
      ld.location = { "@type": "VirtualLocation", name: ev.virtualLocation.name, url: ev.virtualLocation.url };
    }
    if (ev.organizers && ev.organizers.length) {
      ld.organizer = ev.organizers.map(function (o) {
        return { "@type": o.type === "PERSON" ? "Person" : "Organization", name: o.displayName, url: o.url };
      });
    }
    if (ev.offers && ev.offers.length) {
      ld.offers = ev.offers.map(function (of) {
        return { "@type": "Offer", name: of.name,
          price: of.price != null ? String(of.price) : undefined, priceCurrency: of.priceCurrency, url: of.url };
      });
    }
    if (ev.images && ev.images.length) { ld.image = ev.images.map(function (i) { return i.url; }); }
    if (ev.keywords && ev.keywords.length) { ld.keywords = ev.keywords.join(", "); }
    if (ev.canonicalUrl) ld.url = ev.canonicalUrl;

    var s = document.createElement("script");
    s.type = "application/ld+json";
    s.id = "vk-jsonld";
    s.textContent = JSON.stringify(ld);
    document.head.appendChild(s);
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

    form.addEventListener("submit", function (e) { e.preventDefault(); setQuery(input.value.trim()); resetAndSearch(); });
    input.addEventListener("input", function () {
      clearBtn.hidden = !input.value;
      clearTimeout(searchTimer);
      searchTimer = setTimeout(function () { setQuery(input.value.trim()); resetAndSearch(); }, 250);
    });
    clearBtn.addEventListener("click", function () {
      input.value = ""; clearBtn.hidden = true; setQuery(""); resetAndSearch(); input.focus();
    });

    $("vk-sort").addEventListener("change", function (e) { state.sort = e.target.value; resetAndSearch(); });

    $("vk-q-interpret").addEventListener("click", interpretQuery);

    $("vk-filters-reset").addEventListener("click", function () {
      state = { mandant: state.mandant, vk: state.vk, q: state.q, from: "", to: "", category: "",
        place: "", placeLabel: "", organizer: "", organizerLabel: "", attendanceMode: "",
        free: false, sort: state.sort, page: 1 };
      renderFilters();
      resetAndSearch();
      this.focus(); // expliziter Reset -> Fokus bleibt auf dem Button
    });

    // Mobiler Filter-Drawer (Dialog mit Fokus-Trap)
    $("vk-filters-open").addEventListener("click", openFiltersDrawer);
    $("vk-filters-close").addEventListener("click", closeFiltersDrawer);

    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape") {
        if ($("vk-filters").classList.contains("is-open")) {
          closeFiltersDrawer();
        } else if ($("vk-detail").classList.contains("is-active") && window.innerWidth < 1200) {
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

  // ---- Mobiler Filter-Drawer (Dialog) -------------------------------------
  var drawerOpener = null;
  var drawerTrap = null;

  function focusable(container) {
    return Array.prototype.slice.call(container.querySelectorAll(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )).filter(function (n) { return n.offsetParent !== null; });
  }

  function openFiltersDrawer() {
    var f = $("vk-filters");
    drawerOpener = document.activeElement;
    f.classList.add("is-open");
    f.setAttribute("role", "dialog");
    f.setAttribute("aria-modal", "true");
    $("vk-filters-open").setAttribute("aria-expanded", "true");

    drawerTrap = function (e) {
      if (e.key !== "Tab") return;
      var f2 = focusable(f);
      if (!f2.length) return;
      var first = f2[0], last = f2[f2.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    f.addEventListener("keydown", drawerTrap);
    $("vk-filters-close").focus();
  }

  function closeFiltersDrawer() {
    var f = $("vk-filters");
    if (!f.classList.contains("is-open")) return;
    f.classList.remove("is-open");
    f.removeAttribute("role");
    f.removeAttribute("aria-modal");
    if (drawerTrap) { f.removeEventListener("keydown", drawerTrap); drawerTrap = null; }
    $("vk-filters-open").setAttribute("aria-expanded", "false");
    if (drawerOpener && drawerOpener.focus) { drawerOpener.focus(); }
    drawerOpener = null;
  }

  // ---- Mandanten-/VK-Kontext (nur Anzeige) --------------------------------
  function loadContext() {
    return getJson(API + "/context").then(function (body) {
      var ctx = body.data || {};
      state.mandant = ctx.mandant;
      state.vk = ctx.vk;
      if (ctx.name) {
        $("vk-vk-name").textContent = ctx.name;
        $("vk-page-title").textContent = ctx.name;
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
