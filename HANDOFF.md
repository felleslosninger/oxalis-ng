# HANDOFF – store meldinger (1–2 GB) i Oxalis-NG

Branch: `light-on-memory` (ingen kodeendringer ennå, peker på `1b81c3a4`). Sist oppdatert 2026-09-24.

## Mål

Sende og motta AS4-meldinger på ~1 GB **komprimert** (evt. opp mot 2 GB) uten at Java-prosessene trenger enorme mengder minne. **Første prioritet er mottakssiden**, som kjører i container med minne som største begrensning (Java 25, `InitialRAMPercentage=75`, `MaxRAMPercentage=75`).

Payloadene er en liten XML med ett element som inneholder kryptert binærdata i base64. gzip gir bare ~75 %, så 1 GB komprimert ≈ 1,33 GB ukomprimert.

Målte størrelsesforhold (tilfeldige binærdata): base64-XML = 1,33 × binær, gzip = 1,01 × binær (0,757 × base64). «1 GB komprimert» ≈ «1 GB binær» i testene.

**Valgt teststørrelse:** `messageSize = 700 * mib` → XML ~978,7 MB (≈ reelle filer på 1 GB), gzip ~741 MB. Under `String`-grensen (så `AdministrativeMessageInMemory` fungerer, men bruker ~4,6 GB i topp under generering – sender trenger `-Xmx6g`), og under WSS4J-terskelen. Forventet mottak: ~3,5–5 GB heap før M8, ~1,5 GB etter M8. Mottaker på `--memory=8g`. Bytt til filbasert generator før sendersiden måles.

**Terskel:** Komprimert størrelse over 1 GiB (1 073 741 824 byte) får WSS4J-bufferen til å doble seg til ~2 GiB (vedvarende 2 GiB, topp ~3 GiB per side). Testen med 1024 MiB binær gir ~1,084 GB komprimert og havner **over** terskelen. For baseline:
- `messageSize = 1000 * mib` (~1,059 GB komprimert, under terskelen) med mottaker på `--memory=8g`.
- `messageSize = 1024 * mib` (over terskelen) krever mottaker på `--memory=12g` og sender med `-Xmx6g`.

Testklienten ligger i `~/src/efm-peppol-accesspoint-testing` (`client`, `messages`). **Brukerens faktiske Jetty-server ligger i et annet repo som ikke er synlig herfra** – `efm-peppol-accesspoint-testing/server` er IKKE den som kjører. Be om kode/logg i stedet for å lese derfra. `AdministrativeMessageInMemory` feilet med `OutOfMemoryError: UTF16 String size is 1431658670` (`String.replace` av `{asic}`), og må byttes ut med en generator som strømmer til temp-fil (`AdministrativeMessageOnDisk`, `getSizeInBytes()` → `long`). Sender-klienten trenger `oxalis.http.timeout.read = 900000` i `oxalis.conf`.

## Status

- Analyse ferdig (bare hovedkode, ikke test). Ingen kode endret i oxalis-ng.
- **Runde 1 ferdig** (2026-09-24), i brukerens serverrepo:
  - M1/M2/M3/M6: `ENTRYPOINT` med `-XX:InitialRAMPercentage=70.0 -XX:MaxRAMPercentage=70.0 -XX:+UseZGC -Djava.io.tmpdir=/data/tmp -Dorg.apache.cxf.io.CachedOutputStream.OutputDirectory=/data/cxf-tmp -Dorg.apache.cxf.io.CachedOutputStream.MaxSize=2500000000`, pluss midlertidig GC-logg, NMT og heap dump ved OOM.
  - `/data` eies av app-brukeren i imaget; `main()` oppretter `java.io.tmpdir` og CXF-mappen før Oxalis starter (CXF faller ellers stille tilbake til tmpdir).
  - M4: `connector.setIdleTimeout(900_000)` i `Main`. Ingress/proxy gjelder først i produksjon.
  - M5: utsatt (én fil om gangen under testing).
  - M7: gjennomgått, OK.
- **Baseline målt** (2026-09-24, før M8): 700 MiB binær (XML ~978,7 MB, gzip ~741 MB), mottaker `--memory=8g` (heap-maks 5 736 MiB, ZGC).
  - Total tid sett fra klienten: **51,1 s** (send → kvittering).
  - Heap brukt da `ReceiptPersister` ble kalt: **3 556 MiB** (5 736 total − 2 180 ledig). Øyeblikksbilde inkl. søppel, ikke topp. Samme logglinje brukes som sammenligning etter M8.
  - Topp fra `gc.log` ikke hentet ennå.
