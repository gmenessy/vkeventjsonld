#!/usr/bin/env bash
# End-to-End-Smoke-Test gegen eine laufende VK-Instanz (Demo-/H2-Daten).
# Aufruf: ci/container-smoke.sh <BASE-URL>   z. B. http://localhost:8080/vk
# Deckt ab: Shell/CSP-Nonce, Suche, Detail, JSON-LD, NL-Suche, Login/CSRF, Multipart-Upload,
# Selbsteintrag mit allen Feldern, Redaktions-Workflow, Versionen/Restore, Audit, GenAI, CSV-Export.
B="${1:?BASE-URL fehlt, z. B. http://localhost:8080/vk}"
J=$(mktemp); SHELLF=$(mktemp); PNGF=$(mktemp --suffix=.png); PASS=0; FAIL=0
ok()   { echo "  ok    $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $1  -> $2"; FAIL=$((FAIL+1)); }
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

# 1 Shell + CSP-Nonce (Header == HTML)
H=$(curl -s -D - "$B/" -o $SHELLF)
HN=$(echo "$H" | grep -i '^content-security-policy' | grep -oE "nonce-[A-Za-z0-9_-]+" | head -1 | cut -d- -f2-)
BN=$(grep -oE 'nonce="[^"]+"' $SHELLF | head -1 | cut -d'"' -f2)
[ -n "$HN" ] && [ "$HN" = "$BN" ] && ok "Shell + CSP-Nonce (Header == HTML)" || bad "Shell/Nonce" "header=$HN html=$BN"

# 2 Kontext + Suche + Detail + JSON-LD (Content-Type mit charset -> Servlet-6.1-Pfad)
c=$(code "$B/api/context"); [ "$c" = 200 ] && ok "GET /api/context" || bad "/api/context" "$c"
S=$(curl -s "$B/api/events?size=3&q=konzert")
T=$(echo "$S" | jget "d['meta']['total']"); EID=$(echo "$S" | jget "d['data'][0]['id']")
[ -n "$T" ] && [ "$T" -gt 0 ] 2>/dev/null && ok "Suche 'konzert' ($T Treffer)" || bad "Suche" "$(echo "$S" | head -c 200)"
c=$(code "$B/api/events/$EID"); [ "$c" = 200 ] && ok "Detail" || bad "Detail" "$c"
c=$(code "$B/api/events/$EID/jsonld"); [ "$c" = 200 ] && ok "JSON-LD (application/ld+json;charset)" || bad "JSON-LD" "$c"
c=$(code "$B/api/search/parse?q=kostenlose%20konzerte%20am%20wochenende"); [ "$c" = 200 ] && ok "NL-Suche" || bad "NL-Suche" "$c"

# 3 Login + CSRF
TOK=$(curl -s -b $J -c $J -H 'Content-Type: application/json' \
  -d '{"email":"redaktion@vk.example","password":"redaktion"}' "$B/api/auth/login" | jget "d['data']['csrfToken']")
[ -n "$TOK" ] && ok "Login + CSRF-Token" || bad "Login" "kein Token"
c=$(code -b $J -H 'Content-Type: application/json' -d '{}' "$B/api/me/events"); [ "$c" = 403 ] && ok "Schreiben ohne CSRF -> 403" || bad "CSRF-Schutz" "$c"

# 4 Multipart-Upload (Servlet getParts) + Auslieferung
printf 'PNGSMOKE' > $PNGF
URL=$(curl -s -b $J -H "X-CSRF-Token: $TOK" -F "file=@$PNGF;type=image/png" "$B/api/me/uploads" | jget "d['data']['url']")
[ -n "$URL" ] && ok "Upload -> $URL" || bad "Upload" "keine URL"
c=$(code "$URL"); [ "$c" = 200 ] && ok "Upload-Auslieferung" || bad "Upload-Auslieferung" "$c"

# 5 Selbsteintrag mit allen Feldern -> Workflow -> Version -> Audit
BODY='{"title":"Smoke Plattform-Test","startAt":"2027-03-01T19:00","doorTime":"2027-03-01T18:30","durationIso":"PT2H",
"attendanceMode":"ONLINE","virtualUrl":"https://meet.example.org/x","category":"musik","organizerName":"Smoke Org",
"offers":[{"name":"Std","price":"10"}],"performers":[{"displayName":"Band"}],"sponsors":[{"displayName":"Sponsor"}],
"images":[{"url":"'"$URL"'","altText":"Bühne"}],"documents":[{"url":"https://cdn.example.org/p.pdf","fileName":"P.pdf"}]}'
PID=$(curl -s -b $J -H 'Content-Type: application/json' -H "X-CSRF-Token: $TOK" -d "$BODY" "$B/api/me/events" | jget "d['data']['id']")
[ -n "$PID" ] && ok "Event anlegen (alle Felder)" || bad "Event anlegen" "keine ID"
for a in "me/events/$PID/submit" "admin/events/$PID/approve" "admin/events/$PID/publish"; do
  c=$(code -b $J -X POST -H "X-CSRF-Token: $TOK" "$B/api/$a"); [ "$c" = 200 ] && ok "POST $a" || bad "POST $a" "$c"
done
V=$(curl -s -b $J "$B/api/admin/events/$PID/versions" | jget "len(d['data'])"); [ "$V" = 1 ] && ok "Versionen (1)" || bad "Versionen" "$V"
c=$(code -b $J -X POST -H "X-CSRF-Token: $TOK" "$B/api/admin/events/$PID/versions/1/restore"); [ "$c" = 200 ] && ok "Version wiederherstellen" || bad "Restore" "$c"
A=$(curl -s -b $J "$B/api/admin/audit?limit=5" | jget "d['data'][0]['action']"); [ -n "$A" ] && ok "Audit-Trail ($A)" || bad "Audit" "leer"
D=$(curl -s "$B/api/events/$PID" | jget "len(d['data']['images'])"); [ "$D" = 1 ] && ok "Öffentliches Detail mit Bild" || bad "Detail Bild" "$D"

# 6 GenAI-Assistenz + CSV-Export
c=$(code -b $J -H 'Content-Type: application/json' -H "X-CSRF-Token: $TOK" -d '{"text":"Ein Test, z. B. ca. 10 Euro."}' "$B/api/me/assist/simplify")
[ "$c" = 200 ] && ok "GenAI Einfache Sprache" || bad "GenAI" "$c"
CT=$(curl -s -b $J -o /dev/null -w "%{http_code} %{content_type}" "$B/api/admin/export/events?format=csv")
[[ "$CT" == 200* ]] && ok "CSV-Export ($CT)" || bad "CSV-Export" "$CT"

echo "== Ergebnis: $PASS ok, $FAIL fehlgeschlagen"; rm -f "$J" "$SHELLF" "$PNGF"; [ $FAIL = 0 ]
