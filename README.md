# STV ALCO Downloader

Der STV ALCO Downloader ist eine rein lesende Spring-Boot-Batchanwendung. Sie meldet sich bei
`https://stv.alco-web.de` an, durchläuft alle zugänglichen Verträge und speichert strukturierte JSON-Daten,
die ursprünglichen HTML-Seiten sowie verlinkte PDF-Dokumente in einem unveränderlichen Snapshot.

Die Anwendung stellt keinen Webserver und keine grafische Oberfläche bereit. Pro Start wird genau ein Backup
erstellt; danach beendet sich der Prozess mit einem Exit-Code.

## Voraussetzungen

- JDK 26
- Maven wird über den enthaltenen Maven Wrapper bereitgestellt
- Docker für den Betrieb auf einem Linux-Server

Spring Boot 4.1.1 unterstützt Java bis einschließlich Version 26. Auf dem Entwicklungsrechner muss deshalb in
der IDE JDK 26 als Project SDK eingestellt werden.

## Konfiguration

Die Zugangsdaten werden ausschließlich über Umgebungsvariablen übergeben. Sie sollten weder als
Kommandozeilenargumente noch in versionierten Dateien stehen.

| Variable | Pflicht | Standard | Bedeutung |
| --- | --- | --- | --- |
| `ALCO_USERNAME` | ja | – | Benutzername für ALCO-web |
| `ALCO_PASSWORD` | ja | – | Passwort für ALCO-web |
| `ALCO_OUTPUT_DIR` | nein | `./backups` | Basisordner für Snapshots |
| `ALCO_PERIOD` | nein | `all` | `all`, `current-year` oder `current-month` |
| `ALCO_BASE_URL` | nein | `https://stv.alco-web.de` | Basis-URL der Installation |
| `ALCO_REQUEST_DELAY` | nein | `500ms` | Mindestabstand zwischen Requests |

Nicht geheime Werte können auch als Spring-Boot-Argument übergeben werden:

```bash
java -jar target/stv-alco-downloader-1.0.0-SNAPSHOT.jar --alco.period=current-year
```

Die Zugangsdaten bleiben dabei weiterhin in `ALCO_USERNAME` und `ALCO_PASSWORD`.

## Zeitraum-Modi

- `all` lädt alle dynamisch angebotenen Abrechnungsjahre und navigiert Kontoauszüge und WEG-Salden bis zum
  jeweils ältesten erreichbaren Zeitraum.
- `current-year` lädt das aktuelle Jahr. Ist eine Jahresabrechnung noch nicht vorhanden, wird der neueste
  angebotene Jahresstand gesichert.
- `current-month` filtert datierte Buchungen und deren Dokumente auf den aktuellen Monat. Übersichten, die
  ALCO-web nur jahresweise anbietet, werden vollständig mit der Kennzeichnung `YEAR` gespeichert.

Stammdaten, Vertragsdaten und zeitunabhängige Dokumente werden in jedem Modus gesichert.

## Lokale Ausführung und IDE

Projekt bauen und testen:

```bash
./mvnw clean verify
```

Unter Windows:

```powershell
.\mvnw.cmd clean verify
```

Für einen Start aus der IDE wird `de.agiehl.StvAlcoDownloaderApplication` ausgeführt. In der Run Configuration
müssen mindestens `ALCO_USERNAME` und `ALCO_PASSWORD` gesetzt werden. Optional kann dort auch ein absoluter
Pfad für `ALCO_OUTPUT_DIR` hinterlegt werden.

## Ausgabeformat

Jeder Lauf erzeugt unterhalb von `ALCO_OUTPUT_DIR` einen eigenen UTC-Zeitstempel-Ordner:

```text
backups/
└── 20260922-201530-123Z/
    ├── manifest.json
    ├── data/
    │   ├── contracts.json
    │   ├── documents.json
    │   └── contracts/<id>/*.json
    ├── raw/
    │   └── contracts/<id>/*.html
    └── attachments/
        └── <sha256>.pdf
```