- M8, første forsøk (`JCEMapper.setProviderId("BC")`): uendret, 3 556 MiB brukt. Forklart: WSS4J ignorerer `JCEMapper` for vedleggsdekryptering.
- M8 med `StreamingGcmProvider`, første kjøring: startloggen viste `AES/GCM/NoPadding resolves to provider SunJCE` – provideren er ikke i bruk ennå. Feilsøkes med logging av provider-rekkefølge, `getService(...)` og eksplisitt `Cipher.getInstance("AES/GCM/NoPadding", "StreamingGCM")`.
- **M8 aktiv** (2026-09-24): `StreamingGCM inserted at position 1, AES/GCM/NoPadding resolves to provider StreamingGCM`. Årsak til første feil: provider-klassen og `insertProviderAt` manglet i brukerens `Main`. `JCEMapper.setProviderId("BC")` er fjernet (ingen effekt på vedlegg, flyttet bare signatur/digest/RSA-OAEP til BC).
- M8, test med `--memory=8g`: heap brukt ved `ReceiptPersister` **2 822 MiB** (mot 3 556 i baseline, −734 MiB). Øyeblikksbilde med ZGC-søppel – ikke avgjørende.
  - Høyeste verdi etter GC i `gc.log` (`grep -oE '\)->[0-9]+M' gc.log | sort -t'>' -k2 -n | tail -1`): **2 820 MB** med M8, mot **4 358 MB** i kjøringen før provideren virket (**−1 538 MB**). Med generasjonsbasert ZGC inkluderer verdien gammelt søppel, så den er grov, men sammenlignbar mellom kjøringer med samme oppsett.
