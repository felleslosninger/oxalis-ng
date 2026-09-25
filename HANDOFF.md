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

Brukerens Jetty-server (`server/`), testklient (`client/`) og meldingsgenerator (`messages/`) ligger i `~/src/efm-peppol-accesspoint-testing`, gren **`new-work`** (commit 466eaf3: `StreamingGcmProvider`, `idleTimeout`, `verifyCryptoSetup`, `TamperAttachmentInterceptor`; bare `DockerfileLocal` er oppdatert). Sjekk gren og siste commit (lesende git) før du resonnerer ut fra filene der – tidligere lå ikke brukerens siste endringer i den utsjekkede koden. `AdministrativeMessageInMemory` feilet med `OutOfMemoryError: UTF16 String size is 1431658670` (`String.replace` av `{asic}`), og må byttes ut med en generator som strømmer til temp-fil (`AdministrativeMessageOnDisk`, `getSizeInBytes()` → `long`). Sender-klienten trenger `oxalis.http.timeout.read = 900000` i `oxalis.conf`.

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
- **Funn i Oxalis:** `As4CommonModule` gjør allerede `Security.setProperty("jdk.security.provider.preferred", "AES/GCM/NoPadding:BC")`, men det virker ikke: JDK 25 leser egenskapen bare ved oppstart (fra `java.security` eller `-Djava.security.properties=<fil>`). Testet: satt i kode → SunJCE, satt via egenskapsfil ved oppstart → BC. Planlagt som **M12** (se «Fremtidige oppgaver – mottak»).
- **Negativ test** (endret byte i kryptert vedlegg): `TamperAttachmentInterceptor` i testklienten (fase `USER_STREAM`, mellom WSS4J i `POST_PROTOCOL` og skriving av vedlegg i `PRE_STREAM_ENDING`), aktivert med `-Dtamper.offset=1000000`. Må registreres **etter** `new OxalisOutboundComponent()`. `As4CommonModule` lager Oxalis sin CXF-buss med algoritmesuiten, og et tidligere kall til `BusFactory.getDefaultBus()` lager en vanlig buss som blir global standard → `Algorithm suite "Basic128GCMSha256MgfSha256" is not registered`.
- **Negativ test bestått** (2026-09-24): endret byte i kryptert vedlegg → mottaker: `SoapFault` fra `WSS4JInInterceptor`, årsak `TransformException` → `AEADBadTagException: mac check in GCM failed` (BC). Stoppet i sikkerhetssteget, før `As4Provider`/`As4InboundHandler`/`CustomPersister`. Avsenderen logger `Inbound policy verification failed ... Soap Body is not SIGNED` for feilsvaret; det er vanlig Oxalis-oppførsel for sikkerhetsfeil (feil-svar signeres ikke), ikke knyttet til M8.
- **M8 FERDIG.** Oppsummert: 1 GB-melding går med `--memory=4g` (før: 8g), ~−1,5 GB i `gc.log`, ~48 s (før ~51 s), manipulert chiffertekst avvises før persistering.
- **M9 implementert** i oxalis-ng (`oxalis-ng-as4`, commit `eb89f28c`): `As4MarkableCachedInputStream` (kopierer til CXF `CachedOutputStream` under lesing, `mark`/`reset` spiller av fra cache), `As4RereadableDataSource`, `As4AttachmentDeserializer.makeRereadable()/closeRereadable()`, kall fra `As4LazyAttachmentCollection.add()`, opprydding også i out-fault-kjeden (`AttachmentCleanupInterceptor(Phase.SETUP)` i `As4EndpointsPublisherImpl`). Tester: `As4RereadableAttachmentTest` (7, inkl. WSS4Js ekte `AttachmentContentSignatureTransform`).
  - Bygg: oxalis-ng måtte da bygges med JDK 21. Løst senere (commit `05d88c45` + Mockito 5.24.0): bygger og tester nå også på JDK 25.
  - Modultester: 150 OK; de 6 Jetty-testene (`SendReceiveTest`, `AS4StatusServletTest`, 4 MLS-tester) kunne ikke starte fordi port 8080 var opptatt av brukerens container. **Må kjøres på nytt med ledig port.**
  - Brukerens server, 700 MiB, `--memory=4g`: **1 072 MiB ledig** ved `ReceiptPersister` (mot 24 MiB med bare M8, +1 048 MiB ≈ WSS4J-bufferen på 1 GiB).
  - `/data/cxf-tmp`: under mottak to filer (rå kryptert ~741 MB + M9s dekrypterte kopi), **tom etter vellykket mottak**.
  - `--memory=2g` (heap ~1 433 MiB): **OOM**. Årsak (ny, tredje kopi): WSS4J `SignatureProcessor` setter `javax.xml.crypto.dsig.cacheReference=TRUE`; i Santuario `DOMReference` gjør det at `DigesterOutputStream(md, true)` legger all digest-input (hele vedlegget) i en `UnsyncByteArrayOutputStream`, og kopien (`digestInput`) lever til forespørselen er ferdig. Forklarer 1 796 MiB brukt i 4g-testen (ikke søppel, som først antatt).
  - Upstream-fiks: **WSS-727** (commit 6726da983f, 2026-09-22) – slår av `cacheReference` bare for vedleggsreferanser og kjenner dem igjen på transform-algoritmen. Kun på `master` (4.x), ikke i 3.0.6/4.0.2 og ikke på `3_0_x-fixes`.
  - Foreslått **M9b**: kopi av WSS4J 3.0.5 `SignatureProcessor` med WSS-727 som `As4SignatureProcessor`, registrert via `WSSConfig` på AS4-endepunktet (`PolicyBasedWSS4JInInterceptor` har ingen konstruktør for `wss4j.processor.map`). Forventet −0,75 til −1,5 GB → 2g realistisk. Fjernes når WSS4J med WSS-727 tas i bruk.
  - Tamper-test med 700 MiB: to temp-filer under mottak, **`/data/cxf-tmp` tom etter avvisning** – opprydding i out-fault-kjeden virker.
  - Hele `oxalis-ng-as4`-testsuiten med ledig port 8080: **139 tester, 0 feil, 0 hoppet over** (inkl. `SendReceiveTest`, `AS4StatusServletTest`, MLS-testene og `As4RereadableAttachmentTest`).
  - **M9 FERDIG.** Neste: M9b (WSS-727 lokalt).
