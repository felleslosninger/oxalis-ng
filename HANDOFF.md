# HANDOFF – store meldinger (1–2 GB) i Oxalis-NG

Branch: `light-on-memory` (ingen kodeendringer ennå, peker på `1b81c3a4`). Sist oppdatert 2026-09-24.

## Mål

Sende og motta AS4-meldinger på ~1 GB **komprimert** (evt. opp mot 2 GB) uten at Java-prosessene trenger enorme mengder minne. **Første prioritet er mottakssiden**, som kjører i container med minne som største begrensning (Java 25, `InitialRAMPercentage=75`, `MaxRAMPercentage=75`).

Payloadene er en liten XML med ett element som inneholder kryptert binærdata i base64. gzip gir bare ~75 %, så 1 GB komprimert ≈ 1,33 GB ukomprimert.

## Status

- Analyse ferdig (bare hovedkode, ikke test). Ingen kode endret.
- Neste steg: runde 1 (konfigurasjon, M2–M7), deretter **M8** som første kodetiltak.

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
| M8 (B8) | `org.apache.xml.security.algorithms.JCEMapper.setProviderId("BC")` ved oppstart (f.eks. i `As4CommonModule`). Test med endret GCM-tag: må avvises **før** noe persisteres (BC slipper ut klartekst før taggen er verifisert; signaturverifiseringen leser hele strømmen før `As4InboundHandler`) | −3 til −4 GB, fjerner grensen på 2³¹ ved dekryptering | 1–2 d | 4 |
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

## Plan – sendersiden (senere)

| # | Tiltak | Effekt | Omfang |
|---|---|---|---|
| A1 | Øk `oxalis.http.timeout.read` (minutter, evt. regnet ut fra størrelse) | Nødvendig | ½ d |
| A2 | Lever filene med SBDH ferdig på plass | −3 til −5 GB, slipper B2 | 0 d |
| B3 | `CompressionUtil` returnerer en filstrøm med ekte `mark`/`reset` (CXF `DelegatingInputStream` sender `markSupported` videre) | −1,07 / −1,6 GB | 1–2 d |
| B1 | `TransmissionRequestBuilder` / `PeekingInputStream`: les SBDH med `mark(64 KB)`, payload i temp-fil (evt. `payLoad(Path)`) | −1,33 / −2,7 GB | 3–5 d |
| B2 | (bare hvis ikke A2) Strømmende SBDH-innpakking til fil og egen StAX-kopi med `getTextCharacters()` i biter | −3 til −5 GB | 1–2 d |
| B6 | Upstream-patch til WSS4J (fil i stedet for `mark(Integer.MAX_VALUE)`) | Varig løsning for B3/M9 | 2–3 d + ventetid |

Anbefales ikke: bytte AS4-stakk (phase4 m.fl. bruker også WSS4J med DOM).

## Beslutninger

- Mottakssiden optimaliseres først.
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
