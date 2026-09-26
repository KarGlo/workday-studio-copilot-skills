# Skill: workday-studio-integration (dla GitHub Copilot)

Skill w formacie **Agent Skills** (`SKILL.md` + pliki referencyjne). Copilot (VS Code agent mode, Copilot CLI, coding agent) wykrywa go w `.github/skills/`. Działa też w Claude Code.

## Jak używać

1. Skopiuj `.github/skills/workday-studio-integration/` do katalogu głównego swojego workspace Eclipse (albo sklonuj repozytorium jako ten katalog). Otwórz go w VS Code tak, żeby `.github/skills/` był w korzeniu.
2. W czacie Copilota (tryb Agent) opisz integrację, np.:
   - „Utwórz integrację Outbound REST w projekcie INT_Worker_Sync: RaaS 'Workers' → JSON → POST na URL z atrybutu, raport błędów per rekord.”
   - „Dodaj do INT_Moja_Integracja krok, który po zakończeniu wysyła plik na SFTP.”
   - „Zrób integrację na StarterKit (SSK), która woła CallRaaS i loguje wynik przez CreateLogEntry.”
3. Scenariusz A: najpierw utwórz pusty projekt w Studio (File > New > Workday Project). Skill wypełni `assembly.xml` i `assembly-diagram.xml`.
4. Otwórz assembly w Studio. Studio samo dorysuje połączenia (diagram będzie oznaczony jako zmieniony, więc zapisz go). Sprawdź widok **Problems**.

Tryb StarterKit wymaga szablonu `BASE_SSK_Template` w workspace. To kopia StarterKita przygotowana jako neutralny szablon; **nie jest częścią repozytorium**, bo zawiera kod StarterKita. Importujesz go raz do Studio (File > Import > Existing Projects into Workspace). Nowe integracje SSK powstają przez Copy/Paste tego projektu w Studio i przemianowanie `tools/RebrandSsk.java` (patrz `references/ssk.md`).

## Zawartość

| Plik | Po co |
|---|---|
| `SKILL.md` | przepływ pracy i złote zasady (ładowany przy aktywacji) |
| `references/*.md` | wiedza czytana na żądanie: struktura XML, komponenty, kroki, MVEL, integration system, błędy, diagram, checklist, SSK |
| `templates/` | szkielety `assembly.xml` / `assembly-diagram.xml` |
| `examples/` (tylko lokalnie, nie w repo) | zwalidowane projekty pilotażowe Outbound REST (wersja czysta i SSK) |
| `tools/WdCheck.java` | weryfikacja projektu (Copilot uruchamia ją po każdej zmianie, aż do `RESULT: OK`) |
| `tools/RebrandSsk.java` | rebranding kopii `BASE_SSK_Template` na nową integrację (macOS/Windows/Linux) |
| `tools/SskApi.java` | katalog API StarterKita (local-in i ich parametry), czytany na żądanie z projektu SSK w workspace |
| `tools/StudioDocs.java` | dokumentacja Workday Studio i schemat dowolnego elementu, czytane na żądanie z lokalnej instalacji Studio |
| `references/element-catalog.md` | katalog wszystkich ~120 wbudowanych elementów dozwolonych w chmurze (opis w jednej linii + wymagane atrybuty) |

## Narzędzia (Java 17, bez dodatkowych instalacji)

```bash
java .github/skills/workday-studio-integration/tools/WdCheck.java INT_Moj_Projekt
java .github/skills/workday-studio-integration/tools/RebrandSsk.java INT_Nowy INT_Nowy "INT Nowy" intnowy
java .github/skills/workday-studio-integration/tools/StudioDocs.java element json-splitter
java .github/skills/workday-studio-integration/tools/StudioDocs.java search paged get
```

`StudioDocs` udostępnia Copilotowi dokumentację Workday Studio (345 stron, 126 elementów z pomocą kontekstową) bez kopiowania jej do repo:
- czyta strony z pluginu pomocy w lokalnej instalacji, więc zawsze pasują do zainstalowanej wersji,
- Copilot doczytuje tylko potrzebną stronę, co oszczędza tokeny,
- polecenie `element` dokleja do strony pomocy schemat elementu z XSD: wymagane atrybuty, wartości domyślne, kolejność dzieci i informację, czy element jest dozwolony w chmurze,
- omija jeden błąd w pomocy Workday: kontekst `json-splitter` wskazuje tam stronę o `mtable-splitter`.

`WdCheck` wykonuje sześć kontroli, korzystając z komponentów lokalnego Workday Studio:
1. XSD (te same schematy co walidator Studio),
2. kompilacja MVEL runtime'em Studio,
3. referencje diagramu,
4. prawdziwy reconciler diagramu Studio (co edytor zmieni przy otwarciu),
5. konwencja swimlane'ów (jeden lane = jeden flow, lane'y Try/Catch),
6. wywołania `vm://`, wymagane parametry `in*`, nazwy parametrów/atrybutów/aliasów i pliki XSLT.

Studio szukane jest w domyślnej lokalizacji. Inną podajesz przez `--studio <katalog>` albo zmienną `WORKDAY_STUDIO_HOME`. W projektach SSK sprawdzany jest tylko własny kod (wszystko osiągalne z `Main`).

## Skąd ta wiedza i jak ją sprawdzono

- Reguły pochodzą z reverse engineeringu Workday Studio 2026.24.159:
  - schematy XSD,
  - walidatory semantyczne,
  - edytor diagramu (`ViewReconciler`),
  - dokumentacja,
  - oficjalne przykłady.
- Wszystkie fragmenty XML w referencjach przechodzą walidację XSD Studio i kompilację MVEL Studio.
- Pilot i szablon SSK przeszły dodatkowo przez prawdziwy reconciler diagramu Studio, uruchomiony poza GUI (`tools/WdCheck.java`).
- Pliki Workday (XSD, dokumentacja) **nie są kopiowane** do repo. Referencje zawierają własne opracowanie, a pełną dokumentację narzędzia czytają z lokalnej instalacji Studio.

## Licencja

Apache License 2.0 (plik `LICENSE` w katalogu głównym repozytorium). Skill i narzędzia można swobodnie używać, modyfikować i rozpowszechniać, także komercyjnie w firmach, pod warunkiem zachowania informacji o licencji. Licencja zawiera wprost udzieloną licencję patentową.

## Znane ograniczenia

- Pilot nie był jeszcze uruchomiony na tenancie Workday. Semantykę runtime (np. `error-as-response`, statusy eventu) opisano według dokumentacji.
- Narzędzia Java przetestowano na macOS. Na Windows powinny działać tak samo, ale domyślna ścieżka Studio jest tam zgadywana; w razie potrzeby użyj `--studio`.
- Usuwanie komponentów ze środka dużych assembly lepiej robić w edytorze Studio (patrz `references/diagram.md`).