- M8, test med `--memory=4g` (heap-maks 2 868 MiB): **gikk gjennom** – `ReceiptPersister` kalt med **24 MiB ledig**. Én melding på ~1 GB passer nå i halve minnet, men nesten uten margin (WSS4J-bufferen på ~1–1,5 GiB er det som gjenstår → M9).
- 4g-kjøringen: total tid **47,8 s** (mot 50,9–51,1 s med 8g) – mindre minne gjorde det ikke tregere.
- 4g-kjøringen: **2 reelle `Allocation Stall`, 63 ms totalt.** (`grep -c "Allocation Stall"` ga 34, men det teller ZGCs oppsummeringslinjer `Allocation Stalls: 0 0 0 0`, ikke faktiske stalls. Faktiske stalls er egne linjer `Allocation Stall (tråd) X ms`.) Konklusjon: 4g fungerer uten merkbar treghet, men med nesten ingen margin (24 MiB ledig). **Anbefalt før M9: 5–6g for én melding på ~1 GB om gangen.** Etter M9 forventes 4g med god margin, trolig også 2g.
- Gjenstår for M8: (valgfritt) samme 4g-test uten `StreamingGCM` for å bekrefte OOM, og negativ test (endret byte i kryptert vedlegg skal avvises før `CustomPersister`).
- **Funn i Oxalis:** `As4CommonModule` gjør allerede `Security.setProperty("jdk.security.provider.preferred", "AES/GCM/NoPadding:BC")`, men det virker ikke: JDK 25 leser egenskapen bare ved oppstart (fra `java.security` eller `-Djava.security.properties=<fil>`). Testet: satt i kode → SunJCE, satt via egenskapsfil ved oppstart → BC. Kandidat for egen endring i oxalis-ng: erstatt linjen med registrering av `StreamingGcmProvider` (M8 innebygd i Oxalis).
- **Negativ test** (endret byte i kryptert vedlegg): `TamperAttachmentInterceptor` i testklienten (fase `USER_STREAM`, mellom WSS4J i `POST_PROTOCOL` og skriving av vedlegg i `PRE_STREAM_ENDING`), aktivert med `-Dtamper.offset=1000000`. Må registreres **etter** `new OxalisOutboundComponent()`. `As4CommonModule` lager Oxalis sin CXF-buss med algoritmesuiten, og et tidligere kall til `BusFactory.getDefaultBus()` lager en vanlig buss som blir global standard → `Algorithm suite "Basic128GCMSha256MgfSha256" is not registered`.
- **Negativ test bestått** (2026-09-24): endret byte i kryptert vedlegg → mottaker: `SoapFault` fra `WSS4JInInterceptor`, årsak `TransformException` → `AEADBadTagException: mac check in GCM failed` (BC). Stoppet i sikkerhetssteget, før `As4Provider`/`As4InboundHandler`/`CustomPersister`. Avsenderen logger `Inbound policy verification failed ... Soap Body is not SIGNED` for feilsvaret; det er vanlig Oxalis-oppførsel for sikkerhetsfeil (feil-svar signeres ikke), ikke knyttet til M8.
- **M8 FERDIG.** Oppsummert: 1 GB-melding går med `--memory=4g` (før: 8g), ~−1,5 GB i `gc.log`, ~48 s (før ~51 s), manipulert chiffertekst avvises før persistering.
- **M9 implementert** i oxalis-ng (`oxalis-ng-as4`, ikke committet ennå): `As4MarkableCachedInputStream` (kopierer til CXF `CachedOutputStream` under lesing, `mark`/`reset` spiller av fra cache), `As4RereadableDataSource`, `As4AttachmentDeserializer.makeRereadable()/closeRereadable()`, kall fra `As4LazyAttachmentCollection.add()`, opprydding også i out-fault-kjeden (`AttachmentCleanupInterceptor(Phase.SETUP)` i `As4EndpointsPublisherImpl`). Tester: `As4RereadableAttachmentTest` (7, inkl. WSS4Js ekte `AttachmentContentSignatureTransform`).
  - Bygg: oxalis-ng må bygges med JDK 21 (`JAVA_HOME=~/.sdkman/candidates/java/21.0.9-tem`) – Lombok 1.18.38 støtter ikke JDK 25.
  - Modultester: 150 OK; de 6 Jetty-testene (`SendReceiveTest`, `AS4StatusServletTest`, 4 MLS-tester) kunne ikke starte fordi port 8080 var opptatt av brukerens container. **Må kjøres på nytt med ledig port.**
  - Brukerens server, 700 MiB, `--memory=4g`: **1 072 MiB ledig** ved `ReceiptPersister` (mot 24 MiB med bare M8, +1 048 MiB ≈ WSS4J-bufferen på 1 GiB).
  - `/data/cxf-tmp`: under mottak to filer (rå kryptert ~741 MB + M9s dekrypterte kopi), **tom etter vellykket mottak**.
  - `--memory=2g` (heap ~1 433 MiB): **OOM**. Årsak (ny, tredje kopi): WSS4J `SignatureProcessor` setter `javax.xml.crypto.dsig.cacheReference=TRUE`; i Santuario `DOMReference` gjør det at `DigesterOutputStream(md, true)` legger all digest-input (hele vedlegget) i en `UnsyncByteArrayOutputStream`, og kopien (`digestInput`) lever til forespørselen er ferdig. Forklarer 1 796 MiB brukt i 4g-testen (ikke søppel, som først antatt).
  - Upstream-fiks: **WSS-727** (commit 6726da983f, 2026-09-22) – slår av `cacheReference` bare for vedleggsreferanser og kjenner dem igjen på transform-algoritmen. Kun på `master` (4.x), ikke i 3.0.6/4.0.2 og ikke på `3_0_x-fixes`.
  - Foreslått **M9b**: kopi av WSS4J 3.0.5 `SignatureProcessor` med WSS-727 som `As4SignatureProcessor`, registrert via `WSSConfig` på AS4-endepunktet (`PolicyBasedWSS4JInInterceptor` har ingen konstruktør for `wss4j.processor.map`). Forventet −0,75 til −1,5 GB → 2g realistisk. Fjernes når WSS4J med WSS-727 tas i bruk.
  - Tamper-test med 700 MiB: to temp-filer under mottak, **`/data/cxf-tmp` tom etter avvisning** – opprydding i out-fault-kjeden virker.
  - Gjenstår for M9: Jetty-testene i oxalis-ng (krever ledig port 8080), deretter commit. Forventet ~1,5 GB brukt ved `ReceiptPersister`. Sendersiden (runde 3–4) tas etter at mottakssiden er ferdig.

## Brukerens mottaksserver (eget repo, ikke i oxalis-ng)