- **M9b implementert** (commit `ed13e6a8`): `org.apache.wss4j.dom.processor.As4SignatureProcessor` = WSS4J 3.0.5 `SignatureProcessor` + WSS-727 (diff mot original er nøyaktig upstream-patchen + klassenavn; `validateSignature` er package-private for testen). Registrert i `As4Servlet.loadBus()` via `WSSConfig` på endepunktet (`endpointImpl.getProperties().put(WSSConfig.class.getName(), ...)`, `setProcessor(WSConstants.SIGNATURE, As4SignatureProcessor.class)`) – CXF bruker en `WSSConfig` fra kontekst-egenskapene hvis den finnes.
  - Verifisert at Santuarios `XMLSignature.validate` gjør det samme som upstream-løkken (manifester valideres bare med `org.jcp.xml.dsig.validateManifests`, som WSS4J ikke setter).
  - Tester: `As4SignatureProcessorTest` (4): verifisering over body + vedlegg via `WSSecurityEngine`, endret vedlegg avvises, ingen digest-input bufret for vedleggsreferansen (body fortsatt bufret), og kontrolltest som viser at gammel `validate` bufrer hele vedlegget.
  - Hele `oxalis-ng-as4`: **143 tester, 0 feil**. `SendReceiveTest` med debug-logging viser at serveren bruker `As4SignatureProcessor` (klienten bruker standard `SignatureProcessor` for kvitteringen).
  - Brukerens server, 700 MiB, **`--memory=2g`** (heap 1 434 MiB): **gikk gjennom**, **62 MiB brukt** ved `ReceiptPersister` (1 372 MiB ledig), total tid 48,9 s.
  - Tamper-test med M9b: fortsatt avvist (`AEADBadTagException: mac check in GCM failed`).
  - **M9b FERDIG.**

### Oppsummering mottak (700 MiB → ~741 MB komprimert)

