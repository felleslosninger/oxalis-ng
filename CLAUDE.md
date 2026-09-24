# CLAUDE.md

Veiledning for Claude Code i dette repoet. Pågående arbeid og beslutninger står i [HANDOFF.md](HANDOFF.md) – les den først.

## Prosjektet

Oxalis-NG er et Peppol eDelivery Access Point (AS4). Serverdelen mottar meldinger, klientbiblioteket sender. AS4 er implementert i eget modul over Apache CXF + WSS4J.

| Modul | Innhold |
|---|---|
| `oxalis-ng-api` | Offentlige grensesnitt (`TransmissionRequest`, `PersisterHandler`, `HeaderParser` …) |
| `oxalis-ng-commons` | Felles infrastruktur: persistering, HTTP-konfig, SBDH-parser, Guice-moduler |
| `oxalis-ng-outbound` | Klientbibliotek: `TransmissionRequestBuilder`, `TransmissionRequestFactory`, `DefaultTransmitter` |
| `oxalis-ng-inbound` | Servlet-oppsett for mottak |
| `oxalis-ng-extension/oxalis-ng-as4` | AS4-implementasjonen. `network.oxalis.ng.as4.inbound/outbound` + **forket CXF-vedleggsparsing** i `org.apache.cxf.attachment` (`As4AttachmentDeserializer`, `As4LazyAttachmentCollection`, …) |
| `oxalis-ng-legacy/*` | Statistikk, persistens (DB), document-sniffer (`SbdhWrapper`, `NoSbdhParser`) |
| `oxalis-ng-dist/*` | `oxalis-ng-server` (Jetty), `oxalis-ng-standalone` (CLI-sender), war, distribusjon |
| `oxalis-ng-test` | Testhjelpere/integrasjonstester |

## Bygg og test

```bash
mvn clean package
```

```bash
mvn -pl oxalis-ng-extension/oxalis-ng-as4 -am test
```

- Kildekode kompileres for Java 11 (`java.version` i `pom.xml`), men brukeren **kjører på Java 25**.
- Offline-bygg (`mvn -o`) feiler fordi `opentelemetry-bom` ikke ligger i lokal `~/.m2`.
- Nøkkelversjoner (`pom.xml`): CXF 4.0.11, WSS4J 3.0.5, vefa-peppol 4.6.0, peppol-sbdh 2.5.0. Kildejarer for CXF/WSS4J finnes i `~/.m2` og kan pakkes ut for å lese tredjepartskode.

## Konvensjoner

- Guice for DI (`OxalisModule`, `@Inject`), Typesafe Config (`reference.conf`, `@Path`/`@DefaultValue`-enums som `HttpConf`).
- Lombok (`@Slf4j`, `@Getter`) brukes i mange klasser.
- Klassene i `oxalis-ng-as4/src/main/java/org/apache/cxf/attachment` er forks av CXF; sikkerhetsfikser i CXF når ikke disse automatisk (se kommentar i `As4AttachmentDeserializer`).
- Endringer skal matche eksisterende stil og kommentartetthet.

## Brukerens miljø

- Mottakssiden kjører i container med minne som hovedbegrensning. JVM-flagg: `-XX:InitialRAMPercentage=75.0 -XX:MaxRAMPercentage=75.0`, Java 25.
- **Repoets `Dockerfile` brukes ikke** – ikke trekk konklusjoner fra den eller rapporter feil i den.
- Brukeren kommuniserer på norsk.