- Egen Jetty 11-`Main` (`no.digdir.efm.oxalis.server.Main`): `QueuedThreadPool` max 500 tråder, `ServerConnector` på 8080 uten satt `idleTimeout` (Jetty-standard 30 s), `GuiceFilter` + `OxalisGuiceContextListener`.
- Registrerer BouncyCastle **sist** med vilje: BC på plass 1 ødela innlasting av PKCS12/JKS-keystore (`BadPaddingException`). Derfor gjøres M8 med en egen smal provider (`StreamingGcmProvider`, kun AES/GCM → BC) på plass 1, lagt i deres `registerBouncyCastleProvider()` – ingen Oxalis-endring nødvendig. Første forsøk med `JCEMapper.setProviderId("BC")` ga identisk resultat som baseline (3 556 MiB brukt) og må fjernes.
- M4: sett `connector.setIdleTimeout(900_000)` i deres `Main`.
- M5: **utsatt** – under testing sendes bare én fil om gangen. Må på plass før produksjon hvis M8/M9 ikke er ferdige da. Semafor-filter før `GuiceFilter`. Oxalis-sendere bruker chunked overføring (ingen `Content-Length`), så filteret må telle bytes og ta en plass i semaforen når terskelen passeres, ikke bare se på headeren.
- Egen `PersisterHandler` (`CustomPersister`) – **M7 gjennomgått, OK.** Sender strømmen til `StandardBusinessDocumentStreamParser` (`~/src/efm-peppol-accesspoint-testing/messages/.../parsers`), som bruker Woodstox 7.1.0 med `IS_COALESCING=false` og `readElementAsBinary()` i biter på 64 KB (`BinaryElementInputStream`). Testet med 256 MB heap: 1,4 mrd. base64-tegn → 37 MB heap, 2,8 mrd. (over 2³¹) → 37 MB heap. Woodstox-grensene er som standard `Integer.MAX_VALUE` for tekstlengde og `Long.MAX_VALUE` for dokumentet.
  - Gjenstår: `storeBinary()` skriver foreløpig til `nullOutputStream()` (FIXME). Ekte lagring må strømme (multipart ved blob-lagring) og skjer før AS4-kvitteringen, så opplastingstiden teller mot avsenders timeout.
  - `persist(...)` returnerer `Path.of("HelloFromPayloadPersister")` – må bli en ekte sti eller referanse i produksjon.
  - Minneloggingen bør bruke brukt minne og `MemoryPoolMXBean.getPeakUsage()`, ikke `totalMemory()`, som er konstant med `InitialRAMPercentage=75`.

## Funn – hvor minnet går

### Mottak (i dag ~4–6 GB heap per melding på 1 GB komprimert)

1. **SunJCE AES-GCM-dekryptering** (størst). WSS4J henter cipher via `Cipher.getInstance` uten provider (`KeyUtils.getCipherInstance`, `JCEMapper.getProviderId()` er null). BouncyCastle legges bare til *sist* (`As4OutboundModule`, `BCHelper`), så SunJCE brukes. SunJCE bufrer **all** chiffertekst før første byte klartekst.
   - Målt på JDK 25: 3–5× chiffertekststørrelsen i heap.
   - Hard grense: `ProviderException: SunJCE provider only supports input size up to 2147483647 bytes`.
   - BouncyCastle 1.84 strømmer: ~0 heap, også over 2,1 GB.
2. **WSS4J `AttachmentContentSignatureTransform.processAttachment`**: pakker strømmen i `BufferedInputStream` og kaller `mark(Integer.MAX_VALUE)` når strømmen ikke støtter `mark`. Hele det komprimerte vedlegget havner i heap (bufferen dobles: 1 GB → 1 GiB vedvarende, ~1,6 GB topp). Maks ~2 147 483 639 byte.
3. Alt annet strømmer eller går til disk: forket CXF-vedleggsparsing (`As4AttachmentDeserializer` → `CachedOutputStream`, temp-fil over 100 KB), `SAAJInInterceptor` cacher til disk, `As4InboundHandler` gunzipper strømmende, `DefaultPersister` kopierer strømmende. SBDH-header leses med `mark(64 KB)`.

### Sending (i dag ~3 GB med SBDH i payload, 5–8 GB uten)