| Etter | Container | Brukt ved `ReceiptPersister` | Tid |
|---|---|---|---|
| Baseline | 8 GB | 3 556 MiB | ~51 s |
| M8 | 4 GB | 2 844 MiB | ~48 s |
| M8 + M9 | 4 GB | 1 796 MiB | – |
| M8 + M9 + M9b | **2 GB** | **62 MiB** | ~49 s |

**M10 (krypterte temp-filer):** kun JVM-flagg i brukerens server: `-Dorg.apache.cxf.io.CachedOutputStream.CipherTransformation=AES/CTR/NoPadding` (CTR strømmer; GCM ville gitt full bufring ved dekryptering igjen). CXF `CipherPair.getDecryptor()` lager ny `Cipher` per strøm, så M9s «åpne ny før gammel lukkes» er trygt. Test i oxalis-ng (commit `a818f947`): `As4RereadableAttachmentTest.resetWorksWithEncryptedTempFile` (fil på disk ≠ klartekst, gjentatt `reset()` gir riktig innhold). Brukerens server med flagget: temp-filene i `/data/cxf-tmp` inneholder bare tilfeldige bytes under mottak (ingen MIME-tekst eller gzip-header) – **M10 FERDIG**. Total tid 50,6 s (mot 48,9 s uten), dvs. ~+3 %, innenfor variasjonen mellom kjøringer.

**Oppstartssjekk** (brukerens server, `verifyCryptoSetup()` etter `server.start()`, stopper serveren ved feil): `AES/GCM/NoPadding` → `StreamingGCM`, GCM-dekryptering strømmer (`update()` gir klartekst med én gang), `BC` registrert, PKCS12 via `SUN`, CXF-cachemappen finnes og er skrivbar, `CipherTransformation` er satt og ikke GCM. Fanget første gang at `CipherTransformation` manglet i kjøringen; etter retting: 700 MiB med `--memory=2g`, 1 372 MiB ledig ved `ReceiptPersister` (uendret med M10).

Heap-bruken vokser ikke lenger med meldingsstørrelsen; store data går via temp-filer i `/data/cxf-tmp` (~2 × komprimert størrelse per samtidige melding under mottak). Forventet ~1,5 GB brukt ved `ReceiptPersister`. Sendersiden (runde 3–4) tas etter at mottakssiden er ferdig.

## Hva virker, begrensninger og midlertidige løsninger

### Virker for store meldinger (testet med 700 MiB binær → XML ~979 MB, gzip ~741 MB)

- **Mottak** med M8 (`StreamingGcmProvider` i serveren) + M9 + M9b + M10: 2 GB container, 62 MiB heap ved `ReceiptPersister`, temp-filer kryptert og slettet etter både vellykket og avvist melding.
- **Sending** via `TransmissionRequestBuilder` (B1) og `TransmissionRequestFactory` (B1b), med B3: ~126 MiB heap-topp.
- **Innkapsling i SBDH** av payload uten SBDH (`SbdhWrapper`, `XmlContentWrapper`) strømmer til temp-fil; store tekstnoder deles opp av StAX (B2 ikke nødvendig).
- Ingen grense på 2³¹ byte igjen i mottak eller sending.

### Virker ikke / begrensninger