`manifest.json` enthält den Status `RUNNING`, `COMPLETE` oder `FAILED`. Fehlgeschlagene Läufe bleiben zu
Diagnosezwecken erhalten. PDFs werden anhand ihrer ALCO-Dokument-ID nicht mehrfach geladen und anhand der
SHA-256-Prüfsumme nicht mehrfach gespeichert.

Die Dateien enthalten personenbezogene und finanzielle Daten. Der Backup-Ordner muss entsprechend geschützt
und in eine vorhandene Sicherungsstrategie aufgenommen werden.

## Docker-Image bauen

```bash
docker build -t stv-alco-downloader:latest .
```

Das Multi-Stage-Image verwendet Java 26. Der Prozess läuft als nicht privilegierter Benutzer und schreibt nur
in das eingebundene Verzeichnis `/data`.

Eine Credential-Datei kann anhand von `stv-alco.env.example` angelegt werden:

```dotenv
ALCO_USERNAME=mein-benutzer
ALCO_PASSWORD=mein-passwort
ALCO_PERIOD=all
ALCO_OUTPUT_DIR=/data
ALCO_REQUEST_DELAY=500ms
```

Auf dem Ubuntu-Server sollte sie außerhalb des Projekts liegen und nur für den Besitzer lesbar sein:

```bash
install -m 600 stv-alco.env /opt/stv-alco-downloader/stv-alco.env
mkdir -p /srv/stv-alco-backups
```

### Empfohlener Einmal-Lauf

```bash
docker run --rm \
  --name stv-alco-downloader \
  --env-file /opt/stv-alco-downloader/stv-alco.env \
  -v /srv/stv-alco-backups:/data \
  stv-alco-downloader:latest
```

`/srv/stv-alco-backups` ist der persistente Ausgabeordner auf dem Host. `/data` ist der dazugehörige Ordner im
Container.

### Hintergrundstart mit veröffentlichtem Image

`IMAGE` muss durch den Namen des veröffentlichten Images ersetzt werden, beispielsweise
`registry.example.org/stv-alco-downloader:latest`.

```bash
IMAGE=registry.example.org/stv-alco-downloader:latest

docker rm -f stv-alco-downloader 2>/dev/null

docker run -d \
  --name stv-alco-downloader \
  --pull=always \
  -p 8089:8080 \
  --env-file /opt/stv-alco-downloader/stv-alco.env \
  -v /srv/stv-alco-backups:/data \
  "$IMAGE"
```

`8089` ist der Port auf dem Host, `8080` der reservierte Container-Port. Die aktuelle Batchanwendung öffnet
keinen HTTP-Port; die Zuordnung ist für das vorgegebene Docker-Schema reserviert. Der Container beendet sich
automatisch, sobald der Crawl abgeschlossen ist. Status und Exit-Code können danach mit `docker ps -a` und
`docker inspect` geprüft werden.

## Automatisierung mit Cron

Die Planung erfolgt auf dem Host. Beispiel für einen Lauf täglich um 03:15 Uhr:

```cron
15 3 * * * docker run --rm --name stv-alco-downloader --env-file /opt/stv-alco-downloader/stv-alco.env -v /srv/stv-alco-backups:/data stv-alco-downloader:latest >> /var/log/stv-alco-downloader.log 2>&1
```

## Exit-Codes

| Code | Bedeutung |
| --- | --- |
| `0` | Backup vollständig erstellt |
| `2` | Konfiguration oder Anmeldung fehlgeschlagen |
| `3` | Crawl unvollständig oder Remote-Fehler |
| `4` | Backup konnte nicht geschrieben werden |

## Sicherheit und Grenzen

- Der HTTP-Client akzeptiert POST ausschließlich für `index.php` und `obj-abrechnung.php`.
- Nachrichtenversand, Stammdatenänderungen, Passwortänderungen und Logout-Endpunkte sind technisch blockiert.
- Wegen der serverseitigen Session werden Verträge und Zeiträume strikt nacheinander verarbeitet.
- Bei einem Sessionablauf wird der komplette Crawl höchstens einmal neu gestartet.
- Das echte Portal wird durch die normalen Tests nicht angesprochen.

Ein expliziter, rein lesender Live-Smoke-Test kann bei gesetzten Zugangsdaten gestartet werden:

```bash
./mvnw -Plive -Djava.version=26 test
```