1. `TransmissionRequestBuilder.savePayLoad` – `ByteStreams.toByteArray` (hele payloaden i `byte[]`).
2. `PeekingInputStream` (brukt av `TransmissionRequestFactory`) – leser alt bare for å finne SBDH-headeren.
3. SBDH-innpakking: `SbdhWrapper` / `XmlContentWrapper` → `ByteArrayOutputStream`. vefa `XMLStreamUtils.copy` gjør `writer.writeCharacters(reader.getText())` – ett stort base64-element kan bli én `String`.
4. WSS4J-signering: samme `mark(Integer.MAX_VALUE)`-problem som over, på den komprimerte strømmen fra `CompressionUtil` (`CachedOutputStream` → `FileInputStream`, som ikke støtter `mark`).
5. Kryptering (SunJCE GCM encrypt) strømmer fint. HTTP: CXF 4.0 `HttpClientHTTPConduit` med chunking = `PipedInputStream`, strømmer.

### Andre grenser

- `oxalis.http.timeout.read` = 45 s blir `HttpRequest.timeout()` og dekker **opplasting + mottakers behandling**. 1 GB krever >180 Mbit/s.
- Grensen på 2³¹−1 byte: 2,0·10⁹ byte går (med mye heap), 2 GiB stopper uten tiltakene under.
- Et mottatt base64-element på >2³¹ tegn kan ikke leses til en Java-`String` i backend.

## Plan – mottakssiden (valgt rekkefølge)

Effekt ved 1 GB komprimert. Løsbarhet 1–5 (5 = enkelt).

### Runde 1 – container og konfigurasjon

| # | Tiltak | Effekt | Omfang |
|---|---|---|---|
| M1 | Heap: allerede `MaxRAMPercentage=75`. Følg med på RSS (`-XX:NativeMemoryTracking=summary`), gå til 65–70 % ved behov | – | – |
| M2 | `-Djava.io.tmpdir` på eget volum (ikke tmpfs / `emptyDir` Memory) | Hindrer at ~1 GB cache per melding havner i RAM | ½ d |
| M3 | CXF `attachment-max-size` / `attachment-max-count` | Grense mot misbruk av disk | ½ d |
| M4 | Ingress/proxy: body-grense, `proxy-read-timeout`, `proxy-request-buffering off`, Jetty `idleTimeout` | Nødvendig | ½ d |
| M5 | Semafor-filter for store `Content-Length` (svar 503) – midlertidig | Forutsigbar heap | 1 d |
| M6 | Midlertidig `-XX:+UseZGC` (store arrayer uten krav om sammenhengende G1-regioner). Tilbake til G1 etter M8+M9 | Færre OOM-er | ½ d |
| M7 | Backend/`PersisterHandler` må strømme; aldri DOM/`getText()` på base64-elementet | Unngår ≥1,33 GB | 0–1 d |

### Runde 2 – kode

| # | Tiltak | Effekt | Omfang | Løsbarhet |
|---|---|---|---|---|
| M8 (B8) | Registrer en smal JCA-provider **først** (`Security.insertProviderAt(..., 1)`) som bare tilbyr `Cipher.AES/GCM/NoPadding` → `org.bouncycastle.jcajce.provider.symmetric.AES$GCM`. **`JCEMapper.setProviderId("BC")` virker IKKE**: WSS4J `EncryptionUtils` (~linje 320) dekrypterer vedlegg med `Cipher.getInstance(jceAlgorithm)` uten provider (bare RSA-OAEP-nøkkelen går via `KeyUtils`/`JCEMapper`). Verifisert i test: WSS4J-kallet får BC og strømmer (512 MB heap for 300 MB); AES-CBC, PBE og PKCS12 blir på SunJCE/SUN; endret tag gir `AEADBadTagException` ved slutten av strømmen. BC er like rask som SunJCE (132 vs 121 MB/s for 741 MB). Test med endret GCM-tag: må avvises **før** noe persisteres | −2 til −4 GB, fjerner grensen på 2³¹ ved dekryptering | 1–2 d | 4 |
| M9 (B4) | I `As4LazyAttachmentCollection.add()`: skriv dekryptert strøm til temp-fil og bytt til en `InputStream` med ekte `mark`/`reset` (`FileChannel`-posisjon). CXF `AttachmentCallbackHandler` legger resultatet fra dekrypteringen inn via `message.getAttachments().add(...)`, og det er vår samling. **Krever M8.** Verifiser rekkefølgen dekryptering → signatur i WSS4J | −1,07 GB vedvarende / −1,6 GB topp; +1 GB disk | 2–5 d | 3–4 |
| M10 (B5) | Krypter CXF-tempfiler (`bus.io.CachedOutputStream.CipherTransformation`) | Sikkerhet | ½ d | 5 |
| M11 | Løs opp M5/M6 | Kapasitet | ½ d | 5 |