| Hva | Konsekvens | Merknad |
|---|---|---|
| `oxalis.transformer.detector=legacy` | `NoSbdhParser` leser hele dokumentet som DOM (5–10× størrelsen i heap) | Bruk `noop` (standard) og lever payload med SBDH |
| Payload uten SBDH med `detector=noop` | Avvises (`Content does not contain SBDH`), med mindre headeren er komplett inkl. `creationDateAndTime`, som `TransmissionRequestBuilder` ikke kan sette | Uendret oppførsel, ikke en regresjon |
| Oxalis uten `StreamingGcmProvider` | SunJCE bufrer hele chifferteksten ved dekryptering (3–5×), hard grense 2³¹ byte | M8 finnes bare i brukerens server. Linjen `jdk.security.provider.preferred` i `As4CommonModule` har ingen effekt (JDK leser den bare ved oppstart) |
| Signert, men ukryptert vedlegg inn | M9-kroken gjelder vedlegg WSS4J legger til etter dekryptering; et rått signert vedlegg kan fortsatt bli bufret av WSS4J `BufferedInputStream` | Teoretisk: Peppol AS4 krypterer alltid |
| `javax.xml.stream.isCoalescing=true` | `getText()` gir én `String` for hele tekstnoden ved innkapsling | Ikke standard; vefa bruker standard |
| Flere store meldinger samtidig | Heap er ikke lenger problemet, men disk (~2,2 × komprimert størrelse per melding under mottak) og CPU | M5 (samtidighetsgrense) er utsatt |
| Timeouts hos andre | Andre aksesspunkter, proxyer og lastbalanserere har egne grenser | Må avklares med partnere |
| `PeekingInputStream` | Leser alt til `byte[]` | `@Deprecated`, ikke lenger brukt av Oxalis |
| `MessagingProviderTest_*` | Feiler bare når de kjøres filtrert (`-Dtest=...`), også før disse endringene | OpenTelemetry-mock, ikke relatert |

### Midlertidig – kan fjernes senere