Alternativ til M9: egen JSR-105-provider med en kopi av Attachment-Content-Signature-Transform som bufrer til fil. Dekker også sendersiden (erstatter B3). 3–5 d.

### Forventet containerstørrelse (heap 75 %, ~0,5 GB grunnforbruk)

| Etter | Heap per melding | 1 samtidig | 2 samtidige |
|---|---|---|---|
| I dag | ~4–6 GB | ~6–9 GB | ~12–17 GB |
| M8 | ~1,6 GB | ~3 GB | ~5 GB |
| M8 + M9 | noen titalls MB | ~1–1,5 GB | ~1–1,5 GB |

Disk per samtidige melding: ~2,3 GB i dag, ~3,3 GB etter M9.

## Plan – sendersiden (etter mottakssiden)

Sendersiden bruker i dag ~3 GB heap per melding på 1 GB komprimert når payloaden har SBDH, og 5–8 GB når Oxalis må pakke den inn. GCM-kryptering strømmer, så B8/M8 gir ingen gevinst her.

### Runde 3 – konfigurasjon og drift (1–2 d)

| # | Tiltak | Effekt | Omfang | Løsbarhet |
|---|---|---|---|---|
| A1 | Øk `oxalis.http.timeout.read` fra 45 s til 10–15 min, evt. regnet ut fra størrelse. Kan tas allerede i runde 1 hvis dere tester mot egen mottaker | Nødvendig, ellers feiler 1 GB | ½ d | 5 |
| A2 | Lever filene med SBDH ferdig på plass (hopper over innpakking og `getText()`) | −3 til −5 GB, slipper B2 | 0 d | 5 |
| A4s | `java.io.tmpdir` på ekte disk (gzip-temp-filen er ~1 GB) | Hindrer at temp-filen havner i RAM | ½ d | 5 |
| A5s | Begrens samtidige store sendinger (trådbasseng/kø) – midlertidig | Forutsigbar heap | ½ d | 5 |
| A7 | Bekreft `oxalis.transformer.detector=noop` | Unngår DOM på 5–10× | 0 d | 5 |

### Runde 4 – kode (5–9 d + 2–3 d test)

| # | Tiltak | Effekt | Omfang | Løsbarhet |
|---|---|---|---|---|
| B3 | `CompressionUtil` returnerer en filstrøm med ekte `mark`/`reset` (CXF `DelegatingInputStream` sender `markSupported` videre), så WSS4J ikke bufrer i heap. Utgår hvis JSR-105-alternativet ble valgt i stedet for M9 | −1,07 GB vedvarende / −1,6 GB topp, fjerner grensen på 2³¹ | 1–2 d | 4 |
| B1 | `TransmissionRequestBuilder` / `PeekingInputStream` (`TransmissionRequestFactory`): les SBDH med `mark(64 KB)`, payload i temp-fil (evt. ny `payLoad(Path)`), `getPayload()` gir filstrøm som sletter filen ved `close()` | −1,33 GB vedvarende / −2,7 GB topp, fjerner grensen på 2³¹ ukomprimert | 3–5 d | 5 |
| B2 | (bare hvis ikke A2) Strømmende SBDH-innpakking (`SbdhWrapper`, `XmlContentWrapper`) til temp-fil, og egen StAX-kopi med `getTextCharacters()` i biter i stedet for vefa `XMLStreamUtils.copy` | −3 til −5 GB, nødvendig over 2³¹ tegn | 1–2 d | 5 |
| A5s | Løs opp samtidighetsbegrensningen | Kapasitet | ½ d | 5 |

### Forventet heap per sending (1 GB komprimert)

| Etter | Med SBDH i payload | Uten SBDH |
|---|---|---|
| I dag | ~3 GB | ~5–8 GB |
| Runde 3 | ~3 GB | ~3 GB (A2) |
| B3 | ~1,6 GB | – |
| B3 + B1 | noen titalls MB | – |