| Hva | Hvor | Fjernes når |
|---|---|---|
| **`As4SignatureProcessor`** (M9b, backport av WSS-727) + registreringen i `As4Servlet` + `As4SignatureProcessorTest` | oxalis-ng, `org.apache.wss4j.dom.processor` | Oxalis bruker en WSS4J-release med WSS-727. Per 2026-09-25 finnes ingen: siste på Maven Central er WSS4J 4.0.1, som også er det CXF 4.1.8 bruker. **Viktig:** klassen er en kopi av WSS4J **3.0.5**. Oppgraderes WSS4J/CXF (f.eks. til CXF 4.1 / WSS4J 4.0.x) før WSS-727 er ute, må kopien lages på nytt fra den nye versjonens `SignatureProcessor` – ellers nedgraderes signaturverifiseringen og mister nyere sikkerhetsrettinger (f.eks. replay-cache-fiksen #699) |
| `StreamingGcmProvider` i brukerens `Main` | Brukerens server | Når **M12** er gjort (bygget inn i oxalis-ng). SunJCE-oppførselen er bevisst i JDK og forsvinner ikke ved oppgradering |
| `verifyCryptoSetup()`s GCM-sjekk | Brukerens server | Bør beholdes; en tilsvarende oppstartslogg kommer inn i oxalis-ng med M12 |
| M9/B3 (`As4MarkableCachedInputStream`, `As4RereadableDataSource`, `CompressionUtil`) | oxalis-ng | Ikke knyttet til en oppgradering: WSS4J `AttachmentContentSignatureTransform` bruker fortsatt `mark(Integer.MAX_VALUE)` på `master`. Kan fjernes bare hvis WSS4J endrer det (kandidat for upstream-patch, B6) |
| `-XX:NativeMemoryTracking=summary`, `-XX:+HeapDumpOnOutOfMemoryError` | `DockerfileLocal` | Etter testing (NMT koster litt; heap dump trenger diskplass) |
| `-XX:+UseZGC` | `DockerfileLocal` | Valgfritt: heap-bruken er lav, så standard G1 holder |
| `TamperAttachmentInterceptor` | Testklienten | Aldri i produksjon; aktiveres bare med `-Dtamper.offset` |
| `AdministrativeMessageInMemory` | Testklienten | Kan slettes; `AdministrativeMessageOnDisk` erstatter den |

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

### Fremtidige oppgaver – mottak

| # | Tiltak | Effekt | Omfang | Løsbarhet |
|---|---|---|---|---|
| M12 | **Bygg M8 inn i oxalis-ng.** Registrer `StreamingGcmProvider` (bare `Cipher.AES/GCM/NoPadding` → BC) på plass 1 i **`As4InboundModule`**, ikke i `As4CommonModule` (som også lastes av klienten). Gjør det **konfigurerbart**, f.eks. `oxalis.as4.inbound.streaming_gcm = true` som standard, fordi det gjelder hele JVM-en. Fjern den virkningsløse `jdk.security.provider.preferred`-linjen i `As4CommonModule`. Logg ved oppstart hvilken provider `AES/GCM/NoPadding` faktisk gir og om dekrypteringen strømmer (GCM-delen av brukerens `verifyCryptoSetup()`). Dokumenter at BC gir ut klartekst før taggen er sjekket (trygt i Oxalis-flyten: avvist før persistering). Tester: provideren gir `StreamingGCM` når innstillingen er på og SunJCE når den er av | Alle Oxalis-mottakere slipper 3–5× heap og 2³¹-grensen ved dekryptering; brukeren kan fjerne `StreamingGcmProvider` fra egen `Main` (beholde `verifyCryptoSetup()`) | ½–1 d | 4 |

**Hvorfor bare mottak og konfigurerbart** (målt 2026-09-25, AES-GCM-**kryptering** av 741 MB i 16 KiB-blokker som TLS): SunJCE 4 653 MB/s (bruker CPU-ens AES-instruksjoner), BC 166 MB/s – **~28× tregere**. Ved dekryptering av store vedlegg er BC like rask (132 mot 121 MB/s), fordi SunJCE der bufrer alt. En JVM-global `StreamingGCM` ville derfor gjort WSS4J-kryptering av vedlegg på sendersiden og TLS som JVM-en selv terminerer mye tregere. Brukerens server har TLS terminert foran Jetty (HTTP på 8080), så den påvirkes ikke.

**Ideell løsning på sikt (upstream WSS4J):** `EncryptionUtils.decryptAttachment` bruker `Cipher.getInstance(jceAlgorithm)` uten provider, mens `KeyUtils` respekterer `JCEMapper.getProviderId()`. En liten WSS4J-patch som lar vedleggsdekrypteringen bruke en valgt provider ville gjort det JVM-globale grepet unødvendig.

Alternativ til M9: egen JSR-105-provider med en kopi av Attachment-Content-Signature-Transform som bufrer til fil. Dekker også sendersiden (erstatter B3). 3–5 d.

### Forventet containerstørrelse (heap 75 %, ~0,5 GB grunnforbruk)

| Etter | Heap per melding | 1 samtidig | 2 samtidige |
|---|---|---|---|
| I dag | ~4–6 GB | ~6–9 GB | ~12–17 GB |
| M8 | ~1,6 GB | ~3 GB | ~5 GB |
| M8 + M9 | noen titalls MB | ~1–1,5 GB | ~1–1,5 GB |

Disk per samtidige melding: ~2,3 GB i dag, ~3,3 GB etter M9.

## Status – sendersiden (startet 2026-09-25)

- Klienten (`efm-peppol-accesspoint-testing/client`) bruker `OxalisOutboundComponent` + `TransmissionRequestBuilder.payLoad(InputStream)` med `setTransmissionBuilderOverride(true)` og `overrideAs4Endpoint(...)`, og har `TamperAttachmentInterceptor` (registrert etter `new OxalisOutboundComponent()`, endrer vedlegget etter WSS4J i `USER_STREAM`). A1, A2 og A7 er allerede oppfylt.
- **B3 implementert** (commit `0b399315`): `CompressionUtil` låser CXF-cachen i stedet for å lukke den og returnerer `As4MarkableCachedInputStream(CachedOutputStream)` (ny konstruktør som spiller av en ferdig cache). WSS4J-signering bruker da `mark`/`reset` på den i stedet for `BufferedInputStream`. Lukking sletter cachen (`As4MessageSender` lukker i `finally`). Fikser også at en M10-kryptert cache ikke kunne leses på nytt etter `close()` (nøkkelen ødelegges). Tester: `CompressionUtilTest.compressedStreamCanBeReadAgainAfterReset`, `As4RereadableAttachmentTest.replaysCompleteCacheAndDeletesItOnClose`. Hele `oxalis-ng-as4`: **146 tester, 0 feil** (inkl. `SendReceiveTest`, som sender gjennom `CompressionUtil`).
- Testklienten (`new-work`): `AdministrativeMessageOnDisk` (base64 strømmes til temp-fil, ~1 MiB heap; verifisert med 64 MB heap og 10 MiB melding), `getSizeInBytes()` → `long`, og `Main` logger `Client heap peak while sending` (nullstiller topp-verdiene for heap rett før sending; topp inkluderer søppel, så øvre grense).
- **Baseline sender** (uten B3, 700 MiB, uten `-Xmx` → maks 9 216 MiB): topp **3 661 MiB** under sending.
- **Med B3** (samme innstillinger): topp **2 861 MiB** (−800 MiB). Resten er i hovedsak `TransmissionRequestBuilder.savePayLoad` (`ByteStreams.toByteArray`, ~2× 979 MB i topp under innlesing, ~1 GB beholdes som `ByteArrayInputStream`) → B1.
- **B1 implementert** (commit `785124b7`): `TransmissionRequestBuilder` holder payloaden i en CXF `CachedOutputStream` (minne under terskelen, ellers temp-fil med CXFs mappe/`MaxSize`/kryptering) i stedet for `byte[]`. Hver lesing (SBDH-parsing, innholdsdeteksjon, innkapsling, endelig payload) åpner en ny strøm. `holdTempFile()` mens builderen leser (ellers sletter CXF filen når første lesestrøm lukkes – fanget av ny test); `build()` gir cachen videre til requesten og slipper den, så temp-filen slettes når payload-strømmen lukkes. `SbdhWrapper.wrap(..., OutputStream)` for innkapsling fra cache til cache. `getPayload()` (protected) kaster nå `IOException`. API-et `payLoad(InputStream)` er uendret.
  - Tester: `largePayloadWithSbdhIsPassedOnUnchanged` (1 MiB, byte for byte), `largePayloadIsWrappedFromCacheToCache` (innkapsling rett mot filbasert cache – builderens vei uten SBDH krever en innholdsdetektor, som testoppsettet ikke har; de gamle testene for den er `@Ignore`). Full `oxalis-ng-outbound` 37/37 og `oxalis-ng-document-sniffer` 23/23 grønne; `oxalis-ng-standalone` kompilerer. (`MessagingProviderTest_*` feiler bare når de kjøres filtrert med `-Dtest`, også før B1 – OpenTelemetry-mock.)
  - Brukerens måling (700 MiB, samme innstillinger, uten `-Xmx`): topp **126 MiB** under sending. **B1 FERDIG.**

### Oppsummering sender (700 MiB binær → XML ~979 MB)

| Etter | Topp under sending |
|---|---|
| Baseline | 3 661 MiB |
| B3 | 2 861 MiB |
| B3 + B1 | **126 MiB** |

**B1b implementert** (commit `88ecbd69`): den andre veien inn i klientbiblioteket. `TransmissionRequestFactory` bruker CXF `CachedOutputStream` (med `holdTempFile()` mens den leses flere ganger) i stedet for `PeekingInputStream`; `XmlContentWrapper` pakker inn til CXF-cache i stedet for `ByteArrayOutputStream` (temp-filen slettes når strømmen lukkes). `PeekingInputStream` (offentlig i `oxalis-ng-commons`, som ikke har CXF, og `getContent()` gir `byte[]`) er ikke lenger i bruk og er markert `@Deprecated`. Tester: `TransmissionRequestFactoryTest.largePayloadWithSbdhIsPassedOnUnchanged`, `XmlContentWrapperTest.largeContentIsWrapped`. `oxalis-ng-commons` 81/81, `oxalis-ng-outbound` 39/39; standalone og testbed kompilerer.

**B2 trengs ikke** (målt 2026-09-25): både Woodstox 7.1 og JDK-ens StAX deler store tekstnoder i biter når de ikke slår sammen tekst (standard, og det vefa `XMLStreamUtils.copy` bruker): 200 mill. tegn i ett element → lengste `getText()` 4 000 (Woodstox) / 16 384 (JDK) tegn. `SbdhWrapper.wrap` inn i CXF-cache (B1) pakket inn 200 MB uten SBDH med `-Xmx256m` på 0,6 s. Forbehold: `javax.xml.stream.isCoalescing=true` ville gitt problemet tilbake. `detector=legacy` (`NoSbdhParser`, DOM) er en separat minnefelle.

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