Disk per sending: ~1,33 GB kildefil + ~1 GB gzip-temp-fil (+1,33 GB etter B1 hvis payloaden kopieres til temp-fil i stedet for å leses fra `Path`).

## Parallelt / valgfritt

| # | Tiltak | Merknad |
|---|---|---|
| B6 | Upstream-patch til WSS4J (fil i stedet for `mark(Integer.MAX_VALUE)`) | Kan på sikt erstatte B3 og M9. 2–3 d + ventetid |
| B7 | Bytte AS4-stakk | Anbefales ikke – phase4 m.fl. bruker også WSS4J med DOM |

## Samlet omfang

| Runde | Side | Omfang |
|---|---|---|
| 1 | Mottak – konfigurasjon (M2–M7) | 2–3 d |
| 2 | Mottak – kode (M8–M11) + test | 5–10 d |
| 3 | Sending – konfigurasjon (A1, A2, A4s, A5s, A7) | 1–2 d |
| 4 | Sending – kode (B3, B1, evt. B2) + test | 7–12 d |
| **Sum** | | **~15–27 d** |

## Beslutninger

- Mottakssiden optimaliseres først (runde 1–2), deretter sendersiden (runde 3–4).
- B4 (M9) har ingen effekt uten B8 (M8) – de hører sammen.
- `Dockerfile` i repoet brukes ikke og skal ignoreres.

## Reprodusere GCM-målingen

Kjør med `java -Xmx24g GcmTest.java <MB>`. For BouncyCastle: bytt til `Cipher.getInstance("AES/GCM/NoPadding", "BC")`, legg til `Security.addProvider(new BouncyCastleProvider())`, og kjør med `-cp ~/.m2/repository/org/bouncycastle/bcprov-jdk18on/1.84/bcprov-jdk18on-1.84.jar`.

```java
import javax.crypto.*; import javax.crypto.spec.*; import java.io.*;
public class GcmTest {
  public static void main(String[] a) throws Exception {
    long size = Long.parseLong(a[0]) * 1024L * 1024L;
    SecretKey k = KeyGenerator.getInstance("AES").generateKey(); byte[] iv = new byte[12];
    Cipher e = Cipher.getInstance("AES/GCM/NoPadding"); e.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
    InputStream src = new InputStream() { long n = 0;
      public int read() { return n++ < size ? 0 : -1; }
      public int read(byte[] b, int o, int l) { if (n >= size) return -1; int r = (int) Math.min(l, size - n); n += r; return r; } };
    File f = File.createTempFile("gcm", ".bin"); f.deleteOnExit(); byte[] buf = new byte[8192];
    try (InputStream ce = new CipherInputStream(src, e); OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
      int r; while ((r = ce.read(buf)) != -1) o.write(buf, 0, r); }
    Cipher d = Cipher.getInstance("AES/GCM/NoPadding"); d.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
    long[] consumed = {0};
    InputStream fin = new FilterInputStream(new BufferedInputStream(new FileInputStream(f))) {
      public int read(byte[] b, int o, int l) throws IOException { int r = super.read(b, o, l); if (r > 0) consumed[0] += r; return r; } };
    Runtime rt = Runtime.getRuntime(); System.gc(); long before = rt.totalMemory() - rt.freeMemory();
    new CipherInputStream(fin, d).read(buf);
    System.out.println("provider=" + d.getProvider().getName() + " consumed before first plaintext=" + consumed[0]
        + " of " + f.length() + ", heap delta MB=" + (rt.totalMemory() - rt.freeMemory() - before) / 1048576);
  }
}
```

## Nyttige referanser i tredjepartskode

- WSS4J 3.0.5: `org.apache.wss4j.dom.transform.AttachmentContentSignatureTransform#processAttachment`, `org.apache.wss4j.common.util.AttachmentUtils#setupAttachmentDecryptionStream`, `org.apache.wss4j.common.util.KeyUtils#getCipherInstance`
- CXF 4.0.11: `org.apache.cxf.ws.security.wss4j.AttachmentCallbackHandler`, `WSS4JInInterceptor` (linje ~267, handler er hardkodet), `org.apache.cxf.binding.soap.saaj.SAAJInInterceptor`, `org.apache.cxf.transport.http.HttpClientHTTPConduit`
- Kildejarer: `~/.m2/repository/org/apache/{wss4j,cxf}/...-sources.jar`
